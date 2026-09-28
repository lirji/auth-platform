package com.lrj.authz.server.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.web.GovernanceWeb;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.List;

/** 内部双身份入口专用装配；配置、数据库与新路由都在开关关闭时缺席。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
@Import(GovernanceWeb.Errors.class)
public class GovernanceServerConfiguration {
    /** 一次读取配置并验证所有发行方/服务绑定，缺项不能静默使用旧鉴权。 */
    @Bean Settings governanceServerSettings(Environment environment) {
        var props = GovernanceConfigurationFile.read(environment.getProperty("authz.governance.configuration"));
        return new Settings(GovernanceDatabase.from(props), GovernanceConfigurationFile.callers(props));
    }

    /** 仅专用治理库 validate，迁移仍由独立受控 CLI 执行。 */
    @Bean(destroyMethod = "close") GovernanceRuntime governanceRuntime(Settings settings) {
        return GovernanceRuntime.open(settings.database(), false);
    }

    /** 身份解析没有图写入、应用准入或业务 ALLOW 副作用。 */
    @Bean InternalContextService governanceContext(Settings settings, GovernanceRuntime runtime) {
        return new InternalContextService(settings.callers(), runtime.identity());
    }

    record Settings(GovernanceDatabase database, List<CallerService> callers) {}
}
