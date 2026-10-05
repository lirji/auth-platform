package com.lrj.authz.server.governance;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.GovernanceRuntime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.*;

/** 严格范围端点独立启用、独立P3图，不把旧调用方自动升级成资源Owner。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.scope.enabled"
        },
        havingValue = "true")
public class GovernanceScopeConfiguration {
    /** 来源为0600治理配置，不接受浏览器选择app、图地址或资源Owner。 */
    @Bean
    ScopeSettings governanceScopeSettings(Environment env) {
        var p = GovernanceConfigurationFile.read(env.getProperty("authz.governance.configuration"));
        String callers = p.getProperty("scope.check.callers");
        if (callers == null) throw invalid();
        Set<String> allowed = new HashSet<>(List.of(callers.split(",", -1)));
        if (allowed.isEmpty() || allowed.size() > 32) throw invalid();
        allowed.forEach(CatalogManifest::code);
        Map<String, Set<String>> owners = new HashMap<>();
        for (String key : p.stringPropertyNames())
            if (key.startsWith("scope.owner.")) {
                String app = key.substring("scope.owner.".length());
                CatalogManifest.code(app);
                Set<String> types = new HashSet<>(List.of(p.getProperty(key).split(",", -1)));
                if (types.isEmpty()
                        || !types.stream()
                                .allMatch(com.lrj.authz.protocol.ScopeResourceBindings::supports))
                    throw invalid();
                owners.put(app, Set.copyOf(types));
            }
        if (owners.isEmpty() || owners.size() > 32) throw invalid();
        return new ScopeSettings(
                new SpiceDbProjectionGraph(
                        p.getProperty("scope.graph.http"),
                        p.getProperty("scope.graph.key"),
                        Duration.ofSeconds(3)),
                Set.copyOf(allowed),
                Map.copyOf(owners));
    }

    /** 所有范围入口共享持久栅栏及主库A/C检查。 */
    @Bean
    ReliableAuthorization governanceScopeAuthorization(
            GovernanceRuntime runtime, ScopeSettings settings) {
        return runtime.reliableAuthorization(settings.graph());
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }

    /** 配置仅用于内部装配，不序列化凭据。 */
    record ScopeSettings(
            SpiceDbProjectionGraph graph, Set<String> callers, Map<String, Set<String>> owners) {}
}
