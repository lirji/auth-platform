package com.lrj.authz.admin;

import com.lrj.authz.admin.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminExceptionHandlerTest {

    @Test
    void spiceDbDownBecomes503WithWorkspaceHint() {
        WorkspaceRegistry registry = mock(WorkspaceRegistry.class);
        when(registry.currentId()).thenReturn("risk");
        AdminExceptionHandler handler = new AdminExceptionHandler(registry);

        var response = handler.spiceDbUnreachable(new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:8545/v1/schema/read\": null"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).contains("risk").contains("8545").contains("知识库是 :8543");
    }
}
