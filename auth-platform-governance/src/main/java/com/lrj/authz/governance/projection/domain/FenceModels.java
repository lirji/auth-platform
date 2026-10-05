package com.lrj.authz.governance.projection.domain;

import com.lrj.authz.governance.identity.domain.IdentityModels;

/** 业务epoch与图不透明水位分开建模，不能互相比较。 */
public final class FenceModels {
    private FenceModels() {}

    /** 只有READY可以参与严格判权；普通变更不得自动清除隔离状态。 */
    public enum State implements IdentityModels.DbCode {
        READY("READY"),
        UPDATING("UPDATING"),
        BLOCKED("BLOCKED");
        private final String code;

        State(String code) {
            this.code = code;
        }

        /** 持久化稳定编码。 */
        public String code() {
            return code;
        }
    }

    /** 同一分区的权威目标及确认进度。 */
    public record Fence(
            String id, long desiredEpoch, long appliedEpoch, State state, String zedToken) {}

    /** 读取当前有效身份及两道READY栅栏的单语句快照；不包含随时间变化的读取时间。 */
    public record Stamp(
            String principalId,
            String membershipId,
            long generation,
            long membershipVersion,
            long principalVersion,
            long tenantVersion,
            long manifestVersion,
            String tenantId,
            String applicationId,
            String environment,
            String policyId,
            long policyEpoch,
            String policyToken,
            String directoryId,
            long directoryEpoch,
            String directoryToken) {}

    /** 候选业务事实与栅栏来自同一个短事务。 */
    public record Snapshot<T>(Stamp stamp, T data) {}
}
