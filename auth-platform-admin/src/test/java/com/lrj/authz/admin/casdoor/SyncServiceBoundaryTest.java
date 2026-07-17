package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceBoundaryTest {

    @SuppressWarnings("unchecked")
    private static List<RelationshipUpdate> updates(AuthzEngine engine) {
        ArgumentCaptor<List<RelationshipUpdate>> captor = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(captor.capture());
        return captor.getValue();
    }

    @Test
    void groupDeleteAtThresholdIsAllowedAndFilterIsScoped() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of());
        when(casdoor.groupNames()).thenReturn(Set.of("acme_eng"));
        when(engine.readRelationships(any())).thenReturn(List.of(new Relationship(
                ResourceRef.of("group", "acme_eng"), "member", SubjectRef.user("u1"))));

        GroupSyncService.SyncSummary result = new GroupSyncService(casdoor, engine, 1).sync();

        assertThat(result).isEqualTo(new GroupSyncService.SyncSummary(1, 0, 1));
        ArgumentCaptor<RelationshipFilter> filter = ArgumentCaptor.forClass(RelationshipFilter.class);
        verify(engine).readRelationships(filter.capture());
        assertThat(filter.getValue()).isEqualTo(RelationshipFilter.of("group", "acme_eng", "member"));
        assertThat(updates(engine)).containsExactly(RelationshipUpdate.delete(
                ResourceRef.of("group", "acme_eng"), "member", SubjectRef.user("u1")));
    }

    @Test
    void departmentParentReplacementIsAtomicInOneWrite() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.departmentSnapshot()).thenReturn(new CasdoorClient.DepartmentSnapshot(
                Set.of("acme_child"), Map.of(), Map.of("acme_child", "acme_new"), Map.of()));
        when(engine.readRelationships(any())).thenAnswer(invocation -> {
            RelationshipFilter filter = invocation.getArgument(0);
            if ("parent".equals(filter.relation())) {
                return List.of(new Relationship(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_old")));
            }
            return List.of();
        });

        DepartmentSyncService.SyncSummary result = new DepartmentSyncService(casdoor, engine, 10).sync();

        assertThat(result).isEqualTo(new DepartmentSyncService.SyncSummary(1, 1, 1));
        assertThat(updates(engine)).containsExactly(
                RelationshipUpdate.touch(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_new")),
                RelationshipUpdate.delete(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_old")));
        // 一次 writeRelationships 承载完整差量；不声称它与 Casdoor 快照读取构成事务。
    }

    // TODO(issue-CAS02): Casdoor 已删除实体当前不在遍历集合，无法写出“应清理旧 tuple”的通过测试。
}
