package com.lrj.authz.governance.domain;

import java.time.Instant;
import com.lrj.authz.governance.domain.IdentityModels.DbCode;

/** 迁移固定计划与可推进检查点分开，终态不会自动恢复旧来源。 */
public final class RoleMigrationTaskModels {
    private RoleMigrationTaskModels() {}
    /** 来源类型不随任务推进改变，组授权始终保留动态目录资格。 */
    public static final String DIRECT_SOURCE="DIRECT", GROUP_SOURCE="GROUP";
    /** 数据库和协议使用显式稳定编码，不使用 ordinal。 */
    public enum Stage implements DbCode {
        READY_TO_REVOKE("READY_TO_REVOKE"), WAIT_REVOKE_CONFIRM("WAIT_REVOKE_CONFIRM"),
        READY_TO_GRANT("READY_TO_GRANT"), WAIT_NEW_CONFIRM("WAIT_NEW_CONFIRM"),
        COMPLETED("COMPLETED"), FAILED("FAILED"), CANCELLED("CANCELLED");
        private final String code;
        Stage(String code){this.code=code;}
        /** 持久化稳定状态。 */
        public String code(){return code;}
        /** 终态不能通过重试补回被撤销来源。 */
        public boolean terminal(){return this==COMPLETED||this==FAILED||this==CANCELLED;}
    }
    /** 任务最多50项，当前状态从固定集合聚合。 */
    public record Task(String id,String tenantId,String applicationId,String environment,String oldRoleId,
            String newRoleId,String state,long version,String createdBy,long createdGeneration,
            String commandId,Instant createdAt,Instant updatedAt) {
        /** 分区只来自已获授权任务，而非来源内容。 */
        public AccessModels.Partition partition(){return new AccessModels.Partition(tenantId,applicationId,environment);}
    }
    /** 原对象／范围／期限永久固定，新Grant标识预分配，图回执只在确认后保存。 */
    public record Entry(String id,String taskId,String tenantId,String applicationId,String environment,
            String oldGrantId,long oldVersion,String newRoleId,String memberId,long generation,
            String oldSourceId,String scope,String ruleJson,String scopeHash,Instant validFrom,Instant validTo,
            String originalHash,String newSourceId,String plannedGrantId,String revokeCommand,String grantCommand,
            Stage stage,long version,String reason,String revokeReceiptId,String newGrantId,String newReceiptId,
            Instant updatedAt,String sourceType,String groupId) {}
    /** 只有真实操作回执及双栅栏当前READY才可报告新授权确认。 */
    public record Confirmation(String status,String operationId) {}
}
