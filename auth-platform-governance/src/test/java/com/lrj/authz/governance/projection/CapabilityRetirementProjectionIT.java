package com.lrj.authz.governance.projection;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.support.*;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.*;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.State;
import com.lrj.authz.protocol.CapabilityRetirementDtos.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 真PG和真CAS图证明迁移间隙阻断及撤权收敛；运行证明使用明确签名契约夹具。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityRetirementProjectionIT {
    private static final long PROJECTION_BUDGET_NANOS = 30_000_000_000L;
    private RoleMigrationFixture h;
    private SpiceDbProjectionGraph graph;
    @TempDir Path temporary;

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
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private void project(F f) throws Exception {
        for (var kind : List.of(Kind.POLICY, Kind.DIRECTORY)) {
            long until = System.nanoTime() + PROJECTION_BUDGET_NANOS;
            Step step = null;
            for (int n = 0; n < 120 && System.nanoTime() < until; n++) {
                step = h.runtime.reliableProjector(graph).step(f.partition(), kind, id());
                if (step == Step.READY || step == Step.RECOVERED) break;
                if (step != Step.BUSY && step != Step.APPLIED && step != Step.RETRY_WAIT) break;
                Thread.sleep(250);
            }
            assertThat(step).isIn(Step.READY, Step.RECOVERED);
        }
    }

    private CapabilityRetirement none() {
        return h.runtime.retirement(new CatalogRetirementProof(List.of()));
    }

    private Report report(F f, String cap) {
        return none().report(f.login(), f.partition().applicationId(), cap);
    }

    private long blocking(Report r, String kind) {
        return r.counts().stream()
                .filter(c -> c.kind().equals(kind))
                .mapToLong(Count::blocking)
                .sum();
    }

    private boolean allowed(F f, String cap) {
        var m =
                h.runtime
                        .identity()
                        .contextForLogin(
                                f.member().issuer(),
                                f.member().subject(),
                                f.partition().tenantId(),
                                null);
        var context =
                new GovernanceDtos.AccessContext(
                        m.principalId(),
                        m.membershipId(),
                        m.membershipGeneration(),
                        m.membershipVersion(),
                        m.principalVersion(),
                        m.tenantId(),
                        f.partition().applicationId(),
                        "test",
                        "mg16-test",
                        "HUMAN",
                        id());
        return h.runtime
                .reliableAuthorization(graph)
                .allowed(
                        context,
                        cap,
                        new ScopeDtos.Facts(
                                f.partition().tenantId(),
                                "store",
                                "S1",
                                1,
                                null,
                                null,
                                List.of(),
                                "S1",
                                null));
    }

    /** 双水位至少新鲜读取允许短暂保守拒绝；沿用现有图验收预算，只轮询真实判权。 */
    private boolean eventuallyAllowed(F f, String cap) throws Exception {
        long deadline = System.nanoTime() + 12_000_000_000L;
        do {
            if (allowed(f, cap)) return true;
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Test
    void oldRevokeNewGrantGapStillBlocksUntilRealMigrationCompletes() throws Exception {
        var f = h.fixture();
        String cap = f.capabilities().getLast();
        var g = h.grant(f, true);
        project(f);
        assertThat(eventuallyAllowed(f, cap)).isTrue();
        var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, g));
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                cap,
                                State.DEPRECATED,
                                0L,
                                "先退出旧能力",
                                id()));
        task = h.advance(f, task, 0);
        project(f);
        task = h.advance(f, task, 0);
        assertThat(task.items().getFirst().state()).isEqualTo("READY_TO_GRANT");
        assertThat(allowed(f, cap)).isFalse();
        var gap = report(f, cap);
        assertThat(blocking(gap, "GRANT")).isZero();
        assertThat(blocking(gap, "MIGRATION")).isEqualTo(1);
        assertThat(gap.eligible()).isFalse();
        task = h.advance(f, task, 0);
        project(f);
        task = h.advance(f, task, 0);
        assertThat(task.state()).isEqualTo("COMPLETED");
        var exit = h.runtime.retirementExit();
        var hash =
                exit.inspect(
                        f.partition(),
                        RetirementReferenceExit.Kind.DELEGATION,
                        f.owner().membershipId());
        exit.exit(
                f.partition(),
                RetirementReferenceExit.Kind.DELEGATION,
                f.owner().membershipId(),
                hash,
                "mg16-ops",
                id(),
                "退出最后管理引用");
        // 停止委派同样使策略投影失效，必须等待真实图收敛，不能把减少意图当退出证明。
        assertThat(blocking(report(f, cap), "FENCE")).isEqualTo(1);
        project(f);
        var r = report(f, cap);
        assertThat(r.counts()).allMatch(c -> c.blocking() == 0);
        var proof = new RetirementProofFixture(temporary, f.partition().applicationId());
        proof.write(proof.payload(r));
        var service = h.runtime.retirement(proof.reader());
        r = service.report(f.login(), f.partition().applicationId(), cap);
        assertThat(r.eligible()).isTrue();
        assertThat(
                        service.retire(
                                        f.login(),
                                        new Retire(
                                                r.applicationId(),
                                                cap,
                                                r.lifecycleVersion(),
                                                r.basisHash(),
                                                "真撤权确认后退役",
                                                id()))
                                .current()
                                .state())
                .isEqualTo(State.RETIRED);
        project(f);
        assertThat(allowed(f, cap)).isFalse();
        assertThat(eventuallyAllowed(f, f.capabilities().getFirst())).isTrue();
        assertThat(
                        h.runtime
                                .catalog()
                                .current(f.login(), f.partition().applicationId())
                                .capabilities())
                .hasSize(2);
        var finished = task;
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.role_migration_task SELECT ?,tenant_id,application_id,environment,old_role_id,new_role_id,'RUNNING',1,created_by,created_generation,?,created_at,updated_at FROM auth_governance.role_migration_task WHERE id=?",
                                        id(),
                                        id(),
                                        finished.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void groupWithNoCurrentRecipientsStillRequiresFormalRevokeProof() throws Exception {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = h.groupGrant(f, group);
        project(f);
        h.groupEmployee(f, group, 4, 2, false, false);
        project(f);
        String cap = f.capabilities().getFirst();
        assertThat(allowed(f, cap)).isFalse();
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                cap,
                                State.DEPRECATED,
                                0L,
                                "组引用退出",
                                id()));
        assertThat(blocking(report(f, cap), "GRANT")).isEqualTo(1);
        h.runtime.access().revoke(f.login(), f.partition(), id(), grant.id(), grant.version());
        assertThat(blocking(report(f, cap), "RECEIPT")).isEqualTo(1);
        project(f);
        assertThat(blocking(report(f, cap), "GRANT")).isZero();
        assertThat(blocking(report(f, cap), "RECEIPT")).isZero();
        assertThat(allowed(f, cap)).isFalse();
    }
}
