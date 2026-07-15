package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
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

/** {@link DepartmentSyncService} 差量同步单测：新建树写 member/parent/admin；删除熔断中止整轮。 */
class DepartmentSyncServiceTest {

    @Test
    void writesMemberParentAdmin_forNewTree() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        // 树: acme_ecom(parent=acme_platform, member=alice); acme_platform(admin=padmin)
        when(casdoor.departmentSnapshot()).thenReturn(new CasdoorClient.DepartmentSnapshot(
                Set.of("acme_ecom", "acme_platform"),
                Map.of("acme_ecom", Set.of("alice")),
                Map.of("acme_ecom", "acme_platform"),
                Map.of("acme_platform", Set.of("padmin"))));
        when(engine.readRelationships(any())).thenReturn(List.of());          // 当前态空
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("t"));

        DepartmentSyncService.SyncSummary summary = new DepartmentSyncService(casdoor, engine).sync();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelationshipUpdate>> cap = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(cap.capture());
        List<RelationshipUpdate> ups = cap.getValue();
        assertThat(ups).anyMatch(u -> u.resource().equals(ResourceRef.of("department", "acme_ecom"))
                && u.relation().equals("member") && u.subject().equals(SubjectRef.user("alice")));
        assertThat(ups).anyMatch(u -> u.resource().equals(ResourceRef.of("department", "acme_ecom"))
                && u.relation().equals("parent") && u.subject().equals(SubjectRef.of("department", "acme_platform")));
        assertThat(ups).anyMatch(u -> u.resource().equals(ResourceRef.of("department", "acme_platform"))
                && u.relation().equals("admin") && u.subject().equals(SubjectRef.user("padmin")));
        assertThat(summary.added()).isEqualTo(3);
        assertThat(summary.removed()).isZero();
    }

    @Test
    void deleteThreshold_abortsRound() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.departmentSnapshot()).thenReturn(new CasdoorClient.DepartmentSnapshot(
                Set.of("acme_ecom"), Map.of(), Map.of(), Map.of()));   // 期望态空
        // 当前态每次读回 2 个 user 主体（member+admin 两条 relation 各 2 → 4 DELETE），parent 读被 type 过滤为 0。
        when(engine.readRelationships(any())).thenReturn(List.of(
                new Relationship(ResourceRef.of("department", "acme_ecom"), "member", SubjectRef.user("a")),
                new Relationship(ResourceRef.of("department", "acme_ecom"), "member", SubjectRef.user("b"))));

        DepartmentSyncService svc = new DepartmentSyncService(casdoor, engine, 1);
        assertThatThrownBy(svc::sync).isInstanceOf(IllegalStateException.class);
        verify(engine, never()).writeRelationships(anyList());
    }
}
