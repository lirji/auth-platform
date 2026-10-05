package com.lrj.authz.governance.identity.invitation.domain;

import com.lrj.authz.governance.identity.domain.IdentityModels.*;

import java.time.Instant;

/** 邀请只表达显式成员绑定，不表达应用准入或业务授权。 */
public final class InvitationModels {
    private InvitationModels() {}

    /** 单次邀请状态；过期由数据库当前时间判定，不用后台任务延迟决定是否可接受。 */
    public enum InvitationState implements DbCode {
        PENDING("PENDING"),
        ACCEPTED("ACCEPTED"),
        REVOKED("REVOKED");
        private final String code;

        InvitationState(String code) {
            this.code = code;
        }

        /** 显式持久化编码不依赖枚举名称。 */
        public String code() {
            return code;
        }
    }

    /** 数据库邀请事实，只含令牌摘要；默认输出也不暴露摘要。 */
    public record Invitation(
            String id,
            String tenantId,
            String sponsorMembershipId,
            String operatorRef,
            String targetIssuer,
            String targetSubject,
            MemberKind memberKind,
            String tokenHash,
            Instant expiresAt,
            Instant membershipValidTo,
            InvitationState state,
            long version,
            String acceptedMembershipId,
            Long acceptedGeneration,
            Instant acceptedAt) {
        /** 只生成既有脱敏诊断表示，不能把登录、服务凭据或邀请秘密带入日志。 */
        @Override
        public String toString() {
            return "Invitation[id=" + id + ",state=" + state.code() + ",proof=redacted]";
        }
    }
}
