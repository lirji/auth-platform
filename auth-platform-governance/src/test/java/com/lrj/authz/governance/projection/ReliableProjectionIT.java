package com.lrj.authz.governance.projection;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.projection.domain.ProjectionModels.*;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ProjectionGraph;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;

/** 真PG+图验证租约与不可变批次；进程暂停/kill作为独立P3-04c验收。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableProjectionIT {
    private GovernanceRuntime runtime;
    private GovernanceRuntime second;
    private JdbcTemplate jdbc;
    private ProjectionGraph graph;
    private AuthzEngine observer;

    @BeforeAll
    void open() {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        assertThat(db.jdbcUrl())
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        runtime = GovernanceRuntime.open(db, true);
        second = GovernanceRuntime.open(db, false);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
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
        if (second != null) second.close();
        if (runtime != null) runtime.close();
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private BootstrapCommand person(String tenant, String code) {
        var c =
                new BootstrapCommand(
                        id(),
                        "projection-test",
                        tenant,
                        code,
                        id(),
                        "https://projection.example",
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "fixture",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(c);
        return c;
    }

    private Fixture fixture() {
        String tenant = id(), code = "t-" + id(), app = "app-" + id();
        var owner = person(tenant, code);
        var member = person(tenant, code);
        var login = new VerifiedLogin(owner.issuer(), owner.subject());
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        runtime.catalog()
                .publish(
                        login,
                        new Manifest(
                                "1",
                                app,
                                1,
                                List.of(new Capability(app + ".read", "store", Risk.NORMAL)),
                                List.of()),
                        id());
        var p = new Partition(tenant, app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read")),
                                3600),
                        "test",
                        id());
        var role = runtime.access().createRole(login, p, id(), "reader", 1, List.of(app + ".read"));
        runtime.access().enableStrict(login, p, id());
        return new Fixture(p, login, member, role);
    }

    private Grant grant(Fixture f) {
        return runtime.access()
                .grant(
                        f.login,
                        f.p,
                        id(),
                        f.member.membershipId(),
                        1,
                        f.role.id(),
                        "TENANT_ALL",
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(300));
    }

    private String fenceId(Fixture f) {
        return jdbc.queryForObject(
                "select id from auth_governance.policy_partition where tenant_id=?",
                String.class,
                f.p.tenantId());
    }

    private String state(Fixture f) {
        return jdbc.queryForObject(
                "select state from auth_governance.policy_partition where tenant_id=?",
                String.class,
                f.p.tenantId());
    }

    private boolean graphAllows(Fixture f, Grant grant) {
        return observer.check(
                SubjectRef.of(ProjectionGraph.MEMBER_TYPE, f.member.membershipId() + "_g1"),
                "eligible",
                ResourceRef.of(ProjectionGraph.GRANT_TYPE, grant.id()),
                Consistency.fullyConsistent());
    }

    private ProjectionGraph intercept(
            java.util.function.Consumer<String> before, java.util.function.Consumer<String> after) {
        return new ProjectionGraph() {
            public Optional<Marker> readMarker(String partition) {
                return graph.readMarker(partition);
            }

            public String compareAndWrite(
                    String partition,
                    String expected,
                    String next,
                    List<RelationshipUpdate> updates) {
                before.accept(partition);
                String token = graph.compareAndWrite(partition, expected, next, updates);
                after.accept(partition);
                return token;
            }
        };
    }

    @Test
    void allBatchesMustConfirmBeforeReadyAndSnapshotCannotBeChanged() {
        var f = fixture();
        for (int n = 0; n < 51; n++) grant(f);
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.APPLIED);
        assertThat(state(f)).isEqualTo("UPDATING");
        assertThat(second.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.READY);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.projection_receipt r join auth_governance.projection_operation o on o.id=r.operation_id where o.fence_id=?",
                                Integer.class,
                                fenceId(f)))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.projection_operation set payload_json='{}' where fence_id=?",
                                        fenceId(f)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.projection_operation set expected_marker=? where fence_id=?",
                                        id(),
                                        fenceId(f)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.DIRECTORY, id()))
                .isEqualTo(Step.READY);
    }

    @Test
    void validLeasePreventsAnotherWorkerFromPlanning() {
        var f = fixture();
        grant(f);
        var wrapped =
                intercept(
                        partition ->
                                assertThat(
                                                second.reliableProjector(graph)
                                                        .step(f.p, Kind.POLICY, id()))
                                        .isEqualTo(Step.BUSY),
                        partition -> {});
        assertThat(runtime.reliableProjector(wrapped).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.READY);
    }

    @Test
    void revokeDuringGraphWriteCannotPublishOldReady() {
        var f = fixture();
        var g = grant(f);
        var wrapped =
                intercept(
                        partition -> {},
                        partition -> runtime.access().revoke(f.login, f.p, id(), g.id(), 1));
        assertThat(runtime.reliableProjector(wrapped).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.APPLIED);
        assertThat(state(f)).isEqualTo("UPDATING");
        assertThat(graphAllows(f, g)).isTrue();
        assertThat(second.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.READY);
        assertThat(graphAllows(f, g)).isFalse();
        assertThat(runtime.access().state(f.login, f.p, null, null).grants().getFirst().state())
                .isEqualTo(GrantState.REVOKED);
    }

    @Test
    void expiredOldWorkerCannotWriteOrAcknowledgeAfterNewRevoke() {
        var f = fixture();
        var g = grant(f);
        String worker = id();
        var wrapped =
                intercept(
                        partition -> {
                            // 此片控制真实SQL时间资格；P3-04c另用实际进程暂停跨越真实租约时间。
                            jdbc.update(
                                    "update auth_governance.projection_stream set lease_until=clock_timestamp()-interval '1 second' where fence_id=?",
                                    partition);
                            runtime.access().revoke(f.login, f.p, id(), g.id(), 1);
                            assertThat(second.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                                    .isEqualTo(Step.READY);
                        },
                        partition -> {});
        assertThat(runtime.reliableProjector(wrapped).step(f.p, Kind.POLICY, worker))
                .isEqualTo(Step.BUSY);
        assertThat(state(f)).isEqualTo("READY");
        assertThat(graphAllows(f, g)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.projection_operation where fence_id=? and state='SUPERSEDED'",
                                Integer.class,
                                fenceId(f)))
                .isEqualTo(1);
    }

    @Test
    void sqlReceiptFailureRecoversKnownGraphCommitWithoutReplayingPayload() {
        var f = fixture();
        var g = grant(f);
        String worker = id(), constraint = "receipt_fail_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.projection_receipt add constraint "
                        + constraint
                        + " check (confirmed_by <> '"
                        + worker
                        + "')");
        try {
            assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, worker))
                    .isEqualTo(Step.RETRY_WAIT);
            assertThat(graphAllows(f, g)).isTrue();
            assertThat(state(f)).isEqualTo("UPDATING");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.projection_receipt r join auth_governance.projection_operation o on o.id=r.operation_id where o.fence_id=?",
                                    Integer.class,
                                    fenceId(f)))
                    .isZero();
        } finally {
            jdbc.execute(
                    "alter table auth_governance.projection_receipt drop constraint " + constraint);
        }
        jdbc.update(
                "update auth_governance.projection_stream set next_attempt_at=clock_timestamp() where fence_id=?",
                fenceId(f));
        var recovered =
                second.reliableProjector(
                        intercept(
                                partition -> {
                                    throw new AssertionError("已提交批次不应重写图");
                                },
                                partition -> {}));
        assertThat(recovered.step(f.p, Kind.POLICY, id())).isEqualTo(Step.RECOVERED);
        assertThat(state(f)).isEqualTo("READY");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.projection_operation where fence_id=?",
                                Integer.class,
                                fenceId(f)))
                .isEqualTo(1);
    }

    @Test
    void unknownRemoteMarkerBlocksWithoutDeletingIt() {
        var f = fixture();
        String fence = fenceId(f), unknown = id();
        graph.compareAndWrite(fence, null, unknown, List.of());
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.BLOCKED);
        assertThat(state(f)).isEqualTo("BLOCKED");
        assertThat(graph.readMarker(fence).orElseThrow().operationId()).isEqualTo(unknown);
    }

    /** 真实PG租约和图marker证明成功轮次隔开失败；不能将空闲分区的零星依赖故障累计成永久阻塞。 */
    @Test
    void successfulReadyMarkerReadResetsFailureBudgetForPolicyAndDirectory() {
        for (Kind kind : Kind.values()) {
            var f = fixture();
            grant(f);
            assertThat(runtime.reliableProjector(graph).step(f.p, kind, id()))
                    .isEqualTo(Step.READY);
            String fence = targetFence(f, kind);
            String marker = graph.readMarker(fence).orElseThrow().operationId();
            Integer operations =
                    jdbc.queryForObject(
                            "select count(*) from auth_governance.projection_operation where fence_id=?",
                            Integer.class,
                            fence);
            for (int cycle = 0; cycle < 6; cycle++) {
                assertThat(runtime.reliableProjector(unavailable()).step(f.p, kind, id()))
                        .isEqualTo(Step.RETRY_WAIT);
                assertThat(failures(fence)).isEqualTo(1);
                // 测试库提前结束真实退避资格，不用长sleep改变生产重试期限。
                jdbc.update(
                        "update auth_governance.projection_stream set next_attempt_at=clock_timestamp() where fence_id=?",
                        fence);
                assertThat(second.reliableProjector(graph).step(f.p, kind, id()))
                        .isEqualTo(Step.READY);
                assertThat(failures(fence)).isZero();
            }
            assertThat(graph.readMarker(fence).orElseThrow().operationId()).isEqualTo(marker);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.projection_operation where fence_id=?",
                                    Integer.class,
                                    fence))
                    .isEqualTo(operations);
        }
    }

    /** 成功预算重置不取消连续失败阈值；协议marker冲突仍由既有测试证明立即隔离。 */
    @Test
    void fiveConsecutiveMarkerReadFailuresStillBlock() {
        var f = fixture();
        grant(f);
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.READY);
        String fence = fenceId(f), marker = graph.readMarker(fence).orElseThrow().operationId();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(runtime.reliableProjector(unavailable()).step(f.p, Kind.POLICY, id()))
                    .isEqualTo(attempt < 5 ? Step.RETRY_WAIT : Step.BLOCKED);
            assertThat(failures(fence)).isEqualTo(attempt);
            jdbc.update(
                    "update auth_governance.projection_stream set next_attempt_at=clock_timestamp() where fence_id=?",
                    fence);
        }
        assertThat(state(f)).isEqualTo("BLOCKED");
        assertThat(graph.readMarker(fence).orElseThrow().operationId()).isEqualTo(marker);
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.BLOCKED);
    }

    /** 失去租约的旧执行器即使读到了正确marker，也不能清除新代际所持的失败预算。 */
    @Test
    void expiredLeaseCannotResetReadyFailureBudget() {
        var f = fixture();
        grant(f);
        String fence = fenceId(f);
        assertThat(runtime.reliableProjector(graph).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.READY);
        assertThat(runtime.reliableProjector(unavailable()).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.RETRY_WAIT);
        jdbc.update(
                "update auth_governance.projection_stream set next_attempt_at=clock_timestamp() where fence_id=?",
                fence);
        var expired =
                new ProjectionGraph() {
                    public Optional<Marker> readMarker(String partition) {
                        var result = graph.readMarker(partition);
                        jdbc.update(
                                "update auth_governance.projection_stream set lease_until=clock_timestamp()-interval '1 second' where fence_id=?",
                                partition);
                        return result;
                    }

                    public String compareAndWrite(
                            String partition,
                            String expected,
                            String next,
                            List<RelationshipUpdate> updates) {
                        throw new AssertionError("READY读取不能写图");
                    }
                };
        assertThat(runtime.reliableProjector(expired).step(f.p, Kind.POLICY, id()))
                .isEqualTo(Step.BUSY);
        assertThat(failures(fence)).isEqualTo(1);
    }

    /** 故障只注入远端读取，真实数据库事务和图中已有marker仍由集成组件承担。 */
    private ProjectionGraph unavailable() {
        return new ProjectionGraph() {
            public Optional<Marker> readMarker(String partition) {
                throw new ProjectionGraph.Failure(
                        ProjectionGraph.Failure.Code.DEPENDENCY_UNAVAILABLE);
            }

            public String compareAndWrite(
                    String partition,
                    String expected,
                    String next,
                    List<RelationshipUpdate> updates) {
                throw new AssertionError("不可用读取后不能写图");
            }
        };
    }

    private String targetFence(Fixture f, Kind kind) {
        return kind == Kind.POLICY
                ? fenceId(f)
                : jdbc.queryForObject(
                        "select id from auth_governance.directory_fence where tenant_id=?",
                        String.class,
                        f.p.tenantId());
    }

    private int failures(String fence) {
        return jdbc.queryForObject(
                "select failures from auth_governance.projection_stream where fence_id=?",
                Integer.class,
                fence);
    }

    private record Fixture(
            Partition p, VerifiedLogin login, BootstrapCommand member, RoleVersion role) {}
}
