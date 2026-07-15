package com.lrj.authz.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthzServerSecurityFilter 单测（用 spring-test 的 Mock servlet，无需起容器）。
 * 覆盖：默认关放行、非 /v1 放行、缺/错 token 401、正确 token 放行。
 */
class AuthzServerSecurityFilterTest {

    private static AuthzServerSecurityProperties props(boolean enabled, String token) {
        AuthzServerSecurityProperties p = new AuthzServerSecurityProperties();
        p.setEnabled(enabled);
        p.setToken(token);
        return p;
    }

    private static MockHttpServletResponse run(AuthzServerSecurityProperties p, String uri, String authHeader)
            throws Exception {
        AuthzServerSecurityFilter filter = new AuthzServerSecurityFilter(p);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (authHeader != null) {
            req.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    @Test
    void disabled_passesThrough() throws Exception {
        assertThat(run(props(false, "secret"), "/v1/check", null).getStatus()).isEqualTo(200);
    }

    @Test
    void enabled_nonV1Path_passesThrough() throws Exception {
        assertThat(run(props(true, "secret"), "/actuator/health", null).getStatus()).isEqualTo(200);
    }

    @Test
    void enabled_missingToken_401() throws Exception {
        assertThat(run(props(true, "secret"), "/v1/check", null).getStatus()).isEqualTo(401);
    }

    @Test
    void enabled_wrongToken_401() throws Exception {
        assertThat(run(props(true, "secret"), "/v1/relationships", "Bearer nope").getStatus()).isEqualTo(401);
    }

    @Test
    void enabled_blankToken_failsFast() {
        // enabled=true 但 token 为空是配置错误：构造即抛（fail-fast），而非运行期所有请求 401。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuthzServerSecurityFilter(props(true, "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabled_correctToken_passes() throws Exception {
        assertThat(run(props(true, "secret"), "/v1/check", "Bearer secret").getStatus()).isEqualTo(200);
    }
}
