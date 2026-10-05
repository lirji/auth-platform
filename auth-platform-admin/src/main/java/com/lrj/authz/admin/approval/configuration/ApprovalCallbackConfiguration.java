package com.lrj.authz.admin.approval.configuration;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.approval.application.ApprovalInbox;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.ApprovalSignature;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 回调使用独立原文HMAC入口，不把OA服务凭据当成人员Token。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
public class ApprovalCallbackConfiguration {
    /** 密钥与精确分区读取同一个0600配置，未启用时不要求回调配置。 */
    @Bean
    CallbackSettings approvalCallbackSettings(Environment environment) {
        boolean enabled =
                environment.getProperty("authz.governance.requests.enabled", Boolean.class, false);
        if (!enabled) return new CallbackSettings(false, null, null, false);
        var p =
                GovernanceConfigurationFile.read(
                        environment.getProperty("authz.governance.configuration"));
        var partition =
                new Partition(
                        p.getProperty("approval.tenant"),
                        p.getProperty("approval.application"),
                        p.getProperty("approval.environment"));
        AccessValues.partition(partition);
        String key = p.getProperty("approval.inbound-key");
        ApprovalSignature.validateKey(key);
        return new CallbackSettings(
                true,
                partition,
                key,
                Boolean.parseBoolean(p.getProperty("approval.allow-loopback-http", "false")));
    }

    /** 此命名空间只允许一个固定POST端点；真正认证在Inbox.receive内验证签名与nonce。 */
    @Bean
    @Order(0)
    SecurityFilterChain approvalCallbackChain(HttpSecurity http, CallbackSettings settings)
            throws Exception {
        http.securityMatcher("/internal/oa-approval/**")
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAfter(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                if (!settings.enabled()) {
                                    response.setStatus(404);
                                    return;
                                }
                                if (!ApprovalInbox.PATH.equals(request.getRequestURI())
                                        || !"POST".equals(request.getMethod())
                                        || request.getQueryString() != null) {
                                    response.setStatus(404);
                                    return;
                                }
                                boolean local =
                                        settings.allowLoopback()
                                                && loopback(request.getRemoteAddr())
                                                && loopback(request.getLocalAddr());
                                if (!request.isSecure() && !local) {
                                    response.setStatus(403);
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        SecurityContextHolderFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    private static boolean loopback(String ip) {
        return Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(ip);
    }

    /** 避免record默认打印密钥。 */
    public record CallbackSettings(
            boolean enabled, Partition partition, String key, boolean allowLoopback) {
        @Override
        public String toString() {
            return "CallbackSettings[credentials=redacted]";
        }
    }
}
