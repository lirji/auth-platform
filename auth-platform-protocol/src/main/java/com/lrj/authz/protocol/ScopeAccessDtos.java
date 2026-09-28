package com.lrj.authz.protocol;
import java.util.List;
/** 后端一次性范围协议；版本用于检查点绑定，绝不是浏览器可重放票据。 */
public final class ScopeAccessDtos {
    private ScopeAccessDtos() {}
    /** 每条alternative仍保留同Grant的完整AND条件。 */
    public record Plan(String schemaVersion,String requestId,String capability,String resourceType,String decision,String decisionId,
                       GovernanceDtos.AccessContext context,String policyPartitionId,long policyEpoch,String directoryPartitionId,long directoryEpoch,
                       long manifestVersion,long tenantVersion,String validUntil,List<ScopeDtos.Alternative> alternatives) {}
    /** facts只允许经服务身份认证的资源Owner提交。 */
    public record ResourceCheck(CentralAccessDtos.Check check,ScopeDtos.Facts facts) {}
    /** 回显资源版本，资源Owner必须在使用或提交前再次核对实际版本。 */
    public record ResourceDecision(String schemaVersion,String requestId,String capability,String resourceType,String resourceId,long resourceVersion,
                                   String decision,String decisionId,GovernanceDtos.AccessContext context,String validUntil) {}
}
