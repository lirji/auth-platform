package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.AccessReviewModels.*;
import com.lrj.authz.governance.domain.IdentityModels.CurrentContext;
import com.lrj.authz.governance.domain.RoleMigrationModels.Snapshot;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.AccessReviewDtos.*;
import com.lrj.authz.protocol.PersonnelImpactDtos.GrantSource;
import com.lrj.authz.protocol.RequestDtos.Page;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;
import static com.lrj.authz.governance.domain.RoleMigrationTaskModels.GROUP_SOURCE;

/** 人工复核每请求一个数据库检查点，副作用复用严格撤权，不持旧身份后台继续执行。 */
public final class AccessReviews {
    private static final int MAX_SELECTED=100,PAGE_SIZE=20,MAX_REASON=500,MAX_SNAPSHOT_BYTES=65536;
    private static final String RECEIPT_COMPLETED="COMPLETED",COMMAND_NAMESPACE="ACCESS_REVIEW_COMMAND";
    private static final String CREATE="CREATE_ACCESS_REVIEW",DECIDE="DECIDE_ACCESS_REVIEW",CONFIRM="CONFIRM_ACCESS_REVIEW",CANCEL="CANCEL_ACCESS_REVIEW",REASSIGN="REASSIGN_ACCESS_REVIEW";
    private static final ObjectMapper JSON=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final PortalPermissions diagnostics;private final AccessManagement access;private final PersonnelImpact impact;
    private final AccessReviewMapper mapper;private final RoleMigrationMapper snapshots;private final IdentityMapper commands;private final TransactionTemplate tx;
    /** 所有写入沿用同一有界数据库事务，图调用由现有可靠投影进程完成。 */
    public AccessReviews(PortalPermissions diagnostics,AccessManagement access,PersonnelImpact impact,AccessReviewMapper mapper,RoleMigrationMapper snapshots,IdentityMapper commands,TransactionTemplate tx){
        this.diagnostics=diagnostics;this.access=access;this.impact=impact;this.mapper=mapper;this.snapshots=snapshots;this.commands=commands;this.tx=tx;
    }
    /** 候选必须同时具有当前管理与独立诊断资格，不用名称／邮箱推断负责人。 */
    public List<Responsible> responsibles(VerifiedLogin login,Partition p){
        var before=diagnostics.reviewAuthorize(login,p,null);var result=qualified(p);verify(login,p,null,before);return result;
    }
    /** 列表按完整分区和稳定UUID分页，关闭页面后仍可找到原任务。 */
    public Page<Summary> list(VerifiedLogin login,Partition p,String member,String after){
        if(member!=null)BootstrapCommand.uuid(member);if(after!=null)BootstrapCommand.uuid(after);
        var before=diagnostics.reviewAuthorize(login,p,null);var rows=mapper.tasks(p,member,after==null?"":after);verify(login,p,null,before);
        return new Page<>(rows.stream().limit(PAGE_SIZE).map(t->new Summary(t.id(),t.membershipId(),t.responsibleMembershipId(),taskState(t.state()),t.version(),t.createdAt(),t.updatedAt())).toList(),rows.size()>PAGE_SIZE?rows.get(PAGE_SIZE-1).id():null);
    }
    /** 当前详情与最初来源快照分别返回，不能以GET推进决定或补造撤权证明。 */
    public Detail get(VerifiedLogin login,Partition p,String id){BootstrapCommand.uuid(id);return tx.execute(status->{var actor=diagnostics.reviewAuthorize(login,p,id);var t=task(p,id,true);var result=detail(login,p,t,actor);verify(login,p,id,actor);return result;});}
    /** 创建仅接受同一真实来源页中明确选中的1–100条来源。 */
    public Detail create(VerifiedLogin login,Create c){
        if(c==null||c.grantIds()==null||c.grantIds().isEmpty()||c.grantIds().size()>MAX_SELECTED)throw error(INVALID_ARGUMENT);
        var p=p(c.tenantId(),c.applicationId(),c.environment());BootstrapCommand.uuid(c.membershipId());BootstrapCommand.uuid(c.responsibleMembershipId());hash(c.basisHash());reason(c.reason());
        var ids=new TreeSet<String>();for(String id:c.grantIds()){BootstrapCommand.uuid(id);if(!ids.add(id))throw error(INVALID_ARGUMENT);}if(c.responsibleGeneration()<1)throw error(INVALID_ARGUMENT);
        return tx.execute(status->{var actor=lockActor(login,p,null,List.of(c.membershipId(),c.responsibleMembershipId()));
            String result=command(actor,p,CREATE,c.commandId(),AccessValues.hash(p,c.membershipId(),c.responsibleMembershipId(),c.responsibleGeneration(),c.basisHash(),c.sourceCursor(),ids,reason(c.reason())),()->{
                requireResponsible(p,c.responsibleMembershipId(),c.responsibleGeneration());
                var report=diagnostics.personnelImpact(login,p,c.membershipId(),null,c.sourceCursor(),impact);
                if(!report.complete()||!c.basisHash().equals(report.basisHash()))throw error(VERSION_CONFLICT);
                var selected=report.sources().stream().filter(s->ids.contains(s.grant().grantId())).toList();if(selected.size()!=ids.size())throw error(ACCESS_DENIED);
                ids.forEach(id->{if(mapper.lockGrant(p,id)==null)throw error(ACCESS_DENIED);});var original=snapshots.selected(p,List.copyOf(ids));if(original.size()!=ids.size())throw error(ACCESS_DENIED);
                var latest=diagnostics.personnelImpact(login,p,c.membershipId(),null,c.sourceCursor(),impact);if(!report.basisHash().equals(latest.basisHash()))throw error(VERSION_CONFLICT);
                String id=id();one(mapper.insertTask(new Task(id,p.tenantId(),p.applicationId(),p.environment(),c.membershipId(),report.target().generation(),report.basisHash(),c.responsibleMembershipId(),c.responsibleGeneration(),TaskState.RUNNING.code(),1,reason(c.reason()),actor.membershipId(),actor.membershipGeneration(),c.commandId(),ids.size(),null,null)));
                for(var s:selected){var row=original.stream().filter(o->o.id().equals(s.grant().grantId())).findFirst().orElseThrow();one(mapper.insertEntry(new Entry(id(),id,p.tenantId(),p.applicationId(),p.environment(),row.id(),row.version(),json(s),originalHash(row),ItemState.PENDING.code(),1,null,id(),null)));}
                audit(p,id,null,actor,c.commandId(),CREATE,null,TaskState.RUNNING.code(),c.reason());return id;
            });verify(login,p,result,actor);return detail(login,p,task(p,result,false),actor);
        });
    }
    /** 决定核对当前依据与原来源版本，组来源撤销必须承认影响整个组。 */
    public Detail decide(VerifiedLogin login,String id,Decide c){
        if(c==null||c.decision()==null)throw error(INVALID_ARGUMENT);var p=p(c.tenantId(),c.applicationId(),c.environment());validate(id,c.commandId(),c.expectedTaskVersion(),c.itemId(),c.expectedItemVersion(),c.reason());hash(c.currentBasisHash());
        return tx.execute(status->{var unheld=taskAfterAuth(login,p,id);var actor=lockActor(login,p,id,List.of(unheld.membershipId(),unheld.responsibleMembershipId()));var t=task(p,id,true);
            command(actor,p,DECIDE,c.commandId(),AccessValues.hash(p,id,c.expectedTaskVersion(),c.itemId(),c.expectedItemVersion(),c.currentBasisHash(),c.decision(),reason(c.reason()),c.wholeGroupAck()),()->{
                responsible(actor,p,t);version(t,c.expectedTaskVersion());if(t.state().equals(TaskState.CANCELLED.code())||t.state().equals(TaskState.COMPLETED.code()))throw error(VERSION_CONFLICT);
                var e=entry(p,id,c.itemId(),c.expectedItemVersion());if(!e.state().equals(ItemState.PENDING.code())&&!e.state().equals(ItemState.INVESTIGATE.code()))throw error(VERSION_CONFLICT);
                var report=diagnostics.personnelImpact(login,p,t.membershipId(),null,null,impact);if(!report.complete()||!report.basisHash().equals(c.currentBasisHash())||report.target().generation()!=t.targetGeneration())throw error(VERSION_CONFLICT);
                if(mapper.lockGrant(p,e.grantId())==null)throw error(ACCESS_DENIED);var row=source(p,e.grantId());if(row.version()!=e.originalVersion()||!originalHash(row).equals(e.originalHash()))throw error(VERSION_CONFLICT);
                if(!report.basisHash().equals(diagnostics.personnelImpact(login,p,t.membershipId(),null,null,impact).basisHash()))throw error(VERSION_CONFLICT);
                ItemState next;
                switch(c.decision()){
                    case KEEP -> next=ItemState.KEPT;
                    case INVESTIGATE -> next=ItemState.INVESTIGATE;
                    case REVOKE -> {
                        if(GROUP_SOURCE.equals(row.sourceType())&&!c.wholeGroupAck())throw error(INVALID_ARGUMENT);
                        if(row.state()!=com.lrj.authz.governance.domain.AccessModels.GrantState.ACTIVE&&row.state()!=com.lrj.authz.governance.domain.AccessModels.GrantState.PENDING)throw error(VERSION_CONFLICT);
                        access.strictRevoke(login,p,e.revokeCommand(),e.grantId(),e.originalVersion());next=ItemState.WAIT_REVOKE;
                    }
                    default -> throw error(INVALID_ARGUMENT);
                }
                one(mapper.decide(p,id,e.id(),e.version(),next.code(),reason(c.reason()),null));one(mapper.refresh(p,id,t.version(),false));audit(p,id,e.id(),actor,c.commandId(),DECIDE,e.state(),next.code(),c.reason());return id;
            });verify(login,p,id,actor);return detail(login,p,task(p,id,false),actor);
        });
    }
    /** 仅真实严格撤权COMPLETED才完成条目，取消或人员变更不能撤回已发意图。 */
    public Detail confirm(VerifiedLogin login,String id,Confirm c){
        if(c==null)throw error(INVALID_ARGUMENT);var p=p(c.tenantId(),c.applicationId(),c.environment());validate(id,c.commandId(),c.expectedTaskVersion(),c.itemId(),c.expectedItemVersion(),c.reason());
        return tx.execute(status->{var unheld=taskAfterAuth(login,p,id);var actor=lockActor(login,p,id,List.of(unheld.responsibleMembershipId()));var t=task(p,id,true);
            command(actor,p,CONFIRM,c.commandId(),AccessValues.hash(p,id,c.expectedTaskVersion(),c.itemId(),c.expectedItemVersion(),reason(c.reason())),()->{
                responsible(actor,p,t);version(t,c.expectedTaskVersion());var e=entry(p,id,c.itemId(),c.expectedItemVersion());if(!e.state().equals(ItemState.WAIT_REVOKE.code()))throw error(VERSION_CONFLICT);
                var row=source(p,e.grantId());if(row.version()!=e.originalVersion()+1||!originalHash(row).equals(e.originalHash()))throw error(VERSION_CONFLICT);
                // 原撤权命令由当时负责人持有，改派后仍按审计中的固定命令验证，不借新身份伪造完成。
                var receipt=access.revocationReceipt(login,p,e.grantId());
                if(!mapper.revokeCommitted(p,e.grantId(),e.revokeCommand(),e.originalVersion()+1)||!RECEIPT_COMPLETED.equals(receipt.status())||receipt.version()!=e.originalVersion()+1||receipt.operationId()==null||!receipt.grantId().equals(e.grantId()))throw error(VERSION_CONFLICT);
                one(mapper.decide(p,id,e.id(),e.version(),ItemState.REVOKED.code(),reason(c.reason()),receipt.operationId()));one(mapper.refresh(p,id,t.version(),false));audit(p,id,e.id(),actor,c.commandId(),CONFIRM,e.state(),ItemState.REVOKED.code(),c.reason());return id;
            });verify(login,p,id,actor);return detail(login,p,task(p,id,false),actor);
        });
    }
    /** 当前合格管理员可以终止未处理项，WAIT_REVOKE保持原意图供以后确认。 */
    public Detail cancel(VerifiedLogin login,String id,Cancel c){
        if(c==null)throw error(INVALID_ARGUMENT);var p=p(c.tenantId(),c.applicationId(),c.environment());validate(id,c.commandId(),c.expectedTaskVersion(),null,0,c.reason());
        return tx.execute(status->{var actor=lockActor(login,p,id,List.of());var t=task(p,id,true);
            command(actor,p,CANCEL,c.commandId(),AccessValues.hash(p,id,c.expectedTaskVersion(),reason(c.reason())),()->{version(t,c.expectedTaskVersion());if(t.state().equals(TaskState.COMPLETED.code())||t.state().equals(TaskState.CANCELLED.code()))throw error(VERSION_CONFLICT);mapper.cancelEntries(p,id,reason(c.reason()));one(mapper.refresh(p,id,t.version(),true));audit(p,id,null,actor,c.commandId(),CANCEL,t.state(),TaskState.CANCELLED.code(),c.reason());return id;});
            verify(login,p,id,actor);return detail(login,p,task(p,id,false),actor);
        });
    }
    /** 原负责人失权后仍可由当前合格管理员显式改派，旧责任和原因保留审计。 */
    public Detail reassign(VerifiedLogin login,String id,Reassign c){
        if(c==null||c.responsibleGeneration()<1)throw error(INVALID_ARGUMENT);var p=p(c.tenantId(),c.applicationId(),c.environment());validate(id,c.commandId(),c.expectedTaskVersion(),null,0,c.reason());BootstrapCommand.uuid(c.responsibleMembershipId());
        return tx.execute(status->{var actor=lockActor(login,p,id,List.of(c.responsibleMembershipId()));var t=task(p,id,true);
            command(actor,p,REASSIGN,c.commandId(),AccessValues.hash(p,id,c.expectedTaskVersion(),c.responsibleMembershipId(),c.responsibleGeneration(),reason(c.reason())),()->{version(t,c.expectedTaskVersion());requireResponsible(p,c.responsibleMembershipId(),c.responsibleGeneration());one(mapper.reassign(p,id,t.version(),c.responsibleMembershipId(),c.responsibleGeneration()));audit(p,id,null,actor,c.commandId(),REASSIGN,t.responsibleMembershipId()+":"+t.responsibleGeneration(),c.responsibleMembershipId()+":"+c.responsibleGeneration(),c.reason());return id;});
            verify(login,p,id,actor);return detail(login,p,task(p,id,false),actor);
        });
    }
    private Task taskAfterAuth(VerifiedLogin login,Partition p,String id){diagnostics.reviewAuthorize(login,p,id);return task(p,id,false);}
    private CurrentContext lockActor(VerifiedLogin login,Partition p,String target,List<String> members){
        var actor=diagnostics.reviewAuthorize(login,p,target);var ids=new TreeSet<>(members);ids.add(actor.membershipId());
        for(String member:ids)if(mapper.lockPrincipal(p,member)==null)throw error(ACCESS_DENIED);
        for(String member:ids)if(mapper.lockMember(p,member)==null)throw error(ACCESS_DENIED);
        access.lockAuthority(login,p);verify(login,p,target,actor);return actor;
    }
    private void verify(VerifiedLogin login,Partition p,String id,CurrentContext before){if(!before.equals(diagnostics.reviewAuthorize(login,p,id)))throw error(VERSION_CONFLICT);}
    private void responsible(CurrentContext actor,Partition p,Task t){if(!actor.membershipId().equals(t.responsibleMembershipId())||actor.membershipGeneration()!=t.responsibleGeneration())throw error(ACCESS_DENIED);requireResponsible(p,t.responsibleMembershipId(),t.responsibleGeneration());}
    private List<Responsible> qualified(Partition p){var authorities=diagnostics.reviewAuthorities(p);return authorities.isEmpty()?List.of():mapper.qualifiedBatch(p,authorities);}
    private void requireResponsible(Partition p,String member,long generation){if(diagnostics.reviewAuthorities(p).stream().noneMatch(a->a.membershipId().equals(member)&&a.generation()==generation)||mapper.qualified(p,member,generation)==null)throw error(ACCESS_DENIED);}
    private Task task(Partition p,String id,boolean lock){var t=mapper.task(p,id,lock);if(t==null)throw error(ACCESS_DENIED);return t;}
    private Entry entry(Partition p,String task,String id,long version){var e=mapper.entries(p,task).stream().filter(v->v.id().equals(id)).findFirst().orElseThrow(()->error(ACCESS_DENIED));if(e.version()!=version)throw error(VERSION_CONFLICT);return e;}
    private Detail detail(VerifiedLogin login,Partition p,Task t,CurrentContext actor){
        var entries=mapper.entries(p,t.id());if(entries.size()!=t.itemCount()||entries.size()>MAX_SELECTED)throw error(DEPENDENCY_UNAVAILABLE);
        var report=diagnostics.personnelImpact(login,p,t.membershipId(),null,null,impact);boolean qualified=diagnostics.reviewAuthorities(p).stream().anyMatch(a->a.membershipId().equals(t.responsibleMembershipId())&&a.generation()==t.responsibleGeneration())&&mapper.qualified(p,t.responsibleMembershipId(),t.responsibleGeneration())!=null;
        return new Detail(t.id(),t.membershipId(),t.targetGeneration(),t.originalBasisHash(),t.responsibleMembershipId(),t.responsibleGeneration(),taskState(t.state()),t.version(),t.reason(),t.createdBy(),t.creatorGeneration(),t.createdAt(),t.updatedAt(),entries.stream().map(e->new Item(e.id(),e.grantId(),original(e.originalJson()),e.originalHash(),itemState(e.state()),e.version(),e.reason(),e.revokeCommand(),e.confirmedOperationId())).toList(),report,qualified,qualified&&actor.membershipId().equals(t.responsibleMembershipId())&&actor.membershipGeneration()==t.responsibleGeneration());
    }
    private Snapshot source(Partition p,String id){var rows=snapshots.selected(p,List.of(id));if(rows.size()!=1)throw error(ACCESS_DENIED);return rows.getFirst();}
    /** 摘要只包含不可变业务输入，排除PENDING→ACTIVE投影与成员瞬时状态。 */
    private static String originalHash(Snapshot s){return AccessValues.hash(s.id(),s.tenantId(),s.applicationId(),s.environment(),s.membershipId(),s.generation(),s.roleId(),s.scope(),s.sourceType(),s.sourceId(),s.validFrom(),s.validTo(),s.groupId(),s.ruleJson(),s.scopeHash());}
    private String command(CurrentContext actor,Partition p,String operation,String command,String hash,Supplier<String> write){
        BootstrapCommand.uuid(command);String operator=actor.membershipId()+":"+actor.membershipGeneration();String bodyHash=AccessValues.hash(operation,hash);commands.reserveCommand(operator,p.tenantId(),COMMAND_NAMESPACE,command,bodyHash);var receipt=commands.lockCommand(operator,p.tenantId(),COMMAND_NAMESPACE,command);
        if(receipt==null||!bodyHash.equals(receipt.payloadHash()))throw error(COMMAND_CONFLICT);if(receipt.completed())return receipt.resultRef();String result=write.get();one(commands.completeCommand(operator,p.tenantId(),COMMAND_NAMESPACE,command,result));return result;
    }
    private void audit(Partition p,String task,String item,CurrentContext actor,String command,String operation,String before,String after,String reason){one(mapper.audit(id(),p,task,item,actor.membershipId()+":"+actor.membershipGeneration(),command,operation,before,after,reason(reason)));one(commands.appendAudit(id(),actor.membershipId()+":"+actor.membershipGeneration(),p.tenantId(),operation,task,mapper.task(p,task,false).version(),command));}
    private static void version(Task t,long v){if(t.version()!=v)throw error(VERSION_CONFLICT);}
    private static TaskState taskState(String value){try{return TaskState.fromCode(value);}catch(IllegalArgumentException failure){throw error(DEPENDENCY_UNAVAILABLE);}}
    private static ItemState itemState(String value){try{return ItemState.fromCode(value);}catch(IllegalArgumentException failure){throw error(DEPENDENCY_UNAVAILABLE);}}
    private static String json(GrantSource s){try{String value=JSON.writeValueAsString(s);if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_SNAPSHOT_BYTES)throw error(INVALID_ARGUMENT);return value;}catch(java.io.IOException failure){throw error(DEPENDENCY_UNAVAILABLE);}}
    private static GrantSource original(String s){try{return JSON.readValue(s,GrantSource.class);}catch(java.io.IOException failure){throw error(DEPENDENCY_UNAVAILABLE);}}
    private static Partition p(String t,String a,String e){var p=new Partition(t,a,e);AccessValues.partition(p);return p;}
    private static String reason(String s){if(s==null||s.trim().isEmpty()||s.trim().length()>MAX_REASON)throw error(INVALID_ARGUMENT);return s.trim();}
    private static void hash(String s){if(s==null||!s.matches("[a-f0-9]{64}"))throw error(INVALID_ARGUMENT);}
    private static void validate(String id,String command,long taskVersion,String item,long itemVersion,String why){BootstrapCommand.uuid(id);BootstrapCommand.uuid(command);reason(why);if(taskVersion<1)throw error(INVALID_ARGUMENT);if(item!=null){BootstrapCommand.uuid(item);if(itemVersion<1)throw error(INVALID_ARGUMENT);}}
    private static void one(int n){if(n!=1)throw error(VERSION_CONFLICT);}
    private static String id(){return UUID.randomUUID().toString();}
    private static GovernanceException error(GovernanceException.Code code){return new GovernanceException(code);}
}
