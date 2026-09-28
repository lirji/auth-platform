package com.lrj.authz.protocol;

import java.util.List;

/** 治理接口的纯 Java 契约，不携带 OA 私有模型、Spring 或发行方 Token。 */
public final class GovernanceDtos {
    private GovernanceDtos() {}

    /** 租户选择必须在认证后与本人数据库成员交叉核验，不能指定主体/app。 */
    public record ResolveRequest(String tenantId, Long expectedMembershipGeneration) {}

    /** 只返回当前有效成员的引用与生命周期，不暴露其他人的组织目录。 */
    public record MembershipView(String membershipId, String tenantId, String memberKind,
                                 long membershipGeneration, long membershipVersion,
                                 String validFrom, String validTo) {}

    /** 列表是有界快照，traceId 由服务端生成；接口没有创建成员的副作用。 */
    public record MembershipsResponse(List<MembershipView> memberships, String traceId) {
        public MembershipsResponse { memberships = List.copyOf(memberships); }
    }

    /** 身份上下文不是业务 ALLOW；app/environment/caller 只能来自受控服务绑定。 */
    public record AccessContext(String principalId, String membershipId, long membershipGeneration,
                                long membershipVersion, long principalVersion, String tenantId,
                                String applicationId, String environment, String callerServiceId,
                                String actorType, String traceId) {}

    /** 错误只公开稳定 code/traceId，不传入 Token、SQL 或内部堆栈。 */
    public record ErrorResponse(String code, String traceId) {}
}
