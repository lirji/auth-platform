package com.lrj.authz.server.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** 导航调用资格独立启用，不把执行引用或管理资格自动扩展成导航资格。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled","authz.governance.scope.enabled","authz.governance.navigation.enabled"},havingValue="true")
public class GovernanceNavigationConfiguration {
    /** 允许名单必须属于当前已注册双身份服务及scope调用方。 */
    @Bean NavigationSettings navigationSettings(Environment env,GovernanceScopeConfiguration.ScopeSettings scope,GovernanceServerConfiguration.Settings server) {
        var p=GovernanceConfigurationFile.read(env.getProperty("authz.governance.configuration"));
        String value=p.getProperty("navigation.callers");
        if(value==null)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        var callers=new HashSet<>(List.of(value.split(",",-1)));callers.forEach(CatalogManifest::code);
        var registered=new HashSet<String>();server.callers().forEach(caller->registered.add(caller.callerServiceId()));
        if(callers.isEmpty()||!scope.callers().containsAll(callers)||!registered.containsAll(callers))throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        return new NavigationSettings(Set.copyOf(callers));
    }
    /** 只用严格范围存在性判权，没有P2或旧管理接口回退。 */
    @Bean BusinessNavigation businessNavigation(GovernanceRuntime runtime,GovernanceScopeConfiguration.ScopeSettings settings) {
        return runtime.navigation(settings.graph());
    }
    record NavigationSettings(Set<String> callers) {}
}
