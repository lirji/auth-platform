package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.authentication.CasdoorMachineTokens;
import com.lrj.authz.governance.web.GovernanceWeb;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 独立机器前缀只存VerifiedMachine；不赋予任何人类管理角色，也不保留原Token。 */
final class CatalogPublisherBearerFilter extends OncePerRequestFilter {
    private final CasdoorMachineTokens tokens;

    /** 固定客户端集合由私密配置注入，JWT只用于选择已登记验证器。 */
    CatalogPublisherBearerFilter(CasdoorMachineTokens tokens) {
        this.tokens = tokens;
    }

    /** 认证依赖失败统一脱敏；主库当前委派由实际用例在事务中验证。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var machine =
                    tokens.verify(
                            GovernanceWeb.bearer(
                                    GovernanceWeb.singleHeader(
                                            request.getHeaders("Authorization"))));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(machine, null, List.of()));
            SecurityContextHolder.setContext(context);
        } catch (RuntimeException failure) {
            reject(response, failure);
            SecurityContextHolder.clearContext();
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 关闭入口也直接返回本边界错误，不转发/error导致进入旧JWT链。 */
    static void reject(HttpServletResponse response, RuntimeException failure) throws IOException {
        var error = GovernanceWeb.error(failure);
        response.setStatus(error.getStatusCode().value());
        response.setContentType("application/json");
        response.setHeader("X-Trace-Id", error.getHeaders().getFirst("X-Trace-Id"));
        response.getWriter().write(error.getBody().toString());
    }
}
