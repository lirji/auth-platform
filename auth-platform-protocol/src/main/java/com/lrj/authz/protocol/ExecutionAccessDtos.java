package com.lrj.authz.protocol;

/** 持久任务只保存执行引用，用户Token仅用于签发请求，不进入任务或响应。 */
public final class ExecutionAccessDtos {
    private ExecutionAccessDtos() {}

    /** requestId同时作为签发幂等键，期限沿用原任务的明确截止时间。 */
    public record Issue(CentralAccessDtos.Check check, String expiresAt) {}

    /** 引用本身不表示ALLOW；每次业务推进仍须调用执行检查。 */
    public record Reference(
            String schemaVersion,
            String requestId,
            String executionId,
            GovernanceDtos.AccessContext context,
            String capability,
            String resourceType,
            String expiresAt) {}

    /** 事实由资源Owner当前读库生成，引用不得跨调用服务或租户使用。 */
    public record Check(String executionId, ScopeAccessDtos.ResourceCheck resource) {}

    /** 集合/创建没有对象事实，只返回引用原路径与当前权限的交集。 */
    public record ScopeCheck(String executionId, CentralAccessDtos.Check check) {}
}
