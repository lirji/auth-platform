package com.lrj.authz.governance.portal.domain;

/** 管理目录的单语句数据库基准不对外暴露，避免拼接不同时间的资格与清单。 */
public final class PortalCatalogModels {
    private PortalCatalogModels() {}

    /** 字段均来自同一次PG读取；状态版本可以识别紧急开关的关闭再恢复。 */
    public record PublishedCatalogBasis(
            String principalId,
            long principalVersion,
            long tenantVersion,
            String membershipId,
            long generation,
            long membershipVersion,
            String capabilitiesJson,
            long maxDurationSeconds,
            long manifestVersion,
            String contentHash,
            String manifestJson,
            String capabilityStatesJson,
            String presentationJson,
            String presentationHash,
            String lifecycleStatesJson,
            String ownerPrincipalId) {}

    /** 生命周期只代表新增使用资格，与紧急开关分开读取。 */
    public record LifecycleState(String capability, String state, long version, String reason) {}

    /** 未持久化的开关由调用者显式解释为false/version0，不把缺行视为全权限。 */
    public record CapabilityState(String capability, boolean disabled, long version) {}
}
