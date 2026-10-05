package com.lrj.authz.governance.access.domain;

import com.lrj.authz.governance.identity.domain.IdentityModels;

import java.time.Instant;
import java.util.List;

/** 直接授权领域模型，固定版本与来源组成完整授权路径。 */
public final class AccessModels {
    private AccessModels() {}

    /** 业务隔离的完整分区；环境不能从任意用户Token推断。 */
    public record Partition(String tenantId, String applicationId, String environment) {}

    /** 管理委派不是业务角色，绑定成员代际与能力上限。 */
    public record Delegation(
            String membershipId,
            long generation,
            String capabilitiesJson,
            long maxDurationSeconds) {}

    /** 发布后不修改的角色权限集合。 */
    public record RoleVersion(
            String id,
            String tenantId,
            String applicationId,
            String environment,
            String roleCode,
            long version,
            String capabilitiesJson,
            String contentHash) {}

    /** 封闭状态只用显式稳定编码持久化。 */
    public enum GrantState implements IdentityModels.DbCode {
        PENDING("PENDING"),
        ACTIVE("ACTIVE"),
        REVOKED("REVOKED");
        private final String code;

        GrantState(String code) {
            this.code = code;
        }

        /** 对外稳定状态编码。 */
        public String code() {
            return code;
        }
    }

    /** 时间窗和成员代际固定，撤销只推进状态/版本，不复用旧来源。 */
    public record Grant(
            String id,
            String tenantId,
            String applicationId,
            String environment,
            String membershipId,
            long generation,
            String roleId,
            String scope,
            String sourceType,
            String sourceId,
            Instant validFrom,
            Instant validTo,
            GrantState state,
            long version,
            String zedToken,
            String groupId) {
        /** 明确数据库使用完整构造器，旧DIRECT调用保留兼容。 */
        @org.apache.ibatis.annotations.AutomapConstructor
        public Grant {}

        /** 旧直接授权不携带组织组。 */
        public Grant(
                String id,
                String tenantId,
                String applicationId,
                String environment,
                String membershipId,
                long generation,
                String roleId,
                String scope,
                String sourceType,
                String sourceId,
                Instant validFrom,
                Instant validTo,
                GrantState state,
                long version,
                String zedToken) {
            this(
                    id,
                    tenantId,
                    applicationId,
                    environment,
                    membershipId,
                    generation,
                    roleId,
                    scope,
                    sourceType,
                    sourceId,
                    validFrom,
                    validTo,
                    state,
                    version,
                    zedToken,
                    null);
        }
    }

    /** 管理回执不把本地REVOKED当成图全局完成。 */
    public record RevocationReceipt(
            String grantId,
            long version,
            String status,
            String operationId,
            long desiredEpoch,
            long appliedEpoch) {}

    /** 只展示本分区已确认的OA组。 */
    public record Group(String id, String orgRef, boolean active, String businessZone) {}

    /** 管理视图稳定游标，有界返回；展示不替代后台实时鉴权。 */
    public record State(
            List<RoleVersion> roles,
            List<Grant> grants,
            String nextRoleCursor,
            String nextGrantCursor) {}

    /** 可靠投影意图只引用不可变Grant路径及目标版本。 */
    public record Projection(String grantId, long grantVersion, String operation, int attempts) {}
}
