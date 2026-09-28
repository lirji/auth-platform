package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.IdentityModels.CurrentContext;
import com.lrj.authz.governance.persistence.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 角色与直接授权用例；任何HTTP/CLI写入均复用管理上限和原子审计。 */
public final class AccessManagement {
    private final AccessMapper mapper;private final CatalogMapper catalog;private final IdentityMapper commands;
    private final IdentityGovernance identity;private final TransactionTemplate tx;
    private final FenceMapper fences;
    private final SafetyMapper safety;
    /** 仅所属治理Runtime装配专用数据库事务。 */
    public AccessManagement(AccessMapper mapper,CatalogMapper catalog,IdentityMapper commands,IdentityGovernance identity,TransactionTemplate tx,FenceMapper fences,SafetyMapper safety){this.safety=safety;this.fences=fences;this.mapper=mapper;this.catalog=catalog;this.commands=commands;this.identity=identity;this.tx=tx;}
    /** 先完成升级节点路由再启用；启用后没有自动退回P2的操作。 */
    public void enableStrict(VerifiedLogin login,Partition p,String command){
        tx.executeWithoutResult(status->{
            Manager manager=manager(login,p,true);
            command(manager.context(),p,"ENABLE_STRICT",command,AccessValues.hash(p),()->{
                int changed=fences.enable(p,manager.context().membershipId());
                if(changed==1)audit(manager,p,"ENABLE_STRICT",fences.policy(p).id(),1,command);
                return fences.policy(p).id();
            });
        });
    }
    /** 受控初始化只增加显式准入与管理上限，不恢复停用委派。 */
    public void bootstrap(Partition p,Delegation d,String operator,String command){
        AccessValues.partition(p);BootstrapCommand.uuid(d.membershipId());BootstrapCommand.uuid(command);BootstrapCommand.bounded(operator,100);
        if(d.generation()<1||d.maxDurationSeconds()<1||d.maxDurationSeconds()>31536000)throw new GovernanceException(INVALID_ARGUMENT);
        String caps=AccessValues.json(AccessValues.read(d.capabilitiesJson()));
        tx.executeWithoutResult(status->{
            if(!mapper.memberActive(p,d.membershipId(),d.generation()))throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
            requireCapabilities(p,AccessValues.read(caps));
            int created=mapper.registerPartition(p,operator);
            if(!Boolean.TRUE.equals(mapper.lockPartition(p)))throw new GovernanceException(ACCESS_DENIED);
            Delegation normalized=new Delegation(d.membershipId(),d.generation(),caps,d.maxDurationSeconds());
            int inserted=mapper.registerDelegation(p,normalized,operator);
            if(!normalized.equals(mapper.delegation(p,d.membershipId(),d.generation())))throw new GovernanceException(BINDING_CONFLICT);
            if(created+inserted>0)one(commands.appendAudit(id(),operator,p.tenantId(),"BOOTSTRAP_ACCESS",d.membershipId(),d.generation(),command));
        });
    }
    /** 不可变版本创建和重放都重新验证操作者当前管理上限。 */
    public RoleVersion createRole(VerifiedLogin login,Partition p,String command,String code,long version,List<String> capabilities){
        CatalogManifest.code(code);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        String caps=AccessValues.json(capabilities);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);requireCeiling(manager.delegation(),AccessValues.read(caps));requireCapabilities(p,AccessValues.read(caps));
            String hash=AccessValues.hash(p,code,version,caps);
            String result=command(manager.context(),p,"CREATE_ROLE",command,hash,()->{
                RoleVersion role=new RoleVersion(id(),p.tenantId(),p.applicationId(),p.environment(),code,version,caps,hash);
                int inserted=mapper.insertRole(role,manager.context().membershipId());
                RoleVersion actual=mapper.roleByCode(p,code,version);
                if(actual==null||!hash.equals(actual.contentHash()))throw new GovernanceException(VERSION_CONFLICT);
                if(inserted==1)audit(manager,p,"CREATE_ROLE",actual.id(),version,command);
                return actual.id();
            });
            return mapper.role(p,result);
        });
    }
    /** 完整授权路径与待投影意图同事务；不可用成员/自授予/超范围全部拒绝。 */
    public Grant grant(VerifiedLogin login,Partition p,String command,String member,long generation,String roleId,String scope,String source,Instant from,Instant to){
        if(!"TENANT_ALL".equals(scope))throw new GovernanceException(INVALID_ARGUMENT);
        return grantFixed(login,p,command,member,generation,roleId,scope,null,source,from,to,null);
    }
    /** 新范围入口只接收固定类型快照，继续复用管理上限、来源唯一与审计事务。 */
    public Grant grantScoped(VerifiedLogin login,Partition p,String command,String member,long generation,String roleId,
                             com.lrj.authz.protocol.ScopeDtos.Rule rule,String source,Instant from,Instant to){
        return grantFixed(login,p,command,member,generation,roleId,"SCOPED",ScopeRules.validated(rule),source,from,to,null);
    }
    private Grant grantFixed(VerifiedLogin login,Partition p,String command,String member,long generation,String roleId,String scope,
                              com.lrj.authz.protocol.ScopeDtos.Rule rule,String source,Instant from,Instant to,String group){
        if(group==null)BootstrapCommand.uuid(member);else BootstrapCommand.uuid(group);BootstrapCommand.uuid(roleId);BootstrapCommand.bounded(source,100);
        if((group==null?generation<1:generation!=0)||from==null||to==null||!to.isAfter(from))throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);
            if(group==null&&manager.context().membershipId().equals(member))throw new GovernanceException(ACCESS_DENIED);
            RoleVersion role=mapper.role(p,roleId);if(role==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(role.capabilitiesJson()));
            String ruleJson=rule==null?null:ScopeRules.encode(rule);
            if(rule!=null){
                var app=catalog.application(p.applicationId());
                var definitions=CatalogManifest.read(catalog.snapshot(p.applicationId(),app.manifestVersion()).manifestJson()).capabilities();
                if(AccessValues.read(role.capabilitiesJson()).stream().anyMatch(cap->definitions.stream().noneMatch(c->c.code().equals(cap)&&c.resourceType().equals(rule.resourceType()))))throw new GovernanceException(SCOPE_UNSUPPORTED);
            }
            String hash=rule==null?AccessValues.hash(p,member,generation,roleId,scope,source,from,to)
                    :AccessValues.hash(p,member,generation,roleId,scope,ruleJson,source,from,to);
            String commandHash=group==null?hash:AccessValues.hash(hash,group);
            String result=command(manager.context(),p,"CREATE_GRANT",command,commandHash,()->{
                if(group==null){if(!mapper.memberActive(p,member,generation))throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);}
                else {
                    var target=safety.group(p,group);
                    if(safety.groupMember(group,manager.context().membershipId(),manager.context().membershipGeneration()))throw new GovernanceException(ACCESS_DENIED);
                    if(!mapper.strict(p)||target==null||!target.active()||target.businessZone()==null)throw new GovernanceException(ACCESS_DENIED);
                }
                if(!to.isAfter(mapper.now())||Duration.between(from,to).compareTo(Duration.ofSeconds(manager.delegation().maxDurationSeconds()))>0)throw new GovernanceException(ACCESS_DENIED);
                if((group==null?mapper.liveCount(p,member,generation):safety.groupCount(p,group))>=100)throw new GovernanceException(INVALID_ARGUMENT);
                Grant g=new Grant(id(),p.tenantId(),p.applicationId(),p.environment(),member,generation,roleId,scope,group==null?"DIRECT":"GROUP",source,from,to,GrantState.PENDING,1,null,group);
                if(mapper.insertGrant(g,manager.context().membershipId())!=1)throw new GovernanceException(BINDING_CONFLICT);
                if(rule!=null)one(mapper.insertScope(g,rule.resourceType(),ruleJson,AccessValues.hash(ruleJson)));
                one(mapper.enqueue(g.id(),1,"UPSERT"));audit(manager,p,"CREATE_GRANT",g.id(),1,command);return g.id();
            });
            return mapper.grant(p,result);
        });
    }
    /** 组授权仍固定角色、范围和来源；目录自动入组本身不生成Grant。 */
    public Grant grantGroup(VerifiedLogin login,Partition p,String command,String group,String roleId,
                            com.lrj.authz.protocol.ScopeDtos.Rule rule,String source,Instant from,Instant to){
        return grantFixed(login,p,command,null,0,roleId,"SCOPED",ScopeRules.validated(rule),source,from,to,group);
    }
    /** 显式确认来源时区，必须与OA进程日期语义一致；每次更改均审计并阻断旧目录水位。 */
    public void configureGroupClock(VerifiedLogin login,Partition p,String command,String source,String zone){
        BootstrapCommand.uuid(source);
        if(zone==null||!ZoneId.getAvailableZoneIds().contains(zone))throw new GovernanceException(INVALID_ARGUMENT);
        tx.executeWithoutResult(status->{var actor=manager(login,p,true);
            command(actor.context(),p,"DIRECTORY_CLOCK",command,AccessValues.hash(p,source,zone),()->{
                one(safety.clock(p,source,zone));audit(actor,p,"DIRECTORY_CLOCK",source,1,command);return source;
            });
        });
    }
    /** 只提供管理分区内的目录组，返回最多100条，下一页使用末项ID。 */
    public List<Group> groups(VerifiedLogin login,Partition p,String after){manager(login,p,false);return safety.groups(p,cursor(after));}
    /** 严格分区预检查与撤销在同事务，避免非严格调用部分提交后才发现无receipt。 */
    public RevocationReceipt strictRevoke(VerifiedLogin login,Partition p,String command,String grantId,long version){
        return tx.execute(status->{
            revocationReceipt(login,p,grantId);
            revoke(login,p,command,grantId,version);
            return revocationReceipt(login,p,grantId);
        });
    }
    /** 管理查询检查当前委派，完成必须包含真实操作回执。 */
    public RevocationReceipt revocationReceipt(VerifiedLogin login,Partition p,String grantId){
        BootstrapCommand.uuid(grantId);manager(login,p,false);
        var result=safety.receipt(p,grantId);if(result==null)throw new GovernanceException(ACCESS_DENIED);return result;
    }
    /** 修复依赖后允许有界重试；正在持租约的worker不能被管理入口强行替换。 */
    public void retryStrict(VerifiedLogin login,Partition p,String command,com.lrj.authz.governance.domain.ProjectionModels.Kind kind){
        if(kind==null)throw new GovernanceException(INVALID_ARGUMENT);
        tx.executeWithoutResult(status->{var actor=manager(login,p,true);
            if(!mapper.strict(p))throw new GovernanceException(ACCESS_DENIED);
            command(actor.context(),p,"RETRY_STRICT",command,AccessValues.hash(p,kind),()->{
                one(safety.retryStream(p,kind));one(safety.retryFence(p,kind));
                String target=kind==com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY?fences.policy(p).id():fences.directory(p.tenantId()).id();
                audit(actor,p,"RETRY_STRICT",target,1,command);return target;
            });
        });
    }
    /** 撤销先去掉SQL资格，再可靠删除图；重复命令返回当前状态，不能恢复授权。 */
    public Grant revoke(VerifiedLogin login,Partition p,String command,String grantId,long version){
        BootstrapCommand.uuid(grantId);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);Grant g=mapper.grant(p,grantId);
            if(g==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(mapper.role(p,g.roleId()).capabilitiesJson()));
            String result=command(manager.context(),p,"REVOKE_GRANT",command,AccessValues.hash(p,grantId,version),()->{
                one(mapper.revoke(p,grantId,version));one(mapper.enqueue(grantId,version+1,"DELETE"));audit(manager,p,"REVOKE_GRANT",grantId,version+1,command);return grantId;
            });return mapper.grant(p,result);
        });
    }
    /** 修复依赖后受控重新领取耗尽的投影；不直接改变Grant或伪造生效回执。 */
    public Grant retryProjection(VerifiedLogin login,Partition p,String command,String grantId,long version){
        BootstrapCommand.uuid(grantId);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);Grant g=mapper.grant(p,grantId);
            if(g==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(mapper.role(p,g.roleId()).capabilitiesJson()));
            String result=command(manager.context(),p,"RETRY_PROJECTION",command,AccessValues.hash(p,grantId,version),()->{
                if(g.version()!=version)throw new GovernanceException(VERSION_CONFLICT);
                one(mapper.retryProjection(p,grantId,version));audit(manager,p,"RETRY_PROJECTION",grantId,version,command);return grantId;
            });return mapper.grant(p,result);
        });
    }
    /** 读取同一管理分区，稳定游标防止无界返回及跨环境泄露。 */
    public State state(VerifiedLogin login,Partition p,String afterRole,String afterGrant){
        manager(login,p,false);String r=cursor(afterRole),g=cursor(afterGrant);
        var roles=mapper.roles(p,r);var grants=mapper.grants(p,g);
        return new State(List.copyOf(roles.subList(0,Math.min(100,roles.size()))),List.copyOf(grants.subList(0,Math.min(100,grants.size()))),
                roles.size()>100?roles.get(99).id():null,grants.size()>100?grants.get(99).id():null);
    }
    private Manager manager(VerifiedLogin login,Partition p,boolean lock){
        AccessValues.partition(p);CurrentContext current=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null);
        if(!Boolean.TRUE.equals(lock?mapper.lockPartition(p):mapper.enabled(p)))throw new GovernanceException(ACCESS_DENIED);
        Delegation d=mapper.delegation(p,current.membershipId(),current.membershipGeneration());
        if(d==null)throw new GovernanceException(ACCESS_DENIED);return new Manager(current,d);
    }
    private void requireCapabilities(Partition p,List<String> caps){
        var app=catalog.application(p.applicationId());if(app==null||app.manifestVersion()==0)throw new GovernanceException(ACCESS_DENIED);
        var snapshot=catalog.snapshot(p.applicationId(),app.manifestVersion());
        var known=CatalogManifest.read(snapshot.manifestJson()).capabilities().stream().map(c->c.code()).toList();
        if(!known.containsAll(caps))throw new GovernanceException(ACCESS_DENIED);
    }
    private void requireCeiling(Delegation d,List<String> caps){if(!AccessValues.read(d.capabilitiesJson()).containsAll(caps))throw new GovernanceException(ACCESS_DENIED);}
    private String command(CurrentContext actor,Partition p,String operation,String command,String hash,Supplier<String> write){
        BootstrapCommand.uuid(command);String operator=actor.membershipId()+":"+actor.membershipGeneration();
        commands.reserveCommand(operator,p.tenantId(),operation,command,hash);
        var receipt=commands.lockCommand(operator,p.tenantId(),operation,command);
        if(receipt==null||!hash.equals(receipt.payloadHash()))throw new GovernanceException(COMMAND_CONFLICT);
        if(receipt.completed())return receipt.resultRef();
        String result=write.get();one(commands.completeCommand(operator,p.tenantId(),operation,command,result));return result;
    }
    private void audit(Manager manager,Partition p,String operation,String target,long version,String command){one(commands.appendAudit(id(),manager.context().membershipId(),p.tenantId(),operation,target,version,command));}
    private String cursor(String cursor){if(cursor==null||cursor.isEmpty())return "";BootstrapCommand.uuid(cursor);return cursor;}
    private static String id(){return UUID.randomUUID().toString();}
    private static void one(int count){if(count!=1)throw new GovernanceException(VERSION_CONFLICT);}
    private record Manager(CurrentContext context,Delegation delegation){}
}
