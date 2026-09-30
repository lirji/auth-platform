package com.lrj.authz.server.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** 后台执行权单独启用并配置调用服务，不自动提升旧scope调用方。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled","authz.governance.scope.enabled","authz.governance.executions.enabled"},havingValue="true")
public class GovernanceExecutionConfiguration {
    /** 复用同一治理数据库与严格图读栅栏。 */
    @Bean ExecutionAuthorization executionAuthorization(GovernanceRuntime runtime,GovernanceScopeConfiguration.ScopeSettings settings) { return runtime.executions(settings.graph()); }
    /** 私有配置里的允许列表必须是已批准的scope调用方子集。 */
    @Bean ExecutionSettings executionSettings(Environment env,GovernanceScopeConfiguration.ScopeSettings scope) {
        var p=GovernanceConfigurationFile.read(env.getProperty("authz.governance.configuration"));
        String value=p.getProperty("execution.callers");
        if(value==null)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        var callers=new HashSet<>(List.of(value.split(",",-1)));
        callers.forEach(CatalogManifest::code);
        if(callers.isEmpty()||!scope.callers().containsAll(callers)||!scope.owners().getOrDefault("commerce",Set.of()).contains("store"))throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        return new ExecutionSettings(Set.copyOf(callers), scope.owners().getOrDefault("commerce", Set.of()));
    }
    record ExecutionSettings(Set<String> callers, Set<String> resources) {}
}
