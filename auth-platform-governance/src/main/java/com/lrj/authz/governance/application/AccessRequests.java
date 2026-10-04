package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.IdentityModels.CurrentContext;
import com.lrj.authz.governance.domain.RequestModels.*;
import com.lrj.authz.governance.domain.RequestModels.State;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.ScopeDtos.Rule;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 自助申请用例；固定快照和启动意图同事务，审批前不产生业务授权。 */
public final class AccessRequests {
    private final RequestMapper requests;
    private final AccessMapper access;
    private final CatalogMapper catalog;
    private final IdentityMapper identities;
    private final IdentityGovernance identity;
    private final TransactionTemplate tx;
    private final CapabilityLifecycleMapper lifecycles;

    /** 与既有治理聚合共用事务管理器，不跨库写OA状态。 */
    public AccessRequests(RequestMapper requests, AccessMapper access, CatalogMapper catalog,
                          IdentityMapper identities, IdentityGovernance identity, TransactionTemplate tx, CapabilityLifecycleMapper lifecycles) {
        this.lifecycles=lifecycles;
        this.requests=requests; this.access=access; this.catalog=catalog;
        this.identities=identities; this.identity=identity; this.tx=tx;
    }

    /** 策略选择固定角色和范围，登记者与审批者均必须覆盖当前管理上限。 */
    public Policy registerPolicy(VerifiedLogin login, Partition p, String command, String roleId, Rule rule,
                                 long duration, String approver, long approverGeneration, long version) {
        AccessValues.partition(p); BootstrapCommand.uuid(roleId); BootstrapCommand.uuid(approver);
        String scope=ScopeRules.encode(rule);
        if(duration<1 || duration>31536000 || approverGeneration<1 || version<1) throw invalid();
        return tx.execute(status -> {
            CurrentContext actor=context(login,p,true);
            RoleVersion role=access.role(p,roleId);
            if(role==null) throw denied();
            ceiling(p,actor.membershipId(),actor.membershipGeneration(),role,duration);
            Delegation delegation=ceiling(p,approver,approverGeneration,role,duration);
            requireResource(p,role,rule.resourceType());
            String hash=AccessValues.hash(p,roleId,scope,duration,approver,approverGeneration,delegationHash(delegation),version);
            String id=command(actor,p,"REGISTER_REQUEST_POLICY",command,hash,()-> {
                CapabilityUsage.requireCreatable(lifecycles,p.applicationId(),AccessValues.read(role.capabilitiesJson()));
                Policy policy=new Policy(id(),p.tenantId(),p.applicationId(),p.environment(),roleId,scope,duration,
                        approver,approverGeneration,delegationHash(delegation),version,hash,true);
                one(requests.insertPolicy(policy,actor.membershipId()));
                audit(actor.membershipId(),p,"REGISTER_REQUEST_POLICY",policy.id(),1,command);
                return policy.id();
            });
            return requests.policy(p,id);
        });
    }

    /** 重放返回原快照；重试不会重新冻结日期或延长授权。 */
    public Request submit(VerifiedLogin login, Partition p, String command, String policyId,
                          Instant from, Instant to, String reason) {
        AccessValues.partition(p); BootstrapCommand.uuid(policyId); BootstrapCommand.bounded(reason,1000);
        if(from==null || to==null || !to.isAfter(from)) throw invalid();
        Instant start=from.truncatedTo(ChronoUnit.MICROS), end=to.truncatedTo(ChronoUnit.MICROS);
        if(!end.isAfter(start)) throw invalid();
        return tx.execute(status -> {
            CurrentContext actor=context(login,p,true);
            String hash=AccessValues.hash(p,actor.membershipId(),actor.membershipGeneration(),policyId,start,end,reason);
            String result=command(actor,p,"SUBMIT_ACCESS_REQUEST",command,hash,()-> {
                Policy policy=usablePolicy(p,policyId,actor.membershipId());
                RoleVersion role=access.role(p,policy.roleId());
                CapabilityUsage.requireCreatable(lifecycles,p.applicationId(),AccessValues.read(role.capabilitiesJson()));
                long seconds=policy.maxDurationSeconds();
                if(!end.isAfter(access.now()) || Duration.between(start,end).compareTo(Duration.ofSeconds(seconds))>0) throw denied();
                var member=identities.membership(actor.membershipId());
                if(member.validTo()!=null && end.isAfter(member.validTo())) throw denied();
                if(requests.pendingCount(p,actor.membershipId(),actor.membershipGeneration())>=20) throw invalid();
                String snapshot=AccessValues.hash(hash,actor.principalId(),role.id(),role.capabilitiesJson(),
                        policy.scopeJson(),policy.contentHash(),member.validTo(),1);
                Request request=new Request(id(),p.tenantId(),p.applicationId(),p.environment(),actor.principalId(),
                        actor.membershipId(),actor.membershipGeneration(),policy.id(),role.id(),role.capabilitiesJson(),
                        policy.scopeJson(),policy.contentHash(),member.validTo(),start,end,reason,1,snapshot,
                        State.SUBMITTED,1,null,null,command);
                one(requests.insertRequest(request)); one(requests.enqueueStart(request.id()));
                audit(actor.membershipId(),p,"SUBMIT_ACCESS_REQUEST",request.id(),1,command);
                return request.id();
            });
            return requests.owned(p,result,actor.membershipId(),actor.membershipGeneration());
        });
    }

    /** 本人新申请升级固定原OA授权；幂等重放不会重新计算剩余期限。 */
    public Request submitMigration(VerifiedLogin login,Partition p,String command,String policyId,
                                   String oldGrantId,long expectedVersion,String reason){
        AccessValues.partition(p);BootstrapCommand.uuid(policyId);BootstrapCommand.uuid(oldGrantId);
        BootstrapCommand.bounded(reason,1000);if(expectedVersion<1)throw invalid();
        return tx.execute(status->{
            var actor=context(login,p,true);
            String payload=AccessValues.hash(p,actor.membershipId(),actor.membershipGeneration(),policyId,oldGrantId,expectedVersion,reason);
            String result=command(actor,p,"SUBMIT_ROLE_MIGRATION_REQUEST",command,payload,()->{
                var old=access.grant(p,oldGrantId);var original=requests.byGrant(p,oldGrantId);
                if(old==null||old.version()!=expectedVersion||old.state()!=GrantState.ACTIVE
                        ||!com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(old.sourceType())
                        ||!actor.membershipId().equals(old.membershipId())||actor.membershipGeneration()!=old.generation()
                        ||original==null||original.state()!=State.APPROVED||original.withdrawn()
                        ||!old.sourceId().equals(original.id()+":single:"+original.requestVersion()))throw denied();
                var policy=usablePolicy(p,policyId,actor.membershipId());var role=access.role(p,policy.roleId());
                var previous=access.role(p,old.roleId());String rule=access.scope(p,oldGrantId);
                if(!"SCOPED".equals(old.scope())||rule==null||!rule.equals(policy.scopeJson())
                        ||!previous.roleCode().equals(role.roleCode())||role.version()<=previous.version()
                        ||!AccessValues.read(previous.capabilitiesJson()).containsAll(AccessValues.read(role.capabilitiesJson())))throw denied();
                var now=access.now();if(now.isBefore(old.validFrom())||!now.isBefore(old.validTo()))throw denied();
                var member=identities.membership(actor.membershipId());
                if(Duration.between(now,old.validTo()).compareTo(Duration.ofSeconds(policy.maxDurationSeconds()))>0
                        ||member.validTo()!=null&&old.validTo().isAfter(member.validTo()))throw denied();
                if(requests.pendingCount(p,actor.membershipId(),actor.membershipGeneration())>=20
                        ||requests.migrationOpen(p,oldGrantId).size()>=20)throw invalid();
                if(requests.migrationOpen(p,oldGrantId).stream().anyMatch(r->r.roleId().equals(role.id())))throw new GovernanceException(BINDING_CONFLICT);
                CapabilityUsage.requireCreatable(lifecycles,p.applicationId(),AccessValues.read(role.capabilitiesJson()));
                String snapshot=AccessValues.hash(payload,actor.principalId(),role.id(),role.capabilitiesJson(),rule,
                        policy.contentHash(),member.validTo(),now,old.validTo(),1);
                Request request=new Request(id(),p.tenantId(),p.applicationId(),p.environment(),actor.principalId(),
                        actor.membershipId(),actor.membershipGeneration(),policy.id(),role.id(),role.capabilitiesJson(),rule,
                        policy.contentHash(),member.validTo(),now,old.validTo(),reason,1,snapshot,State.SUBMITTED,1,
                        null,null,command,oldGrantId,expectedVersion,false);
                one(requests.insertRequest(request));one(requests.enqueueStart(request.id()));
                audit(actor.membershipId(),p,"SUBMIT_ROLE_MIGRATION_REQUEST",request.id(),1,command);return request.id();
            });
            return requests.owned(p,result,actor.membershipId(),actor.membershipGeneration());
        });
    }

    /** 新审批必须绑定固定旧来源，当前策略／成员失效不会被旧批准掩盖。 */
    Request migrationApproval(Partition p,com.lrj.authz.governance.domain.RoleMigrationModels.Snapshot old,String role){
        var approved=requests.migrationApproved(p,old.id(),role);
        if(approved==null)return null;
        try{
            validateMigration(p,approved,old.grant(),approved.grantId()!=null);
            return approved;
        }catch(GovernanceException unavailable){return null;}
    }

    /** 仅任务在真实旧撤权确认后调用，申请绑定和可靠投影与检查点同事务。 */
    Grant grantMigration(Partition p,com.lrj.authz.governance.domain.RoleMigrationTaskModels.Entry entry,Instant from){
        var r=requests.request(p,entry.replacementRequestId());var old=access.grant(p,entry.oldGrantId());
        if(r==null||r.state()!=State.APPROVED||r.withdrawn()||r.grantId()!=null)throw denied();
        validateMigration(p,r,old,false);
        if(!Objects.equals(r.migrationOldVersion(),entry.oldVersion())||!r.id().equals(entry.replacementRequestId())
                ||!r.roleId().equals(entry.newRoleId())||!r.scopeJson().equals(entry.ruleJson())
                ||!r.validTo().equals(entry.validTo())||!from.isBefore(r.validTo()))throw denied();
        var policy=usablePolicy(p,r.policyId(),r.membershipId());
        Grant g=new Grant(entry.plannedGrantId(),p.tenantId(),p.applicationId(),p.environment(),r.membershipId(),r.generation(),
                r.roleId(),"SCOPED",com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE,entry.newSourceId(),
                from,r.validTo(),GrantState.PENDING,1,null);
        one(access.insertGrant(g,policy.approverMembershipId()));
        one(access.insertScope(g,ScopeRules.decode(r.scopeJson()).resourceType(),r.scopeJson(),AccessValues.hash(r.scopeJson())));
        one(access.enqueue(g.id(),1,"UPSERT"));one(requests.bindMigrationGrant(p,r.id(),r.stateVersion(),g.id()));return g;
    }

    private void validateMigration(Partition p,Request r,Grant old,boolean alreadyGranted){
        var original=old==null?null:requests.byGrant(p,old.id());
        if(old==null||r.migrationOldGrantId()==null||!r.migrationOldGrantId().equals(old.id())||r.migrationOldVersion()==null
                ||!(old.state()==GrantState.ACTIVE&&old.version()==r.migrationOldVersion()
                     ||old.state()==GrantState.REVOKED&&old.version()==r.migrationOldVersion()+1)
                ||!com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(old.sourceType())
                ||!Objects.equals(old.membershipId(),r.membershipId())||old.generation()!=r.generation()
                ||original==null||original.state()!=State.APPROVED||!alreadyGranted&&original.withdrawn()
                ||!old.sourceId().equals(original.id()+":single:"+original.requestVersion())
                ||!r.validTo().equals(old.validTo())||!r.scopeJson().equals(access.scope(p,old.id()))
                ||!access.memberActive(p,r.membershipId(),r.generation())||!r.validTo().isAfter(access.now()))throw denied();
        var policy=usablePolicy(p,r.policyId(),r.membershipId());var next=access.role(p,r.roleId());var previous=access.role(p,old.roleId());
        if(!policy.roleId().equals(r.roleId())||!policy.contentHash().equals(r.policyHash())||!policy.scopeJson().equals(r.scopeJson())
                ||!next.roleCode().equals(previous.roleCode())||next.version()<=previous.version()
                ||!AccessValues.read(previous.capabilitiesJson()).containsAll(AccessValues.read(next.capabilitiesJson())))throw denied();
        var member=identities.membership(r.membershipId());
        if(member.validTo()!=null&&r.validTo().isAfter(member.validTo())||access.liveCount(p,r.membershipId(),r.generation())>=100)throw denied();
    }

    /** 本人查询没有管理人员目录、他人申请或跨代成员回退路径。 */
    public Request owned(VerifiedLogin login, Partition p, String id) {
        BootstrapCommand.uuid(id); CurrentContext actor=context(login,p,false);
        Request result=requests.owned(p,id,actor.membershipId(),actor.membershipGeneration());
        if(result==null) throw denied();
        return result;
    }

    /** 可申请目录只返回当前可用策略；调用端使用末项ID继续翻页。 */
    public List<Policy> policies(VerifiedLogin login, Partition p, String after) {
        CurrentContext actor=context(login,p,false);
        if(after==null) after=""; else if(!after.isEmpty()) BootstrapCommand.uuid(after);
        return policyPage(login,p,after).items();
    }

    /** 策略过滤前保存扫描游标，避免整页失效策略使后续有效项不可达。 */
    public com.lrj.authz.protocol.RequestDtos.Page<Policy> policyPage(VerifiedLogin login,Partition p,String after) {
        var actor=context(login,p,false);var page=requests.policies(p,actor.membershipId(),cursor(after));
        var items=page.stream().filter(policy -> {
            try { usablePolicy(p,policy.id(),actor.membershipId());return true; }
            catch(GovernanceException unavailable){return false;}
        }).toList();
        return new com.lrj.authz.protocol.RequestDtos.Page<>(items,page.size()==100?page.getLast().id():null);
    }

    /** 本人升级目录过滤真实同编码新版与原范围；空页仍保留扫描游标。 */
    public com.lrj.authz.protocol.RequestDtos.Page<Policy> migrationPolicyPage(VerifiedLogin login,Partition p,String requestId,String after){
        var original=owned(login,p,requestId);var old=original.grantId()==null?null:access.grant(p,original.grantId());
        if(original.state()!=State.APPROVED||original.withdrawn()||old==null||old.state()!=GrantState.ACTIVE
                ||!com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(old.sourceType())
                ||!old.sourceId().equals(original.id()+":single:"+original.requestVersion())
                ||!access.memberActive(p,old.membershipId(),old.generation())||!old.validTo().isAfter(access.now()))throw denied();
        var previous=access.role(p,old.roleId());var rule=access.scope(p,old.id());var page=policyPage(login,p,after);
        var now=access.now();var items=page.items().stream().filter(policy->{
            var next=access.role(p,policy.roleId());
            return previous.roleCode().equals(next.roleCode())&&next.version()>previous.version()
                    &&AccessValues.read(previous.capabilitiesJson()).containsAll(AccessValues.read(next.capabilitiesJson()))
                    &&Objects.equals(rule,policy.scopeJson())
                    &&Duration.between(now,old.validTo()).compareTo(Duration.ofSeconds(policy.maxDurationSeconds()))<=0;
        }).toList();
        return new com.lrj.authz.protocol.RequestDtos.Page<>(items,page.nextCursor());
    }

    /** 同分区固定角色的显示字段，调用者必须先通过策略目录或管理范围校验。 */
    public com.lrj.authz.protocol.RequestDtos.PolicyView policyView(Policy p) {
        var role=access.role(new Partition(p.tenantId(),p.applicationId(),p.environment()),p.roleId());
        if(role==null) throw denied();
        return new com.lrj.authz.protocol.RequestDtos.PolicyView(p.id(),p.roleId(),ScopeRules.decode(p.scopeJson()),p.maxDurationSeconds(),p.policyVersion(),role.roleCode(),role.version(),AccessValues.read(role.capabilitiesJson()));
    }

    /** 本人申请分页，禁止调用方传入受益成员身份。 */
    public List<Request> mine(VerifiedLogin login,Partition p,String after) {
        var actor=context(login,p,false);return requests.mine(p,actor.membershipId(),actor.membershipGeneration(),cursor(after));
    }

    /** 站内通知沿用本人当前代际权限，不提供通用员工检索入口。 */
    public List<com.lrj.authz.protocol.RequestDtos.Notice> notices(VerifiedLogin login,Partition p,String after) {
        var actor=context(login,p,false);return requests.notices(p,actor.membershipId(),actor.membershipGeneration(),cursor(after));
    }

    private String cursor(String after) {
        if(after==null || after.isEmpty())return "";BootstrapCommand.uuid(after);return after;
    }

    /** 仅在本人读取之后返回执行视图；它不替代实时业务授权检查。 */
    public com.lrj.authz.protocol.RequestDtos.Execution execution(VerifiedLogin login,Partition p,String id) {
        owned(login,p,id);return requests.execution(p,id);
    }

    /** 本人取消按当前结果收敛：批准赢得竞态时回收本来源，绝不留下竞态授权。 */
    public Request cancel(VerifiedLogin login,Partition p,String command,String requestId,long expectedVersion) {
        BootstrapCommand.uuid(requestId);if(expectedVersion<1)throw invalid();
        return tx.execute(status -> {
            CurrentContext actor=context(login,p,true);
            String result=command(actor,p,"CANCEL_ACCESS_REQUEST",command,AccessValues.hash(p,requestId,expectedVersion),()-> {
                var r=requests.owned(p,requestId,actor.membershipId(),actor.membershipGeneration());
                if(r==null)throw denied();
                // 内容不可变，期间只有状态前进；旧视图取消仍必须回收已批准的同一申请。
                if(expectedVersion>r.stateVersion())throw new GovernanceException(VERSION_CONFLICT);
                settleRequest(p,r,actor.membershipId(),command,"CANCEL_ACCESS_REQUEST");return r.id();
            });
            return requests.request(p,result);
        });
    }

    /** 数据库时间驱动的有界清理；分区短事务串行化领取，无远程调用或长时间租约。 */
    public int settle(Partition p) {
        AccessValues.partition(p);
        return tx.execute(status -> {
            if(access.lockPartition(p)==null)return 0;
            int count=0;
            for(String candidate:requests.expired(p)) {
                var r=requests.request(p,candidate);
                if(r.validTo().isAfter(access.now()) && access.memberActive(p,r.membershipId(),r.generation()))continue;
                settleRequest(p,r,"REQUEST_LIFECYCLE",id(),"SETTLE_ACCESS_REQUEST");count++;
            }
            return count;
        });
    }

    private void settleRequest(Partition p,Request r,String actor,String command,String operation) {
        if(r.withdrawn()||r.state()==State.CANCELLED || r.state()==State.REJECTED)return;
        // 旧Grant已经撤销也要保存撤回事实，否则尚未授新的替代批准仍可能被推进。
        if(r.grantId()!=null)for(var replacement:requests.migrationOpen(p,r.grantId())){
            one(requests.cancel(p,replacement.id(),replacement.stateVersion(),State.CANCELLED));requests.stopStart(replacement.id());
            audit(actor,p,"STOP_LINKED_MIGRATION_REQUEST",replacement.id(),replacement.stateVersion()+1,command);
        }
        if(r.grantId()!=null) {
            var g=access.grant(p,r.grantId());
            if(g==null || !com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(g.sourceType()) || !(r.id()+":single:"+r.requestVersion()).equals(g.sourceId()))
                throw new GovernanceException(BINDING_CONFLICT);
            if(g.state()!=GrantState.REVOKED){one(access.revoke(p,g.id(),g.version()));one(access.enqueue(g.id(),g.version()+1,"DELETE"));}
            // APPROVED保留审批事实；用户由执行视图区分正在回收和真实回收回执。
            one(requests.cancel(p,r.id(),r.stateVersion(),State.APPROVED));
        } else one(requests.cancel(p,r.id(),r.stateVersion(),State.CANCELLED));
        requests.stopStart(r.id());
        audit(actor,p,operation,r.id(),r.stateVersion()+1,command);
    }

    /** Inbox消费者持有分区锁后调用；无HTTP入口，不能由普通用户直接提交批准。 */
    com.lrj.authz.protocol.ApprovalDtos.Receipt decide(Partition p,com.lrj.authz.protocol.ApprovalDtos.Decision event) {
        var r=requests.request(p,event.requestId());
        var status=com.lrj.authz.protocol.ApprovalDtos.Status.IGNORED;
        if(r==null || r.withdrawn() || r.state()==State.CANCELLED || r.state()==State.APPROVED || r.state()==State.REJECTED)
            return new com.lrj.authz.protocol.ApprovalDtos.Receipt(event.eventId(),status.code(),"REQUEST_TERMINAL");
        if(r.state()!=State.IN_REVIEW || !event.approvalInstanceId().equals(r.approvalInstanceId()))
            return new com.lrj.authz.protocol.ApprovalDtos.Receipt(event.eventId(),com.lrj.authz.protocol.ApprovalDtos.Status.REJECTED.code(),"INSTANCE_MISMATCH");
        boolean approved=com.lrj.authz.protocol.ApprovalDtos.Outcome.APPROVED.code().equals(event.outcome());
        String result=approved?"GRANT_PENDING_APPLY":"OA_REJECTED";
        Policy policy=requests.policy(p,r.policyId());
        try {
            if(!Boolean.TRUE.equals(access.enabled(p)) || !access.strict(p) || event.requestVersion()!=r.requestVersion()
                    || !event.snapshotHash().equals(r.snapshotHash()) || event.aggregateVersion()!=1
                    || policy==null || !event.policyId().equals(policy.id()) || event.policyVersion()!=policy.policyVersion()
                    || !event.approverMembershipId().equals(policy.approverMembershipId()) || event.approverGeneration()!=policy.approverGeneration()) throw denied();
            usablePolicy(p,r.policyId(),r.membershipId());
            if(r.migrationOldGrantId()!=null){
                var old=access.grant(p,r.migrationOldGrantId());
                validateMigration(p,r,old,false);
                if(old.state()!=GrantState.ACTIVE)throw denied();
            }
            if(approved)CapabilityUsage.requireCreatable(lifecycles,p.applicationId(),AccessValues.read(r.capabilitiesJson()));
            var member=identities.membership(r.membershipId());
            if(!access.memberActive(p,r.membershipId(),r.generation()) || !r.validTo().isAfter(access.now())
                    || member.validTo()!=null && r.validTo().isAfter(member.validTo())
                    || access.liveCount(p,r.membershipId(),r.generation())>=100) throw denied();
        } catch(GovernanceException rejected) {
            approved=false;result="APPROVAL_REVALIDATION_FAILED";
        }
        String grant=null;
        if(approved&&r.migrationOldGrantId()!=null)result="MIGRATION_APPROVED_PENDING_SWITCH";
        if(approved&&r.migrationOldGrantId()==null) {
            Grant g=new Grant(id(),p.tenantId(),p.applicationId(),p.environment(),r.membershipId(),r.generation(),r.roleId(),"SCOPED",
                    com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE,r.id()+":single:"+r.requestVersion(),r.validFrom(),r.validTo(),GrantState.PENDING,1,null);
            one(access.insertGrant(g,policy.approverMembershipId()));
            var rule=ScopeRules.decode(r.scopeJson());one(access.insertScope(g,rule.resourceType(),r.scopeJson(),AccessValues.hash(r.scopeJson())));
            one(access.enqueue(g.id(),1,"UPSERT"));grant=g.id();
        }
        one(requests.decide(p,r.id(),r.stateVersion(),approved?State.APPROVED:State.REJECTED,grant));
        audit(event.approverMembershipId(),p,approved?"APPROVE_ACCESS_REQUEST":"REJECT_ACCESS_REQUEST",r.id(),r.stateVersion()+1,event.eventId());
        return new com.lrj.authz.protocol.ApprovalDtos.Receipt(event.eventId(),com.lrj.authz.protocol.ApprovalDtos.Status.APPLIED.code(),result);
    }

    private Policy usablePolicy(Partition p, String id, String beneficiary) {
        Policy policy=requests.policy(p,id);
        if(policy==null || !policy.enabled() || policy.approverMembershipId().equals(beneficiary)) throw denied();
        RoleVersion role=access.role(p,policy.roleId());
        if(role==null) throw denied();
        if(lifecycles.deprecated(p.applicationId(),AccessValues.read(role.capabilitiesJson())))throw new GovernanceException(CAPABILITY_DEPRECATED);
        Delegation delegation=ceiling(p,policy.approverMembershipId(),policy.approverGeneration(),role,policy.maxDurationSeconds());
        if(!policy.delegationHash().equals(delegationHash(delegation))) throw denied();
        requireResource(p,role,ScopeRules.decode(policy.scopeJson()).resourceType());
        return policy;
    }

    private Delegation ceiling(Partition p, String member, long generation, RoleVersion role, long duration) {
        Delegation d=access.delegation(p,member,generation);
        if(d==null || !access.memberActive(p,member,generation) || d.maxDurationSeconds()<duration
                || !AccessValues.read(d.capabilitiesJson()).containsAll(AccessValues.read(role.capabilitiesJson()))) throw denied();
        return d;
    }

    private void requireResource(Partition p, RoleVersion role, String resource) {
        var app=catalog.application(p.applicationId());
        if(app==null || app.manifestVersion()<1 || requests.disabledCapabilities(p,AccessValues.read(role.capabilitiesJson()))) throw denied();
        var definitions=CatalogManifest.read(catalog.snapshot(p.applicationId(),app.manifestVersion()).manifestJson()).capabilities();
        if(AccessValues.read(role.capabilitiesJson()).stream().anyMatch(cap -> definitions.stream()
                .noneMatch(c -> c.code().equals(cap) && c.resourceType().equals(resource)))) throw denied();
    }

    private CurrentContext context(VerifiedLogin login, Partition p, boolean lock) {
        AccessValues.partition(p);
        CurrentContext actor=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null);
        if(!Boolean.TRUE.equals(lock?access.lockPartition(p):access.enabled(p)) || !access.strict(p)) throw denied();
        return actor;
    }

    private String command(CurrentContext actor, Partition p, String operation, String command, String hash, Supplier<String> work) {
        BootstrapCommand.uuid(command);
        String operator=actor.membershipId()+":"+actor.membershipGeneration();
        identities.reserveCommand(operator,p.tenantId(),operation,command,hash);
        var previous=identities.lockCommand(operator,p.tenantId(),operation,command);
        if(previous==null || !hash.equals(previous.payloadHash())) throw new GovernanceException(COMMAND_CONFLICT);
        if(previous.completed()) return previous.resultRef();
        String result=work.get(); one(identities.completeCommand(operator,p.tenantId(),operation,command,result));
        return result;
    }

    private void audit(String actor, Partition p, String operation, String target, long version, String command) {
        one(identities.appendAudit(id(),actor,p.tenantId(),operation,target,version,command));
    }
    private static String delegationHash(Delegation d) { return AccessValues.hash(d.membershipId(),d.generation(),d.capabilitiesJson(),d.maxDurationSeconds()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static void one(int count) { if(count!=1) throw new GovernanceException(VERSION_CONFLICT); }
    private static GovernanceException invalid() { return new GovernanceException(INVALID_ARGUMENT); }
    private static GovernanceException denied() { return new GovernanceException(ACCESS_DENIED); }
}
