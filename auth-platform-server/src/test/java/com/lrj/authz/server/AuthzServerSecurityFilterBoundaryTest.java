package com.lrj.authz.server;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthzServerSecurityFilterBoundaryTest {

    private static AuthzServerSecurityProperties enabled() {
        AuthzServerSecurityProperties props = new AuthzServerSecurityProperties();
        props.setEnabled(true);
        props.setToken("correct-secret");
        return props;
    }

    @Test
    void unauthorizedResponseDoesNotInvokeChainOrEchoCredential() throws Exception {
        AuthzServerSecurityFilter filter = new AuthzServerSecurityFilter(enabled());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/check");
        request.addHeader("Authorization", "Bearer wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("unauthorized").doesNotContain("wrong-secret");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsWrongAuthorizationSchemesAndWhitespace() throws Exception {
        for (String header : new String[]{"Basic correct-secret", "bearer correct-secret", "Bearer  correct-secret", "Bearer "}) {
            AuthzServerSecurityFilter filter = new AuthzServerSecurityFilter(enabled());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/relationships");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).as(header).isEqualTo(401);
            verify(chain, never()).doFilter(request, response);
        }
    }

    // TODO(issue-SEC01): 确认 /v1 根路径是否属于保护面；当前会放行，不能先固化。
    // 编码路径/路径规范化需 servlet container IT；MockHttpServletRequest 不代表 Tomcat 最终 requestURI。
}
