package com.lrj.authz.governance.access.persistence;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.protocol.PersonnelImpactDtos.*;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 所有人员核对SQL集中在持久层，分页和全量依据使用同一目标／分区。 */
public interface PersonnelImpactMapper {
    /** 非当前人员也能诊断，但不存在／跨租户返回空并由边界统一拒绝。 */
    Target target(@Param("p") Partition p, @Param("member") String member);

    /** 最多10000行参与摘要，完整计数单独计算，不拿第一页替代全部。 */
    record Basis(long total, String hash) {}

    Basis basis(@Param("p") Partition p, @Param("member") String member);

    /** 只读取本人必要目录投影，绝不返回登录字段。 */
    record DirectoryRow(
            String sourceId,
            String aggregateId,
            long aggregateVersion,
            String payloadHash,
            String factsJson,
            boolean historyMissing) {}

    List<DirectoryRow> directories(@Param("p") Partition p, @Param("member") String member);

    /** 冲突原因去重且有界，无原消息正文。 */
    record SourceRow(
            String sourceId, long lastSequence, boolean quarantined, String conflictReasons) {}

    List<SourceRow> directorySources(@Param("p") Partition p);

    /** 稳定来源／序号复合游标，历史内容不替代当前目录。 */
    record ChangeRow(
            String sourceId,
            String eventId,
            long partitionSequence,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventFingerprint,
            String outcome,
            String beforeJson,
            String afterJson,
            String occurredAt) {}

    List<ChangeRow> changes(
            @Param("p") Partition p,
            @Param("member") String member,
            @Param("source") String source,
            @Param("sequence") long sequence);

    /** 当前页Grant对应的组关系一次读取，避免逐来源N+1。 */
    record GroupRow(
            String grantId,
            String id,
            long generation,
            boolean current,
            String periodsJson,
            boolean groupActive) {}

    List<GroupRow> relations(
            @Param("p") Partition p,
            @Param("member") String member,
            @Param("ids") List<String> ids);

    /** 与既有Safety回执相同判断，只有实际回执和双栅栏满足才COMPLETED。 */
    List<RevocationReceipt> receipts(@Param("p") Partition p, @Param("ids") List<String> ids);
}
