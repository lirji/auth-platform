package com.lrj.authz.protocol;

import java.util.List;

/** 人员变更核对只给必要源事实和完整来源，不携带登录绑定或资源ALLOW。 */
public final class PersonnelImpactDtos {
    private PersonnelImpactDtos() {}

    /** 只观察本地检查点时不能声称源端同步成功。 */
    public enum SourceSync {
        UNKNOWN
    }

    /** 目录事实的最小前后内容，员工为任职，组织为父关系。 */
    public record Facts(
            String status, List<DirectoryEvents.Assignment> assignments, String parentId) {}

    /** 企业成员事实与全局主体状态明确分开。 */
    public record MemberState(String status, long generation, long version) {}

    /** 一个事件接受前后的真实目录／成员事实；首次无前值保留null。 */
    public record Snapshot(Facts facts, MemberState member) {}

    /** 目标不存在或跨租户时拒绝，不返回存在性。 */
    public record Target(
            String membershipId,
            String status,
            long generation,
            long version,
            String principalStatus,
            long principalVersion,
            String validFrom,
            String validTo) {}

    /** 当前目录版本不是某条历史事件的批准证明。 */
    public record DirectoryFact(
            String sourceId,
            String aggregateId,
            long aggregateVersion,
            String payloadHash,
            Facts facts,
            boolean historyMissing) {}

    /** 隔离与冲突来自持久事实，UNKNOWN不被本地检查点替换。 */
    public record Source(
            String sourceId,
            long lastSequence,
            boolean quarantined,
            List<String> conflictReasons,
            SourceSync sourceSync) {}

    /** 复合键固定source／sequence，OBSOLETE的前后当前事实保持不变。 */
    public record Change(
            String sourceId,
            String eventId,
            long partitionSequence,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventFingerprint,
            String outcome,
            Snapshot before,
            Snapshot after,
            String occurredAt) {}

    /** 曾经属于组不表示当前允许，原代际和任职时段单独展示。 */
    public record GroupRelation(
            String id, long generation, boolean current, String periodsJson, boolean groupActive) {}

    /** 与现有撤权回执相同语义，只有真实图证明完成才显示COMPLETED。 */
    public record RevocationReceipt(
            String grantId,
            long version,
            String status,
            String operationId,
            long desiredEpoch,
            long appliedEpoch) {}

    /** 每项保留原能力与范围，真实撤权回执不能被人员暂不可用替代。 */
    public record GrantSource(
            PermissionDtos.Explanation grant,
            boolean currentGeneration,
            List<GroupRelation> groupRelations,
            RevocationReceipt revocationReceipt) {}

    /** 两类分页共享当前依据，明确完整性与当前取样时间。 */
    public record Report(
            Target target,
            List<DirectoryFact> directories,
            List<Source> directorySources,
            List<Change> changes,
            String nextChangeCursor,
            List<GrantSource> sources,
            String nextSourceCursor,
            String basisHash,
            boolean complete,
            String checkedAt) {}
}
