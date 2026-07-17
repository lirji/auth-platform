package com.lrj.authz.server;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import com.lrj.authz.server.AuthzDtos.CheckBulkRequest;
import com.lrj.authz.server.AuthzDtos.CheckRequest;
import com.lrj.authz.server.AuthzDtos.ConsistencyDto;
import com.lrj.authz.server.AuthzDtos.DeleteRequest;
import com.lrj.authz.server.AuthzDtos.ExpandRequest;
import com.lrj.authz.server.AuthzDtos.LookupResourcesRequest;
import com.lrj.authz.server.AuthzDtos.LookupSubjectsRequest;
import com.lrj.authz.server.AuthzDtos.WriteRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthzControllerFacadeTest {

    private static final SubjectRef SUBJECT = SubjectRef.user("u1");
    private static final ResourceRef D1 = ResourceRef.of("document", "d1");
    private static final ResourceRef D2 = ResourceRef.of("document", "d2");

    @Test
    void checkMapsConsistencyModesAndArguments() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(true);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.check(new CheckRequest(SUBJECT, "view", D1, null)).allowed()).isTrue();
        controller.check(new CheckRequest(SUBJECT, "view", D1, new ConsistencyDto("FULLY_CONSISTENT", null)));
        controller.check(new CheckRequest(SUBJECT, "view", D1, new ConsistencyDto("unknown", "ignored")));

        ArgumentCaptor<Consistency> consistency = ArgumentCaptor.forClass(Consistency.class);
        verify(engine, times(3)).check(any(), any(), any(), consistency.capture());
        assertThat(consistency.getAllValues()).containsExactly(
                Consistency.minimizeLatency(), Consistency.fullyConsistent(), Consistency.minimizeLatency());
        verify(engine, times(2)).check(SUBJECT, "view", D1, Consistency.minimizeLatency());
        // 锁定"当前行为"：null/空 mode 与未知 mode 均落到 minimize_latency（见 toConsistency 的 default 分支）。
        // TODO(issue-consistency-downgrade): 未知/拼错的 consistency mode 被静默降级为 minimize_latency，
        //   可能弱化调用方要求的一致性（如把 full 拼错反而读到陈旧快照）。宜改为拒绝未知 mode 或回退 full；
        //   修复后本断言的第 3 个值应改为 fullyConsistent 或抛异常。
    }

    @Test
    void bulkPreservesRequestOrder() {
        AuthzEngine engine = mock(AuthzEngine.class);
        LinkedHashMap<ResourceRef, Boolean> map = new LinkedHashMap<>();
        map.put(D2, false);
        map.put(D1, true);
        when(engine.checkBulk(SUBJECT, "view", List.of(D1, D2), Consistency.fullyConsistent())).thenReturn(map);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        var response = controller.checkBulk(new CheckBulkRequest(
                SUBJECT, "view", List.of(D1, D2), new ConsistencyDto("full", null)));

        assertThat(response.results()).extracting(r -> r.resource()).containsExactly(D1, D2);
        assertThat(response.results()).extracting(r -> r.allowed()).containsExactly(true, false);
    }

    @Test
    void bulkThrowsWhenEngineOmitsRequestedResource() {
        // S01 已修：引擎 map 缺某个请求资源不再被静默降级成 allowed=false，而是抛协议异常（SDK 层据此 fail-closed）。
        AuthzEngine engine = mock(AuthzEngine.class);
        LinkedHashMap<ResourceRef, Boolean> partial = new LinkedHashMap<>();
        partial.put(D1, true); // 缺 D2
        when(engine.checkBulk(SUBJECT, "view", List.of(D1, D2), Consistency.minimizeLatency())).thenReturn(partial);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThatThrownBy(() -> controller.checkBulk(new CheckBulkRequest(SUBJECT, "view", List.of(D1, D2), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document:d2");
    }

    @Test
    void delegatesLookupMethodsWithFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.lookupResources(SUBJECT, "view", "document", Consistency.fullyConsistent()))
                .thenReturn(List.of("d1", "d2"));
        when(engine.lookupSubjects(D1, "view", "user", Consistency.fullyConsistent()))
                .thenReturn(List.of(SubjectRef.user("u2")));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.lookupResources(new LookupResourcesRequest(
                SUBJECT, "view", "document", new ConsistencyDto("full", null))).resourceIds())
                .containsExactly("d1", "d2");
        assertThat(controller.lookupSubjects(new LookupSubjectsRequest(
                D1, "view", "user", new ConsistencyDto("full", null))).subjects())
                .containsExactly(SubjectRef.user("u2"));
    }

    @Test
    void writeAndDeleteAdvanceWatermark() {
        AuthzEngine engine = mock(AuthzEngine.class);
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        RelationshipUpdate update = RelationshipUpdate.touch(D1, "viewer", SUBJECT);
        RelationshipFilter filter = RelationshipFilter.ofResource(D1);
        when(engine.writeRelationships(List.of(update))).thenReturn(new ZedTokenView("write-token"));
        when(engine.deleteRelationships(filter)).thenReturn(new ZedTokenView("delete-token"));
        AuthzController controller = new AuthzController(engine, watermark, true);

        assertThat(controller.write(new WriteRequest(List.of(update))).token()).isEqualTo("write-token");
        assertThat(watermark.latest()).isEqualTo("write-token");
        assertThat(controller.delete(new DeleteRequest(filter)).token()).isEqualTo("delete-token");
        assertThat(watermark.latest()).isEqualTo("delete-token");
    }

    @Test
    void schemaExpandAndReadRelationshipsDelegate() {
        AuthzEngine engine = mock(AuthzEngine.class);
        RelationshipFilter filter = RelationshipFilter.ofResource(D1);
        Relationship relationship = new Relationship(D1, "viewer", SUBJECT);
        when(engine.readSchema()).thenReturn("definition user {}");
        when(engine.expand(D1, "view", Consistency.fullyConsistent())).thenReturn("{\"treeRoot\":{}}");
        when(engine.readRelationships(filter)).thenReturn(List.of(relationship));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.schema()).containsEntry("schema", "definition user {}");
        assertThat(controller.expand(new ExpandRequest(D1, "view", new ConsistencyDto("full", null)))
                .has("treeRoot")).isTrue();
        assertThat(controller.readRelationships(new DeleteRequest(filter)).relationships())
                .containsExactly(relationship);
    }

    @Test
    void expandRejectsMalformedEngineJson() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.expand(any(), any(), any())).thenReturn("not-json");
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                controller.expand(new ExpandRequest(D1, "view", null)));
        assertThat(ex).hasMessageContaining("expand");
    }
}
