package com.lrj.authz.governance.access.domain;

import com.lrj.authz.governance.access.domain.AccessModels.*;

import java.time.Instant;

/** 迁移预览的主库快照；水位仅参与内部比较，不作为浏览器授权凭据。 */
public final class RoleMigrationModels {
    private RoleMigrationModels() {}

    /** 一次有界查询同时读取Grant、不可变范围和当前受益人，避免逐行回源。 */
    public record Snapshot(
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
            String groupId,
            String graphToken,
            String ruleJson,
            String scopeHash,
            boolean memberActive,
            Long membershipVersion,
            Instant memberValidFrom,
            Instant memberValidTo,
            boolean strictPartition,
            boolean groupAvailable) {
        /** 复用既有公开投影，内部水位和人员版本不直接泄露。 */
        public Grant grant() {
            return new Grant(
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
                    graphToken,
                    groupId);
        }
    }
}
