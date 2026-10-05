package com.lrj.authz.governance.access.domain;

/** 复核权威状态在主库，固定输入与可变检查点由数据库分开约束。 */
public final class AccessReviewModels {
    private AccessReviewModels() {}

    /** 任务创建事实不可变，负责人只允许显式版本化替换。 */
    public record Task(
            String id,
            String tenantId,
            String applicationId,
            String environment,
            String membershipId,
            long targetGeneration,
            String originalBasisHash,
            String responsibleMembershipId,
            long responsibleGeneration,
            String state,
            long version,
            String reason,
            String createdBy,
            long creatorGeneration,
            String commandId,
            int itemCount,
            String createdAt,
            String updatedAt) {}

    /** 原来源JSON不随当前投影变化覆盖；撤销意图预先分配稳定UUID。 */
    public record Entry(
            String id,
            String taskId,
            String tenantId,
            String applicationId,
            String environment,
            String grantId,
            long originalVersion,
            String originalJson,
            String originalHash,
            String state,
            long version,
            String reason,
            String revokeCommand,
            String confirmedOperationId) {}
}
