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

    /** 与既有治理聚合共用事务管理器，不跨库写OA状态。 */
    public AccessRequests(RequestMapper requests, AccessMapper access, CatalogMapper catalog,
                          IdentityMapper identities, IdentityGovernance identity, TransactionTemplate tx) {
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
        return requests.policies(p,actor.membershipId(),after).stream().filter(policy -> {
            try { usablePolicy(p,policy.id(),actor.membershipId()); return true; }
            catch(GovernanceException unavailable) { return false; }
        }).toList();
    }

    private Policy usablePolicy(Partition p, String id, String beneficiary) {
        Policy policy=requests.policy(p,id);
        if(policy==null || !policy.enabled() || policy.approverMembershipId().equals(beneficiary)) throw denied();
        RoleVersion role=access.role(p,policy.roleId());
        if(role==null) throw denied();
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
        if(app==null || app.manifestVersion()<1) throw denied();
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
