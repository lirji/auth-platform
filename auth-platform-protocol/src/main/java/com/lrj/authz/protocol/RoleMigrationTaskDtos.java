package com.lrj.authz.protocol;

import java.util.List;

/** 受控迁移命令和进度，202只表示检查点已提交而非权限已生效。 */
public final class RoleMigrationTaskDtos {
    private RoleMigrationTaskDtos() {}
    /** 创建时核对预览所见版本、截止和范围，不能趁等待延长。 */
    public record GrantSelection(String grantId,long expectedVersion,String validTo,String scopeHash) {}
    /** 管理员明确固定对象，没有隐含全量或调用方操作者字段。 */
    public record Create(String tenantId,String applicationId,String environment,String commandId,
            String oldRoleId,String newRoleId,List<GrantSelection> grants) {}
    /** 一次最多推进一个子项一个阶段，未知结果重放原命令。 */
    public record Advance(String tenantId,String applicationId,String environment,String commandId,
            String itemId,long expectedVersion) {}
    /** 取消保护任务版本，已经提交的授权效果不会回滚。 */
    public record Cancel(String tenantId,String applicationId,String environment,String commandId,long expectedVersion) {}
    /** 固定计划和当前新授权状态同时呈现，取消也不能藏起已提交授权。 */
    public record Item(String id,String oldGrantId,long oldVersion,String memberId,long memberGeneration,
            String originalSourceId,String scope,ScopeDtos.Rule scopeRule,String scopeHash,String validFrom,String validTo,
            String newSourceId,String state,long version,String reason,String revocationOperationId,
            AccessDtos.GrantView newGrant,String newOperationId,String updatedAt) {}
    /** 持久任务详情最多50项，统计覆盖原固定集合。 */
    public record Detail(String id,String tenantId,String applicationId,String environment,
            AccessDtos.RoleView oldRole,AccessDtos.RoleView newRole,String state,long version,String commandId,
            String createdAt,String updatedAt,List<Item> items,long completedCount,long failedCount,long cancelledCount,long waitingCount) {
        public Detail{items=List.copyOf(items);}
    }
    /** 历史入口有界分页，不用本地浏览器存储替代任务账本。 */
    public record Summary(String id,String oldRoleId,String newRoleId,String state,long version,String createdAt,String updatedAt) {}
}
