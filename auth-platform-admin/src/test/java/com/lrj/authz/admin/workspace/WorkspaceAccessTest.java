package com.lrj.authz.admin.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceAccessTest {

    private static Jwt jwt(String owner, List<String> groups) {
        return new Jwt("t", Instant.parse("2026-09-15T00:00:00Z"), Instant.parse("2026-09-15T01:00:00Z"),
                Map.of("alg", "none"), Map.of("sub", "u1", "owner", owner, "groups", groups));
    }

    private static WorkspaceProperties.Item workspace(String id, String org, List<String> extra) {
        WorkspaceProperties.Item item = new WorkspaceProperties.Item();
        item.setId(id);
        item.setOrganization(org);
        item.setOrganizations(extra);
        return item;
    }

    @Test
    void platformAdminEntersEveryWorkspace() {
        Jwt admin = jwt("built-in", List.of("built-in/authz-admin"));
        assertThat(WorkspaceAccess.allowed(admin, workspace("recsys", "recsys", List.of()))).isTrue();
        assertThat(WorkspaceAccess.allowed(admin, workspace("risk", "risk-platform", List.of()))).isTrue();
    }

    @Test
    void projectViewerOnlyEntersMatchingOrg() {
        Jwt viewer = jwt("recsys", List.of("recsys/authz-viewer"));
        assertThat(WorkspaceAccess.allowed(viewer, workspace("recsys", "recsys", List.of()))).isTrue();
        assertThat(WorkspaceAccess.allowed(viewer, workspace("knowledge", "built-in", List.of("acme")))).isFalse();
    }

    @Test
    void extraOrganizationsAllowLangchainTenantsOntoKnowledge() {
        Jwt alice = jwt("acme", List.of("acme/authz-viewer"));
        assertThat(WorkspaceAccess.allowed(alice, workspace("knowledge", "built-in", List.of("acme", "globex")))).isTrue();
        assertThat(WorkspaceAccess.allowed(alice, workspace("risk", "risk-platform", List.of()))).isFalse();
    }

    @Test
    void visibleFiltersToAllowedOnly() {
        Jwt viewer = jwt("risk-platform", List.of("risk-platform/authz-viewer"));
        List<WorkspaceProperties.Item> all = List.of(
                workspace("knowledge", "built-in", List.of()),
                workspace("risk", "risk-platform", List.of()));
        assertThat(WorkspaceAccess.visible(viewer, all)).extracting(WorkspaceProperties.Item::getId)
                .containsExactly("risk");
    }
}
