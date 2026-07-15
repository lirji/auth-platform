package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GroupSyncService 差量同步单测：direct-tuple 对账、租户化 id、删除熔断、幂等。 */
class GroupSyncServiceTest {

    private static Relationship member(String groupId, SubjectRef subject) {
        return new Relationship(ResourceRef.of("group", groupId), "member", subject);
    }

    @Test
    void addsMissingAndRemovesExtra() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of("acme_g", Set.of("u1", "u2")));
        when(casdoor.groupNames()).thenReturn(Set.of("acme_g"));
        // 当前 direct 成员 = {u1, u3} → 应 +u2 -u3
        when(engine.readRelationships(any())).thenReturn(List.of(
                member("acme_g", SubjectRef.user("u1")),
                member("acme_g", SubjectRef.user("u3"))));

        GroupSyncService.SyncSummary summary = new GroupSyncService(casdoor, engine).sync();

        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.removed()).isEqualTo(1);
        List<RelationshipUpdate> updates = capture(engine);
        assertThat(updates).anySatisfy(u -> {
            assertThat(u.operation()).isEqualTo(RelationshipUpdate.Operation.TOUCH);
            assertThat(u.subject()).isEqualTo(SubjectRef.user("u2"));
            assertThat(u.resource()).isEqualTo(ResourceRef.of("group", "acme_g"));
        });
        assertThat(updates).anySatisfy(u -> {
            assertThat(u.operation()).isEqualTo(RelationshipUpdate.Operation.DELETE);
            assertThat(u.subject()).isEqualTo(SubjectRef.user("u3"));
        });
    }

    @Test
    void ignoresNestedGroupSubjects_whenComputingCurrent() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of()); // 期望态该组无成员
        when(casdoor.groupNames()).thenReturn(Set.of("acme_g"));
        // 当前含一个 direct user u1 + 一个嵌套 group 主体（group:eng#member）→ 只应删 u1，不碰嵌套组。
        when(engine.readRelationships(any())).thenReturn(List.of(
                member("acme_g", SubjectRef.user("u1")),
                member("acme_g", SubjectRef.ofRelation("group", "eng", "member"))));

        GroupSyncService.SyncSummary summary = new GroupSyncService(casdoor, engine).sync();

        assertThat(summary.removed()).as("只删 direct user，不删嵌套组主体").isEqualTo(1);
        List<RelationshipUpdate> updates = capture(engine);
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).operation()).isEqualTo(RelationshipUpdate.Operation.DELETE);
        assertThat(updates.get(0).subject()).isEqualTo(SubjectRef.user("u1"));
    }

    @Test
    void deleteThreshold_abortsWholeRun_writesNothing() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of()); // 期望全空 → 全删
        when(casdoor.groupNames()).thenReturn(Set.of("acme_g"));
        when(engine.readRelationships(any())).thenReturn(List.of(
                member("acme_g", SubjectRef.user("u1")),
                member("acme_g", SubjectRef.user("u2")),
                member("acme_g", SubjectRef.user("u3"))));

        // 阈值 2，将产生 3 个 DELETE → 中止
        GroupSyncService svc = new GroupSyncService(casdoor, engine, 2);

        assertThatThrownBy(svc::sync).isInstanceOf(IllegalStateException.class).hasMessageContaining("threshold");
        verify(engine, never()).writeRelationships(anyList());
    }

    @Test
    void idempotent_noWriteWhenDesiredEqualsCurrent() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of("acme_g", Set.of("u1")));
        when(casdoor.groupNames()).thenReturn(Set.of("acme_g"));
        when(engine.readRelationships(any())).thenReturn(List.of(member("acme_g", SubjectRef.user("u1"))));

        GroupSyncService.SyncSummary summary = new GroupSyncService(casdoor, engine).sync();

        assertThat(summary.added()).isZero();
        assertThat(summary.removed()).isZero();
        verify(engine, never()).writeRelationships(anyList());
    }

    @SuppressWarnings("unchecked")
    private static List<RelationshipUpdate> capture(AuthzEngine engine) {
        ArgumentCaptor<List<RelationshipUpdate>> cap = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(cap.capture());
        return cap.getValue();
    }
}
