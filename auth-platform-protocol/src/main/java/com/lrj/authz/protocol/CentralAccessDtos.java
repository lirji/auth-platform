package com.lrj.authz.protocol;

import java.util.List;

/** 新治理中央检查协议，严格区分当前身份、应用权限和展示提示。 */
public final class CentralAccessDtos {
    private CentralAccessDtos() {}

    /** 仅选择本人租户；应用/环境和主体永远不由body给定。 */
    public record Check(
            String tenantId,
            Long expectedMembershipGeneration,
            String requestId,
            String capability,
            String resourceType) {}

    /** 有界批次逐项关联，不根据数组下标拼接授权结果。 */
    public record Batch(List<Check> checks) {}

    /** 版本化结果，只对本次调用有效，decisionId不是可重放凭据。 */
    public record Decision(
            String schemaVersion,
            String requestId,
            String capability,
            String resourceType,
            String decision,
            String scope,
            GovernanceDtos.AccessContext context,
            String decisionId) {}

    /** 任一必需项失败时整批失败，不能省略未知项。 */
    public record Decisions(List<Decision> results) {}
}
