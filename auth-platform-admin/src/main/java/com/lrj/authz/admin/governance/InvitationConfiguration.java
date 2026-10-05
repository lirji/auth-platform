package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.authentication.CasdoorAccessTokenVerifier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/** 接受邀请是唯一允许尚未绑定身份的治理路径，显式开关与专用受众避免放宽其他入口。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "authz.governance",
        name = {"enabled", "invitations.enabled"},
        havingValue = "true")
public class InvitationConfiguration {
    /** 仅精确 POST 路径使用专用发行方配置；绑定授权由接受事务中的邀请证明完成。 */
    @Bean
    @Order(-1)
    SecurityFilterChain invitationSecurity(
            HttpSecurity http, GovernanceAdminConfiguration.Settings settings) throws Exception {
        var tokens = new CasdoorAccessTokenVerifier(settings.invitationAuthority().orElseThrow());
        http.securityMatcher(
                        new AntPathRequestMatcher("/api/governance/v1/invitations/accept", "POST"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new GovernanceBearerFilter(
                                tokens,
                                login -> {
                                    // 只证明登录；当前主体/成员或显式首次绑定在邀请事务内核验，不能采用 JWT roles。
                                }),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated());
        return http.build();
    }
}
