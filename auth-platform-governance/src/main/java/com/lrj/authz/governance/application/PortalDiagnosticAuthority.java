package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.Partition;

import java.util.*;

/** 诊断和审计有单独配置授权，应用管理委派不自动获得他人解释权限。 */
public record PortalDiagnosticAuthority(Partition partition, String membershipId, long generation) {
    /** 配置固定分区和当前成员代际，不接受通配符。 */
    public PortalDiagnosticAuthority {
        AccessValues.partition(partition);
        BootstrapCommand.uuid(membershipId);
        if (generation < 1) throw invalid();
    }

    /** 缺省关闭；有界解析和重复范围检查避免多权威歧义。 */
    public static List<PortalDiagnosticAuthority> from(Properties properties) {
        try {
            int count = Integer.parseInt(properties.getProperty("portal.diagnostic.count", "0"));
            if (count < 0 || count > 100) throw invalid();
            List<PortalDiagnosticAuthority> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 1; i <= count; i++) {
                String prefix = "portal.diagnostic." + i + ".";
                var authority =
                        new PortalDiagnosticAuthority(
                                new Partition(
                                        properties.getProperty(prefix + "tenant-id"),
                                        properties.getProperty(prefix + "application-id"),
                                        properties.getProperty(prefix + "environment")),
                                properties.getProperty(prefix + "membership-id"),
                                Long.parseLong(properties.getProperty(prefix + "generation")));
                if (!seen.add(
                        AccessValues.hash(
                                authority.partition(),
                                authority.membershipId(),
                                authority.generation()))) throw invalid();
                result.add(authority);
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
