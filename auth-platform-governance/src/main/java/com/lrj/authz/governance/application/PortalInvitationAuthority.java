package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.Partition;

import java.util.*;

/** 邀请管理独立显式委派，不从应用管理权限自动推导企业邀请权。 */
public record PortalInvitationAuthority(
        Partition partition,
        String membershipId,
        long generation,
        long maxInvitationSeconds,
        long maxMembershipSeconds) {
    private static final long MAX_INVITATION_SECONDS = 604800;
    private static final long MAX_MEMBERSHIP_SECONDS = 31536000;

    /** 部署时失败即关闭入口；不接受重复范围的含糊上限。 */
    public PortalInvitationAuthority {
        AccessValues.partition(partition);
        BootstrapCommand.uuid(membershipId);
        if (generation < 1
                || maxInvitationSeconds < 1
                || maxInvitationSeconds > MAX_INVITATION_SECONDS
                || maxMembershipSeconds <= maxInvitationSeconds
                || maxMembershipSeconds > MAX_MEMBERSHIP_SECONDS) throw invalid();
    }

    /** 未配置时为空集合，兼容原部署且默认拒绝门户邀请管理。 */
    public static List<PortalInvitationAuthority> from(Properties props) {
        try {
            int count = Integer.parseInt(props.getProperty("portal.invitation.count", "0"));
            if (count < 0 || count > 100) throw invalid();
            List<PortalInvitationAuthority> result = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (int i = 1; i <= count; i++) {
                String prefix = "portal.invitation." + i + ".";
                var value =
                        new PortalInvitationAuthority(
                                new Partition(
                                        props.getProperty(prefix + "tenant-id"),
                                        props.getProperty(prefix + "application-id"),
                                        props.getProperty(prefix + "environment")),
                                props.getProperty(prefix + "membership-id"),
                                Long.parseLong(props.getProperty(prefix + "generation")),
                                Long.parseLong(
                                        props.getProperty(prefix + "max-invitation-seconds")),
                                Long.parseLong(
                                        props.getProperty(prefix + "max-membership-seconds")));
                if (!keys.add(
                        AccessValues.hash(
                                value.partition(), value.membershipId(), value.generation())))
                    throw invalid();
                result.add(value);
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
