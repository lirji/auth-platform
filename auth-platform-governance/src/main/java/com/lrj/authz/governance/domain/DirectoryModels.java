package com.lrj.authz.governance.domain;

/** 目录持久化值，不携带源端实体或登录凭据；状态变化由目录事务用例控制。 */
public final class DirectoryModels {
    private DirectoryModels() {}
    /** 来源及检查点共同锁定，不能跨来源拼接游标。 */
    public record Source(String id, String source, String environment, String sourceTenantRef, String tenantId,
                         String issuer, long lastSequence, String lastFingerprint, boolean quarantined) {}
    /** 当前目录事实；payloadJson 是协议定义的有界投影，不是随意扩展的业务 Map。 */
    public record Entry(String sourceId, String tenantId, String aggregateType, String aggregateId, long aggregateVersion,
                        String payloadHash, String payloadJson, String principalId, String membershipId, String loginSubject) {}
    /** 接受后不可变证据只带必要前后内容；不重复保存登录绑定。 */
    public record ChangeEvidence(String sourceId,String eventId,String tenantId,long partitionSequence,String aggregateType,
            String aggregateId,long aggregateVersion,String eventFingerprint,String membershipId,String outcome,String beforeJson,String afterJson,String occurredAt) {}
    /** 最小回执用于去重和连续推进，不重新应用历史业务事实。 */
    public record Inbox(String eventId, long partitionSequence, String fingerprint, String snapshotId, boolean processed) {}
    /** 持久化封存声明必须与 BEGIN/END 和实际内容全部吻合。 */
    public record Snapshot(String sourceId, String snapshotId, long startSequence, long endSequence, long expectedCount,
                           String contentHash, boolean beginSeen, boolean endSeen, boolean complete) {}
}
