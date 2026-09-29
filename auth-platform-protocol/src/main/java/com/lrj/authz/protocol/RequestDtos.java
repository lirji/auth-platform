package com.lrj.authz.protocol;

import java.util.List;

/** OA申请公开边界不接受调用方伪造受益人、能力或审批资格。 */
public final class RequestDtos {
    private RequestDtos() {}
    /** 显式管理委派创建可申请策略；不是自助授权接口。 */
    public record RegisterPolicy(String tenantId,String applicationId,String environment,String commandId,
                                 String roleId,ScopeDtos.Rule scopeRule,long maxDurationSeconds,
                                 String approverMembershipId,long approverGeneration,long policyVersion) {}
    /** 单项本人申请，窗口一经提交固定。 */
    public record Submit(String tenantId,String applicationId,String environment,String commandId,
                         String policyId,String validFrom,String validTo,String reason) {}
    /** 取消意图引用观察到的状态；批准已先提交时自动转本来源回收。 */
    public record Cancel(String tenantId,String applicationId,String environment,String commandId,long stateVersion) {}
    /** 本人公开快照不返回内部审批身份和委派配置。 */
    public record View(String id,String policyId,String roleId,List<String> capabilities,ScopeDtos.Rule scopeRule,
                       String validFrom,String validTo,String reason,long requestVersion,String snapshotHash,
                       String state,long stateVersion,String approvalInstanceId,String grantId) {}
    /** 可申请项目不包含审批人目录或管理委派原文。 */
    public record PolicyView(String id,String roleId,ScopeDtos.Rule scopeRule,long maxDurationSeconds,long policyVersion, String roleCode, long roleVersion, List<String> capabilities) {}
    /** 审批与执行分离，operationId仅在真实投影回执存在时返回。 */
    public record Execution(String requestId,String grantId,String grantState,String displayState,String operationId,
                            String startState,int startAttempts,String startError,String callbackStatus,String callbackResult,long otherActiveGrantCount) {}
    /** 显式下一页游标基于扫描页，过滤不可用策略时也不会丢失后续页。 */
    public record Page<T>(List<T> items,String nextCursor) {}
    /** 只提示本人查看权威申请进度，不在通知中复制敏感范围。 */
    public record Notice(String id,String requestId,long stateVersion,String messageKey,String deliveredAt) {}
}
