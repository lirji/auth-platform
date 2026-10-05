package com.lrj.authz.governance.projection.domain;

import com.lrj.authz.governance.access.domain.AccessModels;
import com.lrj.authz.governance.identity.domain.IdentityModels;
import com.lrj.authz.protocol.RelationshipUpdate;

import java.time.Instant;
import java.util.List;

/** 投影操作是不可变业务快照，租约与回执是独立推进状态。 */
public final class ProjectionModels {
    private ProjectionModels() {}

    /** 控制Mapper固定SQL分支，不允许调用方传表名。 */
    public enum Kind {
        POLICY,
        DIRECTORY
    }

    /** 终态不会被重新领取，重新规划必须生成新UUID。 */
    public enum OperationState implements IdentityModels.DbCode {
        PENDING("PENDING"),
        APPLIED("APPLIED"),
        SUPERSEDED("SUPERSEDED"),
        QUARANTINED("QUARANTINED");
        private final String code;

        OperationState(String code) {
            this.code = code;
        }

        /** 数据库存储稳定编码。 */
        public String code() {
            return code;
        }
    }

    /** 单批执行结果，READY只表示当前分区确认完成。 */
    public enum Step {
        BUSY,
        READY,
        APPLIED,
        RECOVERED,
        RETRY_WAIT,
        BLOCKED
    }

    /** 分区指向真实策略或目录外键，租户参数不从任意payload获取。 */
    public record Target(Kind kind, String fenceId, AccessModels.Partition partition) {}

    /** 唯一分区领取记录，数据库时间决定租约有效性。 */
    public record Stream(
            String fenceId,
            String policyId,
            String directoryId,
            String workerId,
            long leaseGeneration,
            Instant leaseUntil,
            long targetEpoch,
            String scanCursor,
            long batchCounter,
            long confirmedBatch,
            String marker,
            String zedToken,
            int failures,
            Instant nextAttemptAt) {}

    /** 不可变元数据与可变状态显式分开；hash覆盖所有执行语义。 */
    public record Operation(
            String id,
            String fenceId,
            long targetEpoch,
            long batchNo,
            String expectedMarker,
            String payloadJson,
            String contentHash,
            String afterCursor,
            boolean lastBatch,
            OperationState state,
            int attempts,
            String createdBy,
            long leaseGeneration) {}

    /** 当前Grant版本只用于有条件激活，不允许覆盖新撤销状态。 */
    public record GrantChange(
            String id,
            long version,
            String membershipId,
            long generation,
            boolean present,
            String groupId) {}

    /** 历史目录边保存删除对象，不靠当前成员列表猜测旧图内容。 */
    public record GroupChange(
            String id, String groupId, String membershipId, long generation, boolean present) {}

    /** 图关系在规划事务内固定，执行器不能随新marker重新解释旧内容。 */
    public record Payload(List<GrantChange> grants, List<RelationshipUpdate> relationships) {
        public Payload {
            grants = List.copyOf(grants);
            relationships = List.copyOf(relationships);
        }
    }
}
