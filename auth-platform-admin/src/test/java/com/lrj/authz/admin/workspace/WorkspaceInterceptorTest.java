package com.lrj.authz.admin.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkspaceInterceptorTest {

    @Test
    void skipsWorkspaceCatalogAndCasdoorSync() throws Exception {
        WorkspaceRegistry registry = mock(WorkspaceRegistry.class);
        WorkspaceInterceptor interceptor = new WorkspaceInterceptor(registry);

        MockHttpServletRequest list = new MockHttpServletRequest("GET", "/admin/workspaces");
        list.setServletPath("/admin/workspaces");
        assertThat(interceptor.preHandle(list, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest sync = new MockHttpServletRequest("POST", "/admin/casdoor/sync");
        sync.setServletPath("/admin/casdoor/sync");
        assertThat(interceptor.preHandle(sync, new MockHttpServletResponse(), new Object())).isTrue();

        verify(registry, never()).bind(any(), any());
    }
}
