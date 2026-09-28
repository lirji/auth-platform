package com.lrj.authz.governance.projection;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.governance.application.GovernanceConfigurationFile;
import com.lrj.authz.protocol.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真实图证明晚到旧payload、未知结果marker恢复与多marker隔离；进程kill由P3-04c另验。 */
class ProjectionCasIT {
    private ProjectionGraph graph;
    private AuthzEngine observer;
    @BeforeEach void open() {
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        assertThat(p.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        graph = new SpiceDbProjectionGraph(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(3));
        observer = new SpiceDbAuthzEngine(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
    private String id() { return UUID.randomUUID().toString(); }
    @Test void lateOldWriteCannotResurrectRevokedGrant() {
        String partition = id(), first = id(), revoke = id();
        var resource = ResourceRef.of(ProjectionGraph.GRANT_TYPE, id());
        var member = SubjectRef.of(ProjectionGraph.MEMBER_TYPE, id() + "_g1");
        var add = RelationshipUpdate.touch(resource, "assignee", member);
        assertThat(graph.readMarker(partition)).isEmpty();
        graph.compareAndWrite(partition, null, first, List.of(add));
        // 两个执行者持有同一旧marker；撤销先到，旧授予随后才抵达远端。
        graph.compareAndWrite(partition, first, revoke, List.of(RelationshipUpdate.delete(resource, "assignee", member)));
        assertThatThrownBy(() -> graph.compareAndWrite(partition, first, id(), List.of(add)))
                .isInstanceOf(ProjectionGraph.Failure.class).hasMessage("PRECONDITION_FAILED");
        assertThat(observer.check(member, "eligible", resource, Consistency.fullyConsistent())).isFalse();
        assertThat(graph.readMarker(partition).orElseThrow().operationId()).isEqualTo(revoke);
    }
    @Test void graphCommitCanBeIdentifiedAfterCallerLosesReceipt() {
        String partition = id(), operation = id();
        var resource = ResourceRef.of(ProjectionGraph.GRANT_TYPE, id());
        var member = SubjectRef.of(ProjectionGraph.MEMBER_TYPE, id() + "_g1");
        graph.compareAndWrite(partition, null, operation, List.of(RelationshipUpdate.touch(resource, "assignee", member)));
        // 不保存返回token，重建适配器后从真实图恢复确认水位。
        open(); var recovered = graph.readMarker(partition).orElseThrow();
        assertThat(recovered.operationId()).isEqualTo(operation);
        assertThat(recovered.readToken()).isNotBlank();
        assertThat(observer.check(member, "eligible", resource, Consistency.atLeastAsFresh(recovered.readToken()))).isTrue();
        assertThatThrownBy(() -> graph.compareAndWrite(partition, null, id(), List.of())).hasMessage("PRECONDITION_FAILED");
    }
    @Test void conflictingMarkersAreNotSilentlyRepaired() {
        String partition = id();
        var ref = ResourceRef.of(ProjectionGraph.PARTITION_TYPE, partition);
        observer.writeRelationships(List.of(
                RelationshipUpdate.touch(ref, ProjectionGraph.HEAD_RELATION, SubjectRef.of(ProjectionGraph.MARKER_TYPE, id())),
                RelationshipUpdate.touch(ref, ProjectionGraph.HEAD_RELATION, SubjectRef.of(ProjectionGraph.MARKER_TYPE, id()))));
        assertThatThrownBy(() -> graph.readMarker(partition)).hasMessage("MARKER_CONFLICT");
        assertThat(observer.readRelationships(new RelationshipFilter(ProjectionGraph.PARTITION_TYPE, partition, ProjectionGraph.HEAD_RELATION))).hasSize(2);
    }
    @Test void relationshipFailureDoesNotAdvanceMarker() {
        String partition = id(), first = id(); graph.compareAndWrite(partition, null, first, List.of());
        var invalid = new RelationshipUpdate(RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of(ProjectionGraph.GRANT_TYPE, id()), "unknown_relation", SubjectRef.of(ProjectionGraph.MEMBER_TYPE, id()));
        assertThatThrownBy(() -> graph.compareAndWrite(partition, first, id(), List.of(invalid))).isInstanceOf(ProjectionGraph.Failure.class);
        assertThat(graph.readMarker(partition).orElseThrow().operationId()).isEqualTo(first);
    }
}
