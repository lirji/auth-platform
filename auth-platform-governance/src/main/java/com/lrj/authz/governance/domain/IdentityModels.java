package com.lrj.authz.governance.domain;

import java.time.Instant;

/** 治理身份领域值；不携带发行方 DTO 或持久化依赖。 */
public final class IdentityModels {
    private IdentityModels() {}

    /** 人类与服务主体使用不同认证入口，不能用员工身份隐式创建服务账号。 */
    public enum PrincipalKind { HUMAN, SERVICE }
    /** 主体/企业全局状态，停用不会被登录或初始化自动恢复。 */
    public enum GlobalStatus { ACTIVE, SUSPENDED }
    /** 员工与受邀成员分开；合作方成功登录也不会变成员工。 */
    public enum MemberKind { EMPLOYEE, PARTNER, GUEST }
    /** 离开后再加入递增代际；暂停必须显式恢复，不经重新加入绕过。 */
    public enum MemberStatus { ACTIVE, SUSPENDED, LEFT }

    /** 跨租户稳定主体；version 用于当前状态与后续授权一致性检查。 */
    public record Principal(String id, PrincipalKind kind, GlobalStatus status, long version) {}
    /** 发行方 subject 精确绑定；不根据邮箱、名称或组织推断同一人。 */
    public record LoginIdentity(String issuer, String subject, String principalId) {}
    /** 企业是业务隔离边界，不直接等同 Casdoor 组织。 */
    public record Tenant(String id, String code, GlobalStatus status, long version) {}
    /** 成员当前记录；旧授权必须引用 generation，不能随重新加入复活。 */
    public record Membership(String id, String tenantId, String principalId, MemberKind memberKind,
                             MemberStatus status, long generation, long version, Instant validFrom,
                             Instant validTo, String sponsorMembershipId) {}
    /** 保留旧系统主键，只维护显式映射。 */
    public record LegacyBinding(String sourceSystem, String sourceTenantRef, String sourceSubjectRef,
                                String principalId, String membershipId, String tenantId) {}
}
