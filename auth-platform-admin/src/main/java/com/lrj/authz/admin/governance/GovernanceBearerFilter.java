package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.authentication.CasdoorAccessTokenVerifier;
import com.lrj.authz.governance.application.IdentityGovernance;
import com.lrj.authz.governance.web.GovernanceWeb;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 新治理前缀统一认证；不注册全局 Filter Bean，避免影响旧 JWT 链。 */
final class GovernanceBearerFilter extends OncePerRequestFilter {
    private final CasdoorAccessTokenVerifier tokens;
    private final IdentityGovernance identities;

    /** 发行方与绑定均由受控 Runtime 注入，JWT roles/isAdmin 不参与权限。 */
    GovernanceBearerFilter(CasdoorAccessTokenVerifier tokens, IdentityGovernance identities) {
        this.tokens = tokens; this.identities = identities;
    }

    /** SecurityContext 只存已验证 issuer/sub，当前身份不可用时拒绝，结束后清理线程。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var login = tokens.verify(GovernanceWeb.bearer(GovernanceWeb.singleHeader(request.getHeaders("Authorization"))));
            identities.principalForLogin(login.issuer(), login.subject());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
            SecurityContextHolder.setContext(context);
        } catch (RuntimeException failure) {
            var error = GovernanceWeb.error(failure);
            response.setStatus(error.getStatusCode().value());
            response.setContentType("application/json");
            response.setHeader("X-Trace-Id", error.getHeaders().getFirst("X-Trace-Id"));
            response.getWriter().write(error.getBody().toString());
            SecurityContextHolder.clearContext();
            return;
        }
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
