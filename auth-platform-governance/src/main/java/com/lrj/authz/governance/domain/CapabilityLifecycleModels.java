package com.lrj.authz.governance.domain;

import java.time.Instant;

/** SQL保存稳定状态code和当前元数据，公开协议只在应用边界转换。 */
public final class CapabilityLifecycleModels {
    private CapabilityLifecycleModels() {}
    /** 生命周期与紧急disabled分别持久化，读模型不能混为撤权。 */
    public record Value(String applicationId,String capability,String state,long version,String reason,String changedBy,Instant updatedAt) {}
    /** 不可变原命令，重复调用只读当前状态与此历史依据。 */
    public record Receipt(String applicationId,String commandId,String payloadHash,String capability,String beforeState,String afterState,
            long beforeVersion,long afterVersion,String reason,String actor,Instant createdAt) {}
}
