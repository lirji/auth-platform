package com.lrj.authz.protocol;

/** P3治理命令不携带操作者身份，身份来自服务端验证Token。 */
public final class SafetyDtos {
    private SafetyDtos() {}

    /** 固定组受益方与同Grant范围。 */
    public record GroupGrant(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            String groupId,
            String roleId,
            ScopeDtos.Rule scopeRule,
            String sourceId,
            String validFrom,
            String validTo) {}

    /** 显式切换及有界重试管理命令。 */
    public record PartitionCommand(
            String tenantId,
            String applicationId,
            String environment,
            String commandId,
            String kind) {}

    /** 应用拥有者独立紧急开关，不修改旧角色能力集合。 */
    public record CapabilityCommand(
            String applicationId,
            String capability,
            boolean disabled,
            long expectedVersion,
            String reason,
            String commandId) {}
}
