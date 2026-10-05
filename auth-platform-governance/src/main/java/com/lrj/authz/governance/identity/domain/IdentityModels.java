package com.lrj.authz.governance.identity.domain;

import java.time.Instant;

/** 治理身份领域值；不携带发行方 DTO 或持久化依赖。 */
public final class IdentityModels {
    private IdentityModels() {}

    /** 持久化/协议稳定代码，不使用 ordinal 或隐式枚举名称。 */
    public interface DbCode {
        /** 返回稳定的持久化与协议编码，调用方不能用枚举序号或异常文本判断业务结果。 */
        String code();
    }

    /** 人类与服务主体使用不同认证入口，不能用员工身份隐式创建服务账号。 */
    public enum PrincipalKind implements DbCode {
        HUMAN("HUMAN"),
        SERVICE("SERVICE");
        private final String code;

        PrincipalKind(String code) {
            this.code = code;
        }

        /** 稳定数据库与公开契约代码。 */
        public String code() {
            return code;
        }
    }

    /** 主体/企业全局状态，停用不会被登录或初始化自动恢复。 */
    public enum GlobalStatus implements DbCode {
        ACTIVE("ACTIVE"),
        SUSPENDED("SUSPENDED");
        private final String code;

        GlobalStatus(String code) {
            this.code = code;
        }

        /** 稳定数据库与公开契约代码。 */
        public String code() {
            return code;
        }
    }

    /** 员工与受邀成员分开；合作方成功登录也不会变成员工。 */
    public enum MemberKind implements DbCode {
        EMPLOYEE("EMPLOYEE"),
        PARTNER("PARTNER"),
        GUEST("GUEST");
        private final String code;

        MemberKind(String code) {
            this.code = code;
        }

        /** 稳定数据库与公开契约代码。 */
        public String code() {
            return code;
        }
    }

    /** 离开后再加入递增代际；暂停必须显式恢复，不经重新加入绕过。 */
    public enum MemberStatus implements DbCode {
        ACTIVE("ACTIVE"),
        SUSPENDED("SUSPENDED"),
        LEFT("LEFT");
        private final String code;

        MemberStatus(String code) {
            this.code = code;
        }

        /** 稳定数据库与公开契约代码。 */
        public String code() {
            return code;
        }
    }

    /** 跨租户稳定主体；version 用于当前状态与后续授权一致性检查。 */
    public record Principal(String id, PrincipalKind kind, GlobalStatus status, long version) {}

    /** 发行方 subject 精确绑定；不根据邮箱、名称或组织推断同一人。 */
    public record LoginIdentity(String issuer, String subject, String principalId) {}

    /** 企业是业务隔离边界，不直接等同 Casdoor 组织。 */
    public record Tenant(String id, String code, GlobalStatus status, long version) {}

    /** 成员当前记录；旧授权必须引用 generation，不能随重新加入复活。 */
    public record Membership(
            String id,
            String tenantId,
            String principalId,
            MemberKind memberKind,
            MemberStatus status,
            long generation,
            long version,
            Instant validFrom,
            Instant validTo,
            String sponsorMembershipId) {}

    /** 保留旧系统主键，只维护显式映射。 */
    public record LegacyBinding(
            String sourceSystem,
            String sourceTenantRef,
            String sourceSubjectRef,
            String principalId,
            String membershipId,
            String tenantId) {}

    /** 单 SQL 快照中的主体/成员版本，防止拼接不同查询时刻的上下文。 */
    public record CurrentContext(
            String principalId,
            long principalVersion,
            String membershipId,
            long membershipGeneration,
            long membershipVersion,
            String tenantId) {}
}
