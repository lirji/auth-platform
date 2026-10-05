package com.lrj.authz.governance.directory.application;

import com.lrj.authz.governance.directory.domain.DirectoryModels.Source;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.TokenAuthority;
import com.lrj.authz.governance.shared.application.GovernanceException;

import java.net.URI;

/** 只从受控配置建立的来源权限范围，不能使用消息体选择租户或发行方。 */
public record DirectoryAuthority(
        String id,
        String source,
        String environment,
        String sourceTenantRef,
        String tenantId,
        String issuer) {
    /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
    public DirectoryAuthority {
        BootstrapCommand.uuid(id);
        BootstrapCommand.uuid(tenantId);
        BootstrapCommand.bounded(source, 100);
        BootstrapCommand.bounded(environment, 40);
        BootstrapCommand.bounded(issuer, 500);
        if (!source.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                || !environment.matches("[a-z0-9][a-z0-9-]*")
                || sourceTenantRef == null
                || !sourceTenantRef.matches("[1-9][0-9]{0,18}")
                || Long.parseLong(sourceTenantRef) < 1
                || source.length() + environment.length() + 1 > 100) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        TokenAuthority.validateUri(URI.create(issuer));
    }

    /** 稳定来源命名空间含环境，旧身份映射不会在测试和正式之间混用。 */
    public String legacySystem() {
        return source + ":" + environment;
    }

    /** 登记后任何权限范围变化都必须显式迁移，不重用旧来源游标。 */
    public boolean matches(Source row) {
        return row != null
                && id.equals(row.id())
                && source.equals(row.source())
                && environment.equals(row.environment())
                && sourceTenantRef.equals(row.sourceTenantRef())
                && tenantId.equals(row.tenantId())
                && issuer.equals(row.issuer());
    }
}
