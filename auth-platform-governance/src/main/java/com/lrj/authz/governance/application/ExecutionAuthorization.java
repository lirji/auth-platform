package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.persistence.ExecutionMapper;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeDtos.Alternative;
import com.lrj.authz.protocol.ScopeAccessDtos.ResourceDecision;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 将经用户认证的授权路径绑定到后台任务；引用不产生独立于Grant的权利。 */
public final class ExecutionAuthorization {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SECONDS = 37 * 86400L + 60;
    private final ExecutionMapper mapper;
    private final ReliableAuthorization access;
    private final TransactionTemplate tx;
    public ExecutionAuthorization(ExecutionMapper mapper, ReliableAuthorization access, TransactionTemplate tx) {
        this.mapper=mapper; this.access=access; this.tx=tx;
    }
    /** 签发先做严格实时判权；数据库事务仅保存引用，不包围远程图调用。 */
    public Reference issue(AccessContext context, Issue input) {
        if(input==null||input.check()==null)throw error(INVALID_ARGUMENT);
        var check=input.check(); BootstrapCommand.uuid(check.requestId());
        if(!context.tenantId().equals(check.tenantId())||!(context.applicationId()+".catalog.operate").equals(check.capability())||!com.lrj.authz.protocol.ScopeDtos.STORE_RESOURCE_TYPE.equals(check.resourceType())
            ||!com.lrj.authz.governance.domain.IdentityModels.PrincipalKind.HUMAN.code().equals(context.actorType())
            ||(check.expectedMembershipGeneration()!=null&&check.expectedMembershipGeneration()!=context.membershipGeneration()))throw error(ACCESS_DENIED);
        Instant until;
        try { until=Instant.parse(input.expiresAt()); } catch(RuntimeException e) { throw error(INVALID_ARGUMENT); }
        Instant now=mapper.now();
        if(until.getNano()%1000!=0||!until.isAfter(now)||until.isAfter(now.plusSeconds(MAX_SECONDS)))throw error(INVALID_ARGUMENT);
        var result=access.evaluate(context,check.capability(),check.resourceType());
        if(result.alternatives().isEmpty())throw error(ACCESS_DENIED);
        String fingerprint=AccessValues.hash(context.principalId(),context.membershipId(),context.membershipGeneration(),context.membershipVersion(),context.principalVersion(),
            context.tenantId(),context.applicationId(),context.environment(),context.callerServiceId(),check.capability(),check.resourceType(),until);
        Instant expiry=until;
        return tx.execute(status->{
            mapper.lock(context.membershipId());
            var old=mapper.request(context.callerServiceId(),check.requestId());
            if(old!=null) {
                if(!old.fingerprint().equals(fingerprint))throw error(INVALID_ARGUMENT);
                return reference(old);
            }
            if(mapper.active(context.membershipId())>=10000)throw error(DEPENDENCY_UNAVAILABLE);
            var row=new ExecutionMapper.Row(UUID.randomUUID().toString(),context.callerServiceId(),context.applicationId(),context.environment(),context.membershipId(),
                check.requestId(),fingerprint,write(context),check.capability(),check.resourceType(),write(new Paths(result.stamp().directoryId(),result.stamp().directoryEpoch(),result.alternatives())),expiry);
            if(mapper.insert(row)!=1)throw error(DEPENDENCY_UNAVAILABLE);
            return reference(row);
        });
    }
    /** 当前路径须与签发路径完全相同；撤权后重授的不同Grant不能复活排队任务。 */
    public ResourceDecision check(CallerService caller, Check input) {
        if(input==null||input.resource()==null||input.resource().check()==null)throw error(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.executionId());
        var row=mapper.find(input.executionId()); var request=input.resource().check(); var facts=input.resource().facts();
        BootstrapCommand.uuid(request.requestId());
        if(row==null||!caller.callerServiceId().equals(row.caller())||!caller.applicationId().equals(row.application())||!caller.environment().equals(row.environment()))throw error(ACCESS_DENIED);
        var context=read(row.contextJson(),AccessContext.class);
        if(!row.expiresAt().isAfter(mapper.now())||!context.tenantId().equals(request.tenantId())||!row.capability().equals(request.capability())||!row.resourceType().equals(request.resourceType())
            ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()!=context.membershipGeneration()))throw error(ACCESS_DENIED);
        if(facts==null||!context.tenantId().equals(facts.tenantId())||!com.lrj.authz.protocol.ScopeDtos.STORE_RESOURCE_TYPE.equals(facts.resourceType())||facts.resourceId()==null||!facts.resourceId().matches("[A-Za-z0-9_:/.-]{1,100}")
            ||!facts.resourceId().equals(facts.storeId())||facts.resourceVersion()<0||facts.ownerPrincipalId()!=null||facts.departmentId()!=null||facts.supplierId()!=null
            ||facts.departmentAncestors()==null||!facts.departmentAncestors().isEmpty())throw error(INVALID_ARGUMENT);
        var current=access.evaluate(context,row.capability(),row.resourceType());
        var original=read(row.pathsJson(),Paths.class);
        // 目录资格可能先移除再恢复同一个组Grant；绑定目录版本，防止旧后台任务借此复活。
        if(!original.directoryId().equals(current.stamp().directoryId())||original.directoryEpoch()!=current.stamp().directoryEpoch())throw error(ACCESS_DENIED);
        var eligible=current.alternatives().stream().filter(original.alternatives()::contains).toList();
        boolean allowed=ScopeRules.matches(context.tenantId(),context.principalId(),eligible,facts);
        Instant until=current.validUntil().isBefore(row.expiresAt())?current.validUntil():row.expiresAt();
        return new ResourceDecision("1",request.requestId(),row.capability(),row.resourceType(),facts.resourceId(),facts.resourceVersion(),allowed?"ALLOW":"DENY",current.decisionId(),context,until.toString());
    }
    private record Paths(String directoryId,long directoryEpoch,List<Alternative> alternatives) {}
    private static Reference reference(ExecutionMapper.Row row) { return new Reference("1",row.requestId(),row.id(),read(row.contextJson(),AccessContext.class),row.capability(),row.resourceType(),row.expiresAt().toString()); }
    private static String write(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw error(DEPENDENCY_UNAVAILABLE);} }
    private static <T>T read(String value,Class<T> type) { try{return JSON.readValue(value,type);}catch(Exception e){throw error(AUTHZ_STATE_NOT_READY);} }
    private static GovernanceException error(GovernanceException.Code code) { return new GovernanceException(code); }
}
