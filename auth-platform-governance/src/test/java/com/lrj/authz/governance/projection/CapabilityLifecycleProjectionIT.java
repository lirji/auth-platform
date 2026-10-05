package com.lrj.authz.governance.projection;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.projection.domain.ProjectionModels.*;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.Change;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.State;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ProjectionGraph;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import com.lrj.authz.protocol.SubjectRef;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

/** 真实PG和既有CAS测试图；只写本轮新UUID分区，不写原业务Grant或图marker。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityLifecycleProjectionIT {
    private RoleMigrationFixture h;
    private SpiceDbProjectionGraph graph;
    private AuthzEngine observer;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        assertThat(p.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        graph =
                new SpiceDbProjectionGraph(
                        p.getProperty("graph.http"),
                        p.getProperty("graph.key"),
                        Duration.ofSeconds(3));
        observer =
                new SpiceDbAuthzEngine(
                        p.getProperty("graph.http"),
                        p.getProperty("graph.key"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3));
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private void project(F f) {
        project(h.runtime, f, graph);
    }

    private void project(GovernanceRuntime runtime, F f, ProjectionGraph target) {
        for (var kind : List.of(Kind.POLICY, Kind.DIRECTORY)) {
            Step result = null;
            long deadline = System.nanoTime() + 30_000_000_000L;
            for (int n = 0; n < 120 && System.nanoTime() < deadline; n++) {
                result = runtime.reliableProjector(target).step(f.partition(), kind, id());
                if (result == Step.READY || result == Step.RECOVERED) break;
                if (result != Step.BUSY && result != Step.APPLIED && result != Step.RETRY_WAIT)
                    break;
                // 宿主慢响应可能留下真实15秒租约；等待自然到期，不改SQL时间或抢夺租约。
                try {
                    Thread.sleep(250);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("投影等待中断", failure);
                }
            }
            assertThat(result).isIn(Step.READY, Step.RECOVERED);
        }
    }

    private boolean eligible(F f, String id) {
        return observer.check(
                SubjectRef.of(ProjectionGraph.MEMBER_TYPE, f.member().membershipId() + "_g1"),
                "eligible",
                ResourceRef.of(ProjectionGraph.GRANT_TYPE, id),
                Consistency.fullyConsistent());
    }

    private AccessContext context(F f) {
        var m =
                h.runtime
                        .identity()
                        .contextForLogin(
                                f.member().issuer(),
                                f.member().subject(),
                                f.partition().tenantId(),
                                null);
        return new AccessContext(
                m.principalId(),
                m.membershipId(),
                m.membershipGeneration(),
                m.membershipVersion(),
                m.principalVersion(),
                m.tenantId(),
                f.partition().applicationId(),
                "test",
                "mg13-test",
                "HUMAN",
                id());
    }

    private boolean allowed(F f, String cap, String store) {
        return h.runtime
                .reliableAuthorization(graph)
                .allowed(
                        context(f),
                        cap,
                        new Facts(
                                f.partition().tenantId(),
                                "store",
                                store,
                                1,
                                null,
                                null,
                                List.of(),
                                store,
                                null));
    }

    /** 双水位采用至少新鲜读取，短暂保守拒绝允许存在；有界轮询真实判权，不改数据或放宽断言。 */
    private boolean eventuallyAllowed(F f, String cap, String store) {
        long deadline = System.nanoTime() + 12_000_000_000L;
        do {
            if (allowed(f, cap, store)) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("真实判权验证中断", failure);
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Test
    void deprecatedExistingPendingSourceCompletesAndKeepsRealScopeAccess() {
        var f = h.fixture();
        var g = h.grant(f, true);
        String raw = h.scope(g.id());
        var before = h.current(f, g.id());
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                f.capabilities().getFirst(),
                                State.DEPRECATED,
                                0L,
                                "仅停止新增，不回收原授权",
                                id()));
        assertThatThrownBy(() -> h.grant(f, true)).hasMessage("CAPABILITY_DEPRECATED");
        project(f);
        assertThat(eligible(f, g.id())).isTrue();
        assertThat(eventuallyAllowed(f, f.capabilities().getFirst(), "S1")).isTrue();
        assertThat(allowed(f, f.capabilities().getFirst(), "S2")).isFalse();
        assertThat(h.scope(g.id())).isEqualTo(raw);
        var current = h.current(f, g.id());
        assertThat(current.validTo()).isEqualTo(before.validTo());
        assertThat(current.validFrom()).isEqualTo(before.validFrom());
        assertThat(current.sourceId()).isEqualTo(before.sourceId());
        h.runtime.access().revoke(f.login(), f.partition(), id(), g.id(), 1);
        assertThatThrownBy(() -> allowed(f, f.capabilities().getFirst(), "S1"))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        project(f);
        assertThat(allowed(f, f.capabilities().getFirst(), "S1")).isFalse();
        assertThat(eligible(f, g.id())).isFalse();
    }

    @Test
    void lifecycleRestoreCannotClearEmergencyStopOrReviveRevokedSource() {
        var f = h.fixture();
        var g = h.grant(f, true);
        project(f);
        assertThat(eventuallyAllowed(f, f.capabilities().getFirst(), "S1")).isTrue();
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                f.capabilities().getFirst(),
                                State.DEPRECATED,
                                0L,
                                "版本迁出",
                                id()));
        assertThat(eventuallyAllowed(f, f.capabilities().getFirst(), "S1")).isTrue();
        h.runtime
                .catalog()
                .changeCapability(
                        f.login(),
                        f.partition().applicationId(),
                        f.capabilities().getFirst(),
                        true,
                        0,
                        "独立紧急止损",
                        id());
        project(f);
        assertThat(allowed(f, f.capabilities().getFirst(), "S1")).isFalse();
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                f.capabilities().getFirst(),
                                State.ACTIVE,
                                1L,
                                "显式恢复新增",
                                id()));
        assertThat(allowed(f, f.capabilities().getFirst(), "S1")).isFalse();
        h.runtime.access().revoke(f.login(), f.partition(), id(), g.id(), 1);
        project(f);
        assertThat(eligible(f, g.id())).isFalse();
        assertThat(h.current(f, g.id()).state()).isEqualTo(GrantState.REVOKED);
    }

    @Test
    void migrationCannotGrantDeprecatedReplacementAfterRealOldRevocation() {
        var f = h.fixture();
        var g = h.grant(f, true);
        project(f);
        var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, g));
        task = h.advance(f, task, 0);
        project(f);
        task = h.advance(f, task, 0);
        assertThat(task.items().getFirst().state()).isEqualTo("READY_TO_GRANT");
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                f.capabilities().getFirst(),
                                State.DEPRECATED,
                                0L,
                                "迁移间隙停止新增",
                                id()));
        var fixed = task;
        assertThatThrownBy(() -> h.advance(f, fixed, 0)).hasMessage("CAPABILITY_DEPRECATED");
        assertThat(
                        h.runtime
                                .roleMigrationTasks()
                                .get(f.login(), f.partition(), task.id())
                                .items()
                                .getFirst()
                                .newGrant())
                .isNull();
        assertThat(h.current(f, g.id()).state()).isEqualTo(GrantState.REVOKED);
        assertThat(allowed(f, f.capabilities().getFirst(), "S1")).isFalse();
    }
}
