package com.lrj.authz.protocol;

/** 门户邀请只接收绑定意图，企业负责人和操作者由后端受控配置派生。 */
public final class PortalInvitationDtos {
    private PortalInvitationDtos() {}
    /** 原始证明不进入日志、列表和响应。 */
    public record Issue(String tenantId, String applicationId, String environment, String commandId,
                        String invitationId, String targetSubject, String memberKind, String token,
                        String expiresAt, String membershipValidTo, String reason) {
        @Override public String toString() { return "PortalInvitationIssue[proof=redacted]"; }
    }
    /** 只回收未接受邀请，不借此隐式停用已加入成员。 */
    public record Revoke(String tenantId, String applicationId, String environment, String commandId, long expectedVersion, String reason) {}
    /** 配置只包含本人的期限上限，没有管理名单。 */
    public record Authority(String issuer, long maxInvitationSeconds, long maxMembershipSeconds) {}
    /** 明确排除证明和摘要，列表只在本人受控范围内可见。 */
    public record View(String id, String targetIssuer, String targetSubject, String memberKind, String expiresAt,
                       String membershipValidTo, String state, long version) {}
}
