package com.lrj.authz.protocol;

import java.util.List;

/** 人工复核固定已选来源，决定不会隐式创建或恢复授权。 */
public final class AccessReviewDtos {
    private AccessReviewDtos() {}

    /** 稳定协议代码与数据库检查约束一致，未知状态由边界拒绝。 */
    public enum Decision {
        KEEP("KEEP"),
        REVOKE("REVOKE"),
        INVESTIGATE("INVESTIGATE");
        private final String code;

        Decision(String code) {
            this.code = code;
        }

        /** 持久化采用显式稳定代码，不使用ordinal。 */
        public String code() {
            return code;
        }

        /** 未知代码拒绝，不推断成功或任意状态。 */
        public static Decision fromCode(String code) {
            for (var v : values()) if (v.code.equals(code)) return v;
            throw new IllegalArgumentException("未知复核代码");
        }
    }

    public enum ItemState {
        PENDING("PENDING"),
        INVESTIGATE("INVESTIGATE"),
        KEPT("KEPT"),
        WAIT_REVOKE("WAIT_REVOKE"),
        REVOKED("REVOKED"),
        CANCELLED("CANCELLED");
        private final String code;

        ItemState(String code) {
            this.code = code;
        }

        /** 持久化采用显式稳定代码，不使用ordinal。 */
        public String code() {
            return code;
        }

        /** 未知代码拒绝，不推断成功或任意状态。 */
        public static ItemState fromCode(String code) {
            for (var v : values()) if (v.code.equals(code)) return v;
            throw new IllegalArgumentException("未知复核代码");
        }
    }

    public enum TaskState {
        RUNNING("RUNNING"),
        NEEDS_INVESTIGATION("NEEDS_INVESTIGATION"),
        COMPLETED("COMPLETED"),
        CANCELLED("CANCELLED");
        private final String code;

        TaskState(String code) {
            this.code = code;
        }

        /** 持久化采用显式稳定代码，不使用ordinal。 */
        public String code() {
            return code;
        }

        /** 未知代码拒绝，不推断成功或任意状态。 */
        public static TaskState fromCode(String code) {
            for (var v : values()) if (v.code.equals(code)) return v;
            throw new IllegalArgumentException("未知复核代码");
        }
    }

    /** 只提供当前合格成员编号，不返回登录绑定。 */
    public record Responsible(String membershipId, long generation, long version) {}

    /** 原来源快照和撤权检查点分开；确认operation只来自实际严格回执。 */
    public record Item(
            String id,
            String grantId,
            PersonnelImpactDtos.GrantSource original,
            String originalHash,
            ItemState state,
            long version,
            String reason,
            String revokeCommand,
            String confirmedOperationId) {}

    /** 一个任务最多100个条目；当前人员报告不会自动扩充原范围。 */
    public record Detail(
            String id,
            String membershipId,
            long targetGeneration,
            String originalBasisHash,
            String responsibleMembershipId,
            long responsibleGeneration,
            TaskState state,
            long version,
            String reason,
            String createdBy,
            long creatorGeneration,
            String createdAt,
            String updatedAt,
            List<Item> items,
            PersonnelImpactDtos.Report currentReport,
            boolean responsibleQualified,
            boolean canDecide) {}

    /** 列表不展开人员或范围，真正详情仍重新诊断鉴权。 */
    public record Summary(
            String id,
            String membershipId,
            String responsibleMembershipId,
            TaskState state,
            long version,
            String createdAt,
            String updatedAt) {}

    /** 一页显式选择，依据和原来源由服务端核对。 */
    public record Create(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            String membershipId,
            String responsibleMembershipId,
            long responsibleGeneration,
            String basisHash,
            String sourceCursor,
            List<String> grantIds,
            String reason) {}

    /** 新决定必须基于当前报告和两个乐观版本；整组撤销需明确承认影响。 */
    public record Decide(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            long expectedTaskVersion,
            String itemId,
            long expectedItemVersion,
            String currentBasisHash,
            Decision decision,
            String reason,
            boolean wholeGroupAck) {}

    /** 确认已发撤权不要求旧人员依据，但必须匹配实际完成操作。 */
    public record Confirm(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            long expectedTaskVersion,
            String itemId,
            long expectedItemVersion,
            String reason) {}

    /** 取消不回补已发生的授权副作用。 */
    public record Cancel(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            long expectedTaskVersion,
            String reason) {}

    /** 失权负责人由当前合格管理员显式替换，不自动派单。 */
    public record Reassign(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            long expectedTaskVersion,
            String responsibleMembershipId,
            long responsibleGeneration,
            String reason) {}
}
