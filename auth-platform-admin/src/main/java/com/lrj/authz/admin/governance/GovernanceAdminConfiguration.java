package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.GovernanceConfigurationFile;
import com.lrj.authz.governance.authentication.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.web.GovernanceWeb;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** 新管理入口默认关闭；专用治理 Runtime 不注册公共 DataSource 或接管旧 JWT 链。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
@Import(GovernanceWeb.Errors.class)
public class GovernanceAdminConfiguration {
    /** 私密配置一次性转为类型化快照，两个 Bean 不在不同时间反复读文件。 */
    @Bean Settings governanceAdminSettings(Environment environment) {
        var props = GovernanceConfigurationFile.read(environment.getProperty("authz.governance.configuration"));
        return new Settings(GovernanceDatabase.from(props), TokenAuthority.from(props));
    }

    /** HTTP 服务只 validate 已初始化迁移，不隐式成为 migration owner。 */
    @Bean(destroyMethod = "close") GovernanceRuntime governanceRuntime(Settings settings) {
        return GovernanceRuntime.open(settings.database(), false);
    }

    /** 认证只消费治理显式绑定，不采用旧 isAdmin/groups 作为治理管理权。 */
    @Bean CasdoorAccessTokenVerifier governanceTokens(Settings settings) {
        return new CasdoorAccessTokenVerifier(settings.authority());
    }

    /** 新前缀统一强制认证；未来新增路由同样不能绕过 Token 与当前主体绑定。 */
    @Bean @Order(0)
    SecurityFilterChain governanceSecurity(HttpSecurity http, CasdoorAccessTokenVerifier tokens, GovernanceRuntime runtime) throws Exception {
        http.securityMatcher("/api/governance/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new GovernanceBearerFilter(tokens, runtime.identity()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated());
        return http.build();
    }

    record Settings(GovernanceDatabase database, TokenAuthority authority) {}
}
