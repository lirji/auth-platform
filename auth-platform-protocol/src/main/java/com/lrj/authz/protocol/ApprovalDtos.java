package com.lrj.authz.protocol;

import java.util.List;

/** auth与OA公开申请流程契约；OA不携带可替换Grant内容的回调。 */
public final class ApprovalDtos {
    private ApprovalDtos() {}
    /** 固定快照只用字符串UTC跨版本传输；成员由OA显式身份桥解析。 */
    public record Start(String tenantId,String applicationId,String environment,String requestId,long requestVersion,
                        String snapshotHash,String policyId,long policyVersion,String policyHash,
                        String membershipId,long generation,String approverMembershipId,long approverGeneration,
                        String roleId,List<String> capabilities,ScopeDtos.Rule scopeRule,String validFrom,String validTo,String reason) {
        /** 稳定业务键不包含投递尝试次数。 */
        public String businessKey() { return requestId+":"+requestVersion; }
    }
    /** 按业务键查询也校验原快照，避免把错误实例绑定回来。 */
    public record Lookup(String tenantId,String applicationId,String environment,String requestId,long requestVersion,String snapshotHash) {}
    /** 实例已持久化不代表审批完成，更不代表授权ACTIVE。 */
    public record Instance(String requestId,long requestVersion,String snapshotHash,String approvalInstanceId) {}
}
