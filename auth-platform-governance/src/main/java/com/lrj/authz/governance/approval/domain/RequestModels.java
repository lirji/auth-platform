package com.lrj.authz.governance.approval.domain;

import com.lrj.authz.governance.identity.domain.IdentityModels;

import java.time.Instant;

/** 申请固定内容与状态分别建模；状态变化不能重算时间或换角色。 */
public final class RequestModels {
    private RequestModels() {}

    /** 固定来源协议码，回收仅匹配本申请来源。 */
    public static final String SOURCE_TYPE = "OA_REQUEST";

    /** 版本ID即固定策略引用，审批人仍需具备当前管理资格。 */
    public record Policy(
            String id,
            String tenantId,
            String applicationId,
            String environment,
            String roleId,
            String scopeJson,
            long maxDurationSeconds,
            String approverMembershipId,
            long approverGeneration,
            String delegationHash,
            long policyVersion,
            String contentHash,
            boolean enabled) {}

    /** 首版不允许编辑提交内容；修改需要新的申请标识。 */
    public enum State implements IdentityModels.DbCode {
        SUBMITTED("SUBMITTED"),
        IN_REVIEW("IN_REVIEW"),
        APPROVED("APPROVED"),
        REJECTED("REJECTED"),
        CANCELLED("CANCELLED");
        private final String code;

        State(String code) {
            this.code = code;
        }

        /** 持久化稳定编码，拒绝未知状态。 */
        public String code() {
            return code;
        }
    }

    /** 数据库和审批传输共用不可变内容，不向本人查询直接暴露审批人信息。 */
    public record Request(
            String id,
            String tenantId,
            String applicationId,
            String environment,
            String requesterPrincipal,
            String membershipId,
            long generation,
            String policyId,
            String roleId,
            String capabilitiesJson,
            String scopeJson,
            String policyHash,
            Instant memberValidTo,
            Instant validFrom,
            Instant validTo,
            String reason,
            long requestVersion,
            String snapshotHash,
            State state,
            long stateVersion,
            String approvalInstanceId,
            String grantId,
            String commandId,
            String migrationOldGrantId,
            Long migrationOldVersion,
            boolean withdrawn) {
        /** MyBatis只使用完整快照，旧普通申请构造保持源码兼容。 */
        @org.apache.ibatis.annotations.AutomapConstructor
        public Request {}

        /** 普通申请不关联迁移，也没有撤回事实。 */
        public Request(
                String id,
                String tenantId,
                String applicationId,
                String environment,
                String requesterPrincipal,
                String membershipId,
                long generation,
                String policyId,
                String roleId,
                String capabilitiesJson,
                String scopeJson,
                String policyHash,
                Instant memberValidTo,
                Instant validFrom,
                Instant validTo,
                String reason,
                long requestVersion,
                String snapshotHash,
                State state,
                long stateVersion,
                String approvalInstanceId,
                String grantId,
                String commandId) {
            this(
                    id,
                    tenantId,
                    applicationId,
                    environment,
                    requesterPrincipal,
                    membershipId,
                    generation,
                    policyId,
                    roleId,
                    capabilitiesJson,
                    scopeJson,
                    policyHash,
                    memberValidTo,
                    validFrom,
                    validTo,
                    reason,
                    requestVersion,
                    snapshotHash,
                    state,
                    stateVersion,
                    approvalInstanceId,
                    grantId,
                    commandId,
                    null,
                    null,
                    false);
        }
    }
}
