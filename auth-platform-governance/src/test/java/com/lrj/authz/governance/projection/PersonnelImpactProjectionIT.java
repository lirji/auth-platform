package com.lrj.authz.governance.projection;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.ProjectionModels.Kind;
import com.lrj.authz.governance.domain.ProjectionModels.Step;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.*;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.DirectoryEvents.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.PersonnelImpactDtos.Report;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 真PG／CAS图和独立业务HTTP夹具；资源事实由服务端定义，不接受页面传来的ALLOW。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonnelImpactProjectionIT {
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

    private Report read(F f) {
        return h.runtime
                .portalPermissions(
                        List.of(
                                new PortalDiagnosticAuthority(
                                        f.partition(), f.owner().membershipId(), 1)))
                .personnelImpact(
                        f.login(),
                        f.partition(),
                        f.member().membershipId(),
                        null,
                        null,
                        h.runtime.personnelImpact());
    }

    private void project(F f) {
        for (var kind : List.of(Kind.POLICY, Kind.DIRECTORY)) {
            Step step = null;
            long deadline = System.nanoTime() + 30_000_000_000L;
            for (int n = 0; n < 120 && System.nanoTime() < deadline; n++) {
                step = h.runtime.reliableProjector(graph).step(f.partition(), kind, id());
                if (step == Step.READY || step == Step.RECOVERED) break;
                assertThat(step).isIn(Step.APPLIED, Step.BUSY, Step.RETRY_WAIT);
                pause(250);
            }
            assertThat(step).isIn(Step.READY, Step.RECOVERED);
        }
    }

    private AccessContext context(F f, Long generation) {
        var m =
                h.runtime
                        .identity()
                        .contextForLogin(
                                f.member().issuer(),
                                f.member().subject(),
                                f.partition().tenantId(),
                                generation);
        return new AccessContext(
                m.principalId(),
                m.membershipId(),
                m.membershipGeneration(),
                m.membershipVersion(),
                m.principalVersion(),
                m.tenantId(),
                f.partition().applicationId(),
                "test",
                "mg17-test",
                "HUMAN",
                id());
    }

    private boolean allowed(F f, String store) {
        return h.runtime
                .reliableAuthorization(graph)
                .allowed(
                        context(f, null),
                        f.capabilities().getFirst(),
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

    private boolean eventual(F f) {
        long end = System.nanoTime() + 12_000_000_000L;
        do {
            if (allowed(f, "S1")) return true;
            pause(100);
        } while (System.nanoTime() < end);
        return false;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("实际投影等待中断", e);
        }
    }

    private void employee(F f, G g, long sequence, long version, String status, String org) {
        var p =
                new Payload(
                        new Employee(
                                "1",
                                f.member().subject(),
                                status,
                                List.of(
                                        new Assignment(
                                                "1", org, "PRIMARY", false, "2020-01-01", null)),
                                List.of()),
                        null,
                        null);
        var a = g.authority();
        h.runtime
                .directory()
                .accept(
                        a,
                        new Event(
                                1,
                                id(),
                                a.source(),
                                "test",
                                a.sourceTenantRef(),
                                sequence,
                                DirectoryAggregateType.EMPLOYEE,
                                "1",
                                version,
                                Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
                                null,
                                p,
                                DirectoryEvents.payloadHash(DirectoryAggregateType.EMPLOYEE, p)));
    }

    @Test
    void moveDropsDynamicGroupButKeepsDirectAndOaUntilEachActualRevoke() throws Exception {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        var direct = h.grant(f, true);
        var group = h.groupGrant(f, g);
        var policy = h.oaPolicy(f, f.oldRole());
        var req = h.oaRequest(f, policy);
        h.decideOa(f, policy, req, "APPROVED");
        project(f);
        assertThat(eventual(f)).isTrue();
        assertThat(allowed(f, "S2")).isFalse();
        var oa =
                read(f).sources().stream()
                        .filter(s -> s.grant().sourceType().equals("OA_REQUEST"))
                        .findFirst()
                        .orElseThrow();
        employee(f, g, 4, 2, "ACTIVE", "2");
        project(f);
        assertThat(eventual(f)).isTrue();
        var current = read(f);
        assertThat(current.sources()).hasSize(3);
        assertThat(
                        current.sources().stream()
                                .filter(s -> s.grant().grantId().equals(group.id()))
                                .findFirst()
                                .orElseThrow()
                                .groupRelations())
                .allMatch(r -> !r.current());
        h.runtime.access().strictRevoke(f.login(), f.partition(), id(), direct.id(), 1);
        project(f);
        assertThat(eventual(f)).isTrue();
        assertThat(
                        read(f).sources().stream()
                                .filter(s -> s.grant().grantId().equals(direct.id()))
                                .findFirst()
                                .orElseThrow()
                                .revocationReceipt()
                                .status())
                .isEqualTo("COMPLETED");
        h.runtime.access().strictRevoke(f.login(), f.partition(), id(), oa.grant().grantId(), 1);
        project(f);
        assertThat(allowed(f, "S1")).isFalse();
        assertThat(
                        read(f).sources().stream()
                                .filter(s -> s.grant().grantId().equals(group.id()))
                                .findFirst()
                                .orElseThrow()
                                .grant()
                                .grantState())
                .isEqualTo("ACTIVE");
    }

    @Test
    void oldIdentityDeniedOnLeaveAndNewGenerationOnlyUsesCurrentExplicitGroup() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        h.grant(f, true);
        h.groupGrant(f, g);
        project(f);
        assertThat(eventual(f)).isTrue();
        var old = context(f, 1L);
        employee(f, g, 4, 2, "LEFT", "2");
        assertThatThrownBy(() -> context(f, 1L)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .reliableAuthorization(graph)
                                        .allowed(
                                                old,
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
                                                        null)))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        project(f);
        employee(f, g, 5, 3, "ACTIVE", "2");
        project(f);
        assertThat(context(f, null).membershipGeneration()).isEqualTo(2);
        assertThatThrownBy(() -> context(f, 1L)).hasMessage("GENERATION_MISMATCH");
        assertThat(allowed(f, "S1")).isFalse();
        assertThat(read(f).sources().stream().filter(s -> s.grant().sourceType().equals("DIRECT")))
                .allMatch(s -> !s.currentGeneration());
        employee(f, g, 6, 4, "ACTIVE", "1");
        project(f);
        assertThat(eventual(f)).isTrue();
        assertThat(
                        read(f).sources().stream()
                                .filter(s -> s.grant().sourceType().equals("GROUP"))
                                .findFirst()
                                .orElseThrow()
                                .groupRelations())
                .anyMatch(r -> r.generation() == 2 && r.current());
    }

    @Test
    void independentBusinessResourceFixtureAndGlobalSuspensionFailClosed() throws Exception {
        var f = h.fixture();
        h.directoryGroup(f);
        h.grant(f, true);
        project(f);
        assertThat(eventual(f)).isTrue();
        // 同一真实主体关联第二企业，全局暂停必须覆盖两边而不把成员记录伪装成LEFT。
        var baseFixture = h.fixture();
        var c =
                new BootstrapCommand(
                        id(),
                        "mg17",
                        baseFixture.partition().tenantId(),
                        baseFixture.owner().tenantCode(),
                        f.member().principalId(),
                        f.member().issuer(),
                        f.member().subject(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "mg17",
                        id(),
                        id());
        h.runtime.identity().bootstrapEmployee(c);
        var second =
                new F(
                        baseFixture.owner(),
                        c,
                        baseFixture.partition(),
                        baseFixture.oldRole(),
                        baseFixture.newRole(),
                        baseFixture.capabilities());
        h.grant(second, true);
        project(second);
        assertThat(eventual(second)).isTrue();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/stores",
                exchange -> {
                    String store =
                            exchange.getRequestURI().getPath().equals("/stores/S1") ? "S1" : "S2";
                    int status;
                    try {
                        status =
                                h.runtime
                                                .reliableAuthorization(graph)
                                                .allowed(
                                                        context(f, null),
                                                        f.capabilities().getFirst(),
                                                        new Facts(
                                                                f.partition().tenantId(),
                                                                "store",
                                                                store,
                                                                1,
                                                                null,
                                                                null,
                                                                List.of(),
                                                                store,
                                                                null))
                                        ? 200
                                        : 403;
                    } catch (GovernanceException denied) {
                        status =
                                denied.code() == GovernanceException.Code.MEMBERSHIP_UNAVAILABLE
                                        ? 403
                                        : 503;
                    }
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(
                            client.send(
                                            HttpRequest.newBuilder(URI.create(base + "/stores/S1"))
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(
                            client.send(
                                            HttpRequest.newBuilder(URI.create(base + "/stores/S2"))
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(403);
            h.runtime
                    .lifecycle()
                    .apply(
                            new LifecycleCommand(
                                    id(),
                                    "mg17",
                                    "GLOBAL",
                                    LifecycleCommand.Operation.SUSPEND_PRINCIPAL,
                                    f.member().principalId(),
                                    1,
                                    "全局停用"));
            assertThat(
                            client.send(
                                            HttpRequest.newBuilder(URI.create(base + "/stores/S1"))
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode())
                    .isEqualTo(403);
            assertThatThrownBy(() -> context(second, null)).hasMessage("MEMBERSHIP_UNAVAILABLE");
            assertThat(read(f).target().principalStatus()).isEqualTo("SUSPENDED");
            assertThat(read(f).target().status()).isEqualTo("ACTIVE");
            assertThat(read(second).target().principalStatus()).isEqualTo("SUSPENDED");
            assertThat(read(second).target().status()).isEqualTo("ACTIVE");
        } finally {
            server.stop(0);
        }
    }
}
