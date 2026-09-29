package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.PermissionMapper;
import com.lrj.authz.protocol.PermissionDtos.*;
import com.lrj.authz.protocol.RequestDtos.Page;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 本人解释与管理诊断分开；诊断配置不自动产生业务读写权限。 */
public final class PortalPermissions {
    private static final String EXPLAIN="READ_GRANT_EXPLANATION", AUDIT="READ_ACCESS_AUDIT";
    private enum Outcome {
        ALLOWED("ALLOWED"), DENIED("DENIED");
        private final String code; Outcome(String code){this.code=code;}
        String code(){return code;}
    }
    private final IdentityGovernance identity;
    private final AccessManagement access;
    private final PermissionMapper mapper;
    private final List<PortalDiagnosticAuthority> authorities;
    private final TransactionTemplate auditTransaction;
    /** 审计独立提交，之后的拒绝异常不能回滚访问证据。 */
    public PortalPermissions(IdentityGovernance identity,AccessManagement access,PermissionMapper mapper,
                             List<PortalDiagnosticAuthority> authorities,TransactionTemplate transactions) {
        this.identity=identity;this.access=access;this.mapper=mapper;this.authorities=List.copyOf(authorities);
        auditTransaction=new TransactionTemplate(transactions.getTransactionManager());
        auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);auditTransaction.setTimeout(5);
    }
    /** 本人列表只绑定当前代际，历史ACTIVE和菜单不作为当前资源ALLOW。 */
    public Page<Explanation> mine(VerifiedLogin login,Partition p,String after) {
        AccessValues.partition(p);var actor=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null);
        var rows=mapper.mine(p,actor.membershipId(),actor.membershipGeneration(),cursor(after));
        var latest=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),actor.membershipGeneration());
        if(!latest.membershipId().equals(actor.membershipId()) || latest.membershipVersion()!=actor.membershipVersion() || latest.principalVersion()!=actor.principalVersion()) throw new GovernanceException(VERSION_CONFLICT);
        return new Page<>(rows.subList(0,Math.min(100,rows.size())).stream().map(PortalPermissions::view).toList(),rows.size()>100?rows.get(99).grantId():null);
    }
    /** UUID不是诊断凭据，异租户或异应用ID在查询内拒绝，并保留访问审计。 */
    public Explanation explain(VerifiedLogin login,Partition p,String id) {
        BootstrapCommand.uuid(id);String actor=authorize(login,p,EXPLAIN,id);
        var row=mapper.explanation(p,id);
        if(row==null) {record(actor,p,EXPLAIN,id,Outcome.DENIED);throw new GovernanceException(ACCESS_DENIED);}
        var result=view(row);record(actor,p,EXPLAIN,id,Outcome.ALLOWED);return result;
    }
    /** 管理审计仍限显式诊断范围，不能借管理身份读取全企业事件。 */
    public Page<Audit> audit(VerifiedLogin login,Partition p,String after) {
        String actor=authorize(login,p,AUDIT,null);var rows=mapper.audit(p,cursor(after));
        record(actor,p,AUDIT,null,Outcome.ALLOWED);
        return new Page<>(List.copyOf(rows.subList(0,Math.min(100,rows.size()))),rows.size()>100?rows.get(99).id():null);
    }
    private String authorize(VerifiedLogin login,Partition p,String operation,String target) {
        AccessValues.partition(p);String actor=identity.principalForLogin(login.issuer(),login.subject()).id();
        try {
            var member=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null);access.authority(login,p);
            if(authorities.stream().noneMatch(a -> a.partition().equals(p) && a.membershipId().equals(member.membershipId()) && a.generation()==member.membershipGeneration())) throw new GovernanceException(ACCESS_DENIED);
            return actor;
        } catch(GovernanceException denied) {record(actor,p,operation,target,Outcome.DENIED);throw denied;}
    }
    private void record(String actor,Partition p,String operation,String target,Outcome outcome) {
        auditTransaction.executeWithoutResult(status -> {if(mapper.record(UUID.randomUUID().toString(),p,actor,operation,target,outcome.code())!=1)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);});
    }
    private static String cursor(String after) {if(after==null||after.isEmpty())return "";BootstrapCommand.uuid(after);return after;}
    private static Explanation view(PermissionMapper.Row r) {
        return new Explanation(r.grantId(),r.memberId(),r.generation(),r.groupId(),r.roleId(),r.roleCode(),r.roleVersion(),AccessValues.read(r.capabilitiesJson()),r.scope(),
                r.ruleJson()==null?null:ScopeRules.decode(r.ruleJson()),r.sourceType(),r.sourceId(),r.validFrom(),r.validTo(),r.grantState(),r.effectiveState(),r.grantVersion(),r.operationId(),r.policyState(),r.directoryState());
    }
}
