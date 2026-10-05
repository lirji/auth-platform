package com.lrj.authz.admin.governance;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.governance.identity.authentication.TokenAuthority;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.AuthzEngine;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;

/** 图目标只来自专用私密配置，不能复用旧工作区引擎Bean或用户请求URL。 */
public final class GovernanceGraph {
    private GovernanceGraph() {}

    /** 固定连接/读取时限；部署必须给独立图目标，不存在默认共享回退。 */
    public static AuthzEngine open(Properties p) {
        String http = p.getProperty("graph.http"), key = p.getProperty("graph.key");
        if (http == null || key == null || key.length() < 32)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        TokenAuthority.validateUri(URI.create(http));
        return new SpiceDbAuthzEngine(http, key, Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
}
