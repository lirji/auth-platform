package com.lrj.authz.governance.projection;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.access.application.AccessReviews;
import com.lrj.authz.governance.access.application.PortalDiagnosticAuthority;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.projection.domain.ProjectionModels.*;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.AccessReviewDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeDtos.Facts;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;

/** 真实授权图证明复核撤权确认与其他来源独立，不以SQL REVOKED或202冒充完成。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessReviewProjectionIT {
    private RoleMigrationFixture h;
    private SpiceDbProjectionGraph graph;

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

    private List<PortalDiagnosticAuthority> authorities(F f) {
        return List.of(new PortalDiagnosticAuthority(f.partition(), f.owner().membershipId(), 1));
    }

    private AccessReviews service(F f) {
        return h.runtime.accessReviews(authorities(f));
    }

    private void project(F f) {
        for (var kind : List.of(Kind.POLICY, Kind.DIRECTORY)) {
            Step step = null;
            long end = System.nanoTime() + 30_000_000_000L;
            for (int n = 0; n < 120 && System.nanoTime() < end; n++) {
                step = h.runtime.reliableProjector(graph).step(f.partition(), kind, id());
                if (step == Step.READY || step == Step.RECOVERED) break;
                assertThat(step).isIn(Step.APPLIED, Step.BUSY, Step.RETRY_WAIT);
                pause(250);
            }
            assertThat(step).isIn(Step.READY, Step.RECOVERED);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待真实图中断", e);
        }
    }

    private boolean allowed(F f) {
        var m =
                h.runtime
                        .identity()
                        .contextForLogin(
                                f.member().issuer(),
                                f.member().subject(),
                                f.partition().tenantId(),
                                null);
        var ctx =
                new AccessContext(
                        m.principalId(),
                        m.membershipId(),
                        m.membershipGeneration(),
                        m.membershipVersion(),
                        m.principalVersion(),
                        m.tenantId(),
                        f.partition().applicationId(),
                        "test",
                        "mg18-test",
                        "HUMAN",
                        id());
        return h.runtime
                .reliableAuthorization(graph)
                .allowed(
                        ctx,
                        f.capabilities().getFirst(),
                        new Facts(
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

    private boolean eventual(F f) {
        long end = System.nanoTime() + 12_000_000_000L;
        do {
            if (allowed(f)) return true;
            pause(100);
        } while (System.nanoTime() < end);
        return false;
    }

    private Detail create(F f, String... ids) {
        var p = f.partition();
        var report =
                h.runtime
                        .portalPermissions(authorities(f))
                        .personnelImpact(
                                f.login(),
                                p,
                                f.member().membershipId(),
                                null,
                                null,
                                h.runtime.personnelImpact());
        return service(f)
                .create(
                        f.login(),
                        new Create(
                                p.tenantId(),
                                p.applicationId(),
                                p.environment(),
                                id(),
                                f.member().membershipId(),
                                f.owner().membershipId(),
                                1,
                                report.basisHash(),
                                null,
                                List.of(ids),
                                "真图逐来源复核"));
    }

    private Detail decide(F f, Detail t, String grant, Decision decision) {
        var p = f.partition();
        var item =
                t.items().stream().filter(i -> i.grantId().equals(grant)).findFirst().orElseThrow();
        return service(f)
                .decide(
                        f.login(),
                        t.id(),
                        new Decide(
                                p.tenantId(),
                                p.applicationId(),
                                p.environment(),
                                id(),
                                t.version(),
                                item.id(),
                                item.version(),
                                t.currentReport().basisHash(),
                                decision,
                                "真实多来源复核",
                                false));
    }

    private Confirm confirmation(F f, Detail t, String grant) {
        var p = f.partition();
        var item =
                t.items().stream().filter(i -> i.grantId().equals(grant)).findFirst().orElseThrow();
        return new Confirm(
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                id(),
                t.version(),
                item.id(),
                item.version(),
                "核对真实图操作完成证明");
    }

    @Test
    void directAndOaRemainSeparateUntilEveryActualRevokeAndCompletion() throws Exception {
        var f = h.fixture();
        h.directoryGroup(f);
        var a = h.grant(f, true);
        var policy = h.oaPolicy(f, f.oldRole());
        var req = h.oaRequest(f, policy);
        h.decideOa(f, policy, req, "APPROVED");
        project(f);
        assertThat(eventual(f)).isTrue();
        var report =
                h.runtime
                        .portalPermissions(authorities(f))
                        .personnelImpact(
                                f.login(),
                                f.partition(),
                                f.member().membershipId(),
                                null,
                                null,
                                h.runtime.personnelImpact());
        String oa =
                report.sources().stream()
                        .filter(s -> s.grant().sourceType().equals("OA_REQUEST"))
                        .findFirst()
                        .orElseThrow()
                        .grant()
                        .grantId();
        var t = create(f, a.id(), oa);
        t = decide(f, t, a.id(), Decision.REVOKE);
        assertThat(t.state()).isEqualTo(TaskState.RUNNING);
        var pending = t;
        var before = confirmation(f, t, a.id());
        assertThatThrownBy(() -> service(f).confirm(f.login(), pending.id(), before))
                .hasMessage("VERSION_CONFLICT");
        project(f);
        assertThat(eventual(f)).isTrue();
        t = service(f).get(f.login(), f.partition(), t.id());
        t = service(f).confirm(f.login(), t.id(), confirmation(f, t, a.id()));
        assertThat(
                        t.items().stream()
                                .filter(i -> i.grantId().equals(a.id()))
                                .findFirst()
                                .orElseThrow()
                                .confirmedOperationId())
                .isNotNull();
        assertThat(t.currentReport().sources())
                .anyMatch(
                        s ->
                                s.grant().grantId().equals(oa)
                                        && s.grant().grantState().equals("ACTIVE"));
        t = decide(f, t, oa, Decision.KEEP);
        assertThat(t.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(eventual(f)).isTrue();
        var last = create(f, oa);
        last = decide(f, last, oa, Decision.REVOKE);
        project(f);
        assertThat(allowed(f)).isFalse();
        last = service(f).get(f.login(), f.partition(), last.id());
        last = service(f).confirm(f.login(), last.id(), confirmation(f, last, oa));
        assertThat(last.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(last.items().getFirst().state()).isEqualTo(ItemState.REVOKED);
    }

    @Test
    void cancelAndReopenedRuntimeConfirmOriginalIntentWithoutRestoringGrant() {
        var f = h.fixture();
        h.directoryGroup(f);
        var a = h.grant(f, true);
        var b = h.grant(f, true);
        project(f);
        assertThat(eventual(f)).isTrue();
        var t = create(f, a.id(), b.id());
        t = decide(f, t, a.id(), Decision.REVOKE);
        var p = f.partition();
        t =
                service(f)
                        .cancel(
                                f.login(),
                                t.id(),
                                new Cancel(
                                        p.tenantId(),
                                        p.applicationId(),
                                        p.environment(),
                                        id(),
                                        t.version(),
                                        "取消未处理，原撤权继续"));
        project(f);
        assertThat(eventual(f)).isTrue();
        try (var resumed = GovernanceRuntime.open(h.database, false)) {
            var service = resumed.accessReviews(authorities(f));
            var current = service.get(f.login(), p, t.id());
            var c = confirmation(f, current, a.id());
            var confirmed = service.confirm(f.login(), t.id(), c);
            assertThat(confirmed.state()).isEqualTo(TaskState.CANCELLED);
            assertThat(confirmed.items())
                    .extracting(Item::state)
                    .containsExactlyInAnyOrder(ItemState.REVOKED, ItemState.CANCELLED);
            assertThat(service.confirm(f.login(), t.id(), c).version())
                    .isEqualTo(confirmed.version());
        }
        assertThat(h.current(f, a.id()).state()).isEqualTo(GrantState.REVOKED);
        assertThat(h.current(f, b.id()).state()).isEqualTo(GrantState.ACTIVE);
        assertThat(eventual(f)).isTrue();
    }
}
