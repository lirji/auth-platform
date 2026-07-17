package com.lrj.authz.admin;

import com.lrj.authz.admin.AdminDtos.CheckRequest;
import com.lrj.authz.admin.AdminDtos.GrantRequest;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private static final GrantRequest USERSET = new GrantRequest(
            "document", "d1", "viewer", "group", "acme_eng", "member");

    @SuppressWarnings("unchecked")
    private static List<RelationshipUpdate> capturedWrite(AuthzEngine engine) {
        ArgumentCaptor<List<RelationshipUpdate>> captor = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(captor.capture());
        return captor.getValue();
    }

    @Test
    void grantWritesTouchAndAuditsIntentThenOk() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("name")).thenReturn("Alice Admin");
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-1"));

        var response = new AdminController(engine, audit).grant(USERSET, jwt);

        assertThat(response.token()).isEqualTo("zed-1");
        assertThat(capturedWrite(engine)).containsExactly(RelationshipUpdate.touch(
                ResourceRef.of("document", "d1"), "viewer",
                SubjectRef.ofRelation("group", "acme_eng", "member")));
        // ADM01 两段审计：intent 必在写之前落，ok 在写成功之后落，且不落 fail。
        String tuple = "document:d1#viewer@group:acme_eng#member";
        InOrder order = inOrder(audit, engine);
        order.verify(audit).record("Alice Admin", "grant.intent", tuple);
        order.verify(engine).writeRelationships(anyList());
        order.verify(audit).record("Alice Admin", "grant.ok", tuple);
        verify(audit, never()).record(anyString(), eq("grant.fail"), anyString());
    }

    @Test
    void revokeWritesDeleteAndFallsBackToJwtSubject() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("name")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("oidc-sub");
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-2"));
        GrantRequest direct = new GrantRequest("document", "d1", "owner", "user", "u1", " ");

        var response = new AdminController(engine, audit).revoke(direct, jwt);

        assertThat(response.token()).isEqualTo("zed-2");
        assertThat(capturedWrite(engine)).containsExactly(RelationshipUpdate.delete(
                ResourceRef.of("document", "d1"), "owner", SubjectRef.user("u1")));
        String tuple = "document:d1#owner@user:u1";
        InOrder order = inOrder(audit, engine);
        order.verify(audit).record("oidc-sub", "revoke.intent", tuple);
        order.verify(engine).writeRelationships(anyList());
        order.verify(audit).record("oidc-sub", "revoke.ok", tuple);
    }

    @Test
    void grantWriteFailureAuditsIntentAndFailButNotOk() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        when(engine.writeRelationships(anyList())).thenThrow(new IllegalStateException("SpiceDB down"));

        assertThrows(IllegalStateException.class, () -> new AdminController(engine, audit).grant(USERSET, null));

        // ADM01：写失败仍留痕——intent 在写前、fail 在写后，绝不落 ok（无"变更却无记录"，也无"未变更却记 ok"）。
        String tuple = "document:d1#viewer@group:acme_eng#member";
        InOrder order = inOrder(audit, engine);
        order.verify(audit).record("-", "grant.intent", tuple);
        order.verify(engine).writeRelationships(anyList());
        order.verify(audit).record("-", "grant.fail", tuple);
        verify(audit, never()).record(anyString(), eq("grant.ok"), anyString());
    }

    @Test
    void adminReadsAlwaysUseFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        ResourceRef resource = ResourceRef.of("document", "d1");
        when(engine.lookupSubjects(resource, "view", "user", Consistency.fullyConsistent()))
                .thenReturn(List.of(SubjectRef.user("u1")));
        when(engine.lookupResources(SubjectRef.of("user", "u1"), "view", "document", Consistency.fullyConsistent()))
                .thenReturn(List.of("d1"));

        AdminController controller = new AdminController(engine, audit);
        assertThat(controller.subjects("document", "d1", "view", "user").subjects())
                .extracting(s -> s.type(), s -> s.id())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("user", "u1"));
        assertThat(controller.resources("user", "u1", "view", "document").resourceIds())
                .containsExactly("d1");
    }

    @Test
    void revokeAbortsWriteWhenIntentAuditFails() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        // ADM01 fail-closed：intent 审计写失败（审计库不可用）时，绝不进行 SpiceDB 写（数据面零变更）。
        org.mockito.Mockito.doThrow(new IllegalStateException("audit db down"))
                .when(audit).record(anyString(), eq("revoke.intent"), anyString());
        GrantRequest direct = new GrantRequest("document", "d1", "owner", "user", "u1", null);

        assertThrows(IllegalStateException.class, () -> new AdminController(engine, audit).revoke(direct, null));

        verify(engine, never()).writeRelationships(anyList());
    }

    @Test
    void checkUsesFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(SubjectRef.of("user", "u1"), "edit", ResourceRef.of("document", "d1"),
                Consistency.fullyConsistent())).thenReturn(true);

        var result = new AdminController(engine, mock(AuditStore.class)).check(
                new CheckRequest("user", "u1", "edit", "document", "d1"));

        assertThat(result.allowed()).isTrue();
        verify(engine).check(SubjectRef.of("user", "u1"), "edit", ResourceRef.of("document", "d1"),
                Consistency.fullyConsistent());
    }

    @Test
    void checkDenyReturnsFalse() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(SubjectRef.of("user", "u1"), "edit", ResourceRef.of("document", "d1"),
                Consistency.fullyConsistent())).thenReturn(false);

        var result = new AdminController(engine, mock(AuditStore.class)).check(
                new CheckRequest("user", "u1", "edit", "document", "d1"));

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void schemaRelationshipsAndAuditDelegate() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        RelationshipFilter filter = RelationshipFilter.of("document", "d1", null);
        Relationship tuple = new Relationship(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1"));
        AuditStore.AuditRecord record = new AuditStore.AuditRecord("2026-07-17T00:00:00Z", "a", "grant", "x");
        when(engine.readSchema()).thenReturn("definition user {}");
        when(engine.readRelationships(filter)).thenReturn(List.of(tuple));
        when(audit.recent(7)).thenReturn(List.of(record));
        AdminController controller = new AdminController(engine, audit);

        assertThat(controller.schema()).containsEntry("schema", "definition user {}");
        assertThat(controller.relationships("document", "d1", null)).containsExactly(tuple);
        assertThat(controller.auditLog(7)).containsExactly(record);
        verify(engine).readRelationships(filter);
        verify(audit).recent(7);
    }

    @Test
    void expandParsesJsonAndRejectsMalformedTree() {
        AuthzEngine engine = mock(AuthzEngine.class);
        CheckRequest request = new CheckRequest("user", "ignored", "view", "document", "d1");
        when(engine.expand(ResourceRef.of("document", "d1"), "view", Consistency.fullyConsistent()))
                .thenReturn("{\"treeRoot\":{}}");
        AdminController controller = new AdminController(engine, mock(AuditStore.class));
        assertThat(controller.expand(request).has("treeRoot")).isTrue();

        when(engine.expand(ResourceRef.of("document", "d1"), "view", Consistency.fullyConsistent()))
                .thenReturn("broken");
        assertThrows(IllegalStateException.class, () -> controller.expand(request));
    }
}
