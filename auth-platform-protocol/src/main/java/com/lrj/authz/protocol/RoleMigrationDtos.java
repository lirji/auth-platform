package com.lrj.authz.protocol;

import java.util.List;

/** 角色迁移只读契约；资格提示不是授权票据，执行必须重新核验。 */
public final class RoleMigrationDtos {
    private RoleMigrationDtos() {}
    /** 固定分区、两个不可变版本和明确对象，禁止隐含全量选择。 */
    public record PreviewRequest(String tenantId, String applicationId, String environment,
            String oldRoleId, String newRoleId, List<String> grantIds) {}
    /** 排除码使用稳定名称，不允许前端将未知码解释为可迁移。 */
    public enum Exclusion {
        UNSUPPORTED_SOURCE, GRANT_ROLE_MISMATCH, GRANT_NOT_ACTIVE, GRANT_NOT_CURRENT,
        GRANT_EXPIRED, MEMBERSHIP_UNAVAILABLE, SELF_GRANT_DENIED, MANAGEMENT_CEILING,
        CAPABILITY_UNAVAILABLE, SCOPE_UNSUPPORTED, DURATION_EXCEEDS_CEILING,
        REQUIRES_SEPARATE_AUTHORIZATION, STRICT_PARTITION_REQUIRED, GROUP_UNAVAILABLE, OA_APPROVAL_REQUIRED
    }
    /** 每项保留来源和固定范围，不能以角色能力差异推算整体权限消失。 */
    public record Item(AccessDtos.GrantView grant, ScopeDtos.Rule scopeRule, String scopeHash,
            long remainingSeconds, String proposedSourceId, boolean eligible, List<Exclusion> reasons,String replacementRequestId) {
        public Item { reasons = List.copyOf(reasons); }
    }
    /** 统计仅针对明确选中集合，UNKNOWN不伪装图回执或执行完成。 */
    public record Preview(String tenantId, String applicationId, String environment,
            AccessDtos.RoleView oldRole, AccessDtos.RoleView newRole,
            List<String> added, List<String> removed, List<String> retained,
            String assessedAt, String viewHash, String continuity, String projectionStatus,
            List<Item> items, long eligibleCount, long excludedCount) {
        public Preview { added=List.copyOf(added); removed=List.copyOf(removed); retained=List.copyOf(retained); items=List.copyOf(items); }
    }
}
