package com.lrj.authz.protocol;

import java.util.List;

/** 应用RBAC公开契约，身份来自独立认证边界，DTO不携带私有Mapper模型。 */
public final class AccessDtos {
    private AccessDtos() {}
    /** 创建固定角色版本，不接受管理者身份字段。 */
    public record CreateRole(String tenantId,String applicationId,String environment,String commandId,String roleCode,long roleVersion,List<String> capabilities) {}
    /** 直接成员授权，当前仅TENANT_ALL；UTC字符串在服务边界解析。 */
    public record CreateGrant(String tenantId,String applicationId,String environment,String commandId,String memberId,long memberGeneration,String roleId,String scope,String sourceId,String validFrom,String validTo) {}
    /** 乐观版本撤销，重试保留同一命令ID。 */
    public record RevokeGrant(String tenantId,String applicationId,String environment,String commandId,String grantId,long expectedVersion) {}
    /** 角色展示公开字段，能力是结构化数组。 */
    public record RoleView(String id,String roleCode,long version,List<String> capabilities) {}
    /** 状态来自SQL与图确认记录，PENDING不能显示为已生效。 */
    public record GrantView(String id,String memberId,long memberGeneration,String roleId,String scope,String sourceType,String sourceId,String validFrom,String validTo,String state,long version) {}
    /** 分页分别推进角色和授权游标，避免混用集合位置。 */
    public record StateView(List<RoleView> roles,List<GrantView> grants,String nextRoleCursor,String nextGrantCursor) {}
}
