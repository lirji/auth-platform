package com.lrj.authz.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /v1/**} 的最小 service-credential 关口（不引 spring-security，用 spring-web 自带的
 * {@link OncePerRequestFilter}）。{@link AuthzServerSecurityProperties#isEnabled()} 为 true 时校验
 * {@code Authorization: Bearer <token>}，不匹配返回 401；health/info 等非 /v1 路径放行。
 * 常量时间比较防时序侧信道。作为 {@link Component} 由 Spring Boot 自动注册进 servlet filter chain。
 */
@Component
public class AuthzServerSecurityFilter extends OncePerRequestFilter {

    private final AuthzServerSecurityProperties props;

    public AuthzServerSecurityFilter(AuthzServerSecurityProperties props) {
        // fail-fast：enabled=true 但 token 为空是配置错误，拒绝启动（而非运行期才所有请求 401）。
        if (props.isEnabled() && (props.getToken() == null || props.getToken().isBlank())) {
            throw new IllegalStateException(
                    "authz.server.security.enabled=true 但 token 为空：拒绝启动（生产须配置非空 token）");
        }
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (!props.isEnabled() || !req.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(req, resp);
            return;
        }
        String configured = props.getToken();
        String header = req.getHeader("Authorization");
        String presented = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
        if (configured == null || configured.isBlank()
                || presented == null || !constantTimeEquals(configured, presented)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"missing or invalid service credential\"}");
            return;
        }
        chain.doFilter(req, resp);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
