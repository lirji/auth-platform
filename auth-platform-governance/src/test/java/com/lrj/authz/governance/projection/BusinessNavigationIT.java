package com.lrj.authz.governance.projection;

import static org.assertj.core.api.Assertions.*;

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
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.NavigationDtos;
import com.lrj.authz.protocol.ProjectionGraph;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.StrictGraphReader;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真PG和专用SpiceDB证明范围存在性、撤权和整次查询拒绝；不修改原Commerce分区。 */
class BusinessNavigationIT {
    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void scopedUserSeesOnlyTheirMenusAndReadDoesNotIssueReferencesOrChangeFacts() {
        var db = database();
        var graph = graph();
        try (var runtime = GovernanceRuntime.open(db, true)) {
            var jdbc = jdbc(db);
            var fixture = fixture(runtime, graph);
            var before = facts(jdbc);
            var view =
                    runtime.navigation(graph)
                            .current(context(runtime, fixture.member(), fixture.partition()), id());
            assertThat(view.state()).isEqualTo("AVAILABLE");
            assertThat(view.capabilityHints())
                    .containsExactly(fixture.partition().applicationId() + ".read");
            assertThat(view.menus())
                    .extracting(NavigationDtos.Menu::code)
                    .containsExactly("root", "page.read", "page.collaboration");
            assertThat(view.menus().getFirst().route()).isNull();
            assertThat(view.menus().get(1).route()).isEqualTo("/operations/products");
            assertThat(view.menus().get(2).route()).isEqualTo("/collaboration/products");
            assertThat(view.manifestVersion()).isEqualTo(1);
            assertThat(view.contentHash()).hasSize(64);
            assertThat(view.presentationHash()).hasSize(64);
            assertThat(facts(jdbc)).isEqualTo(before);
            var noAccess =
                    runtime.navigation(graph)
                            .current(context(runtime, fixture.owner(), fixture.partition()), id());
            assertThat(noAccess.state()).isEqualTo("NO_ACCESS");
            assertThat(noAccess.menus()).isEmpty();
            runtime.access()
                    .revoke(fixture.login(), fixture.partition(), id(), fixture.grant().id(), 1);
            assertThatThrownBy(
                            () ->
                                    runtime.navigation(graph)
                                            .current(
                                                    context(
                                                            runtime,
                                                            fixture.member(),
                                                            fixture.partition()),
                                                    id()))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
            project(runtime, graph, fixture.partition());
            assertThat(
                            runtime.navigation(graph)
                                    .current(
                                            context(runtime, fixture.member(), fixture.partition()),
                                            id())
                                    .state())
                    .isEqualTo("NO_ACCESS");
            var stale = context(runtime, fixture.member(), fixture.partition());
            jdbc.update(
                    "update auth_governance.membership set version=version+1 where id=?",
                    fixture.member().membershipId());
            assertThatThrownBy(() -> runtime.navigation(graph).current(stale, id()))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
            jdbc.update(
                    "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                    fixture.member().principalId());
            assertThatThrownBy(() -> context(runtime, fixture.member(), fixture.partition()))
                    .hasMessage("MEMBERSHIP_UNAVAILABLE");
        }
    }

    @Test
    void publishingDuringGraphReadOrGraphFailureCannotReturnPartialMenus() {
        var db = database();
        var graph = graph();
        try (var runtime = GovernanceRuntime.open(db, true);
                var publisher = GovernanceRuntime.open(db, false)) {
            var fixture = fixture(runtime, graph);
            var current = context(runtime, fixture.member(), fixture.partition());
            var changed = new AtomicBoolean();
            StrictGraphReader concurrent =
                    (membership, generation, grants, token) -> {
                        var result = graph.checkEligible(membership, generation, grants, token);
                        if (changed.compareAndSet(false, true))
                            publisher
                                    .catalog()
                                    .publish(
                                            fixture.login(),
                                            manifest(fixture.partition().applicationId(), 2),
                                            id());
                        return result;
                    };
            assertThatThrownBy(() -> runtime.navigation(concurrent).current(current, id()))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
            // 新目录先推进策略栅栏，确认READY后才允许读取新版本，不能跳过投影阶段。
            project(runtime, graph, fixture.partition());
            assertThat(runtime.navigation(graph).current(current, id()).manifestVersion())
                    .isEqualTo(2);
            StrictGraphReader unavailable =
                    (membership, generation, grants, token) -> {
                        throw new ProjectionGraph.Failure(
                                ProjectionGraph.Failure.Code.DEPENDENCY_UNAVAILABLE);
                    };
            assertThatThrownBy(() -> runtime.navigation(unavailable).current(current, id()))
                    .hasMessage("DEPENDENCY_UNAVAILABLE");
            assertThatThrownBy(
                            () ->
                                    runtime.navigation(
                                                    (membership, generation, grants, token) ->
                                                            Map.of())
                                            .current(current, id()))
                    .hasMessage("AUTHZ_PROTOCOL_INVALID");
            var wrong =
                    new AccessContext(
                            current.principalId(),
                            current.membershipId(),
                            2,
                            current.membershipVersion(),
                            current.principalVersion(),
                            current.tenantId(),
                            current.applicationId(),
                            current.environment(),
                            current.callerServiceId(),
                            "HUMAN",
                            id());
            assertThatThrownBy(() -> runtime.navigation(graph).current(wrong, id()))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
        }
    }

    private record Fixture(
            BootstrapCommand owner,
            BootstrapCommand member,
            VerifiedLogin login,
            Partition partition,
            Grant grant) {}

    private static Fixture fixture(GovernanceRuntime runtime, SpiceDbProjectionGraph graph) {
        String tenant = id(), app = "nav-" + id(), code = "nav-" + id();
        var owner = person(runtime, tenant, code);
        var member = person(runtime, tenant, code);
        var login = new VerifiedLogin(owner.issuer(), owner.subject());
        runtime.catalog()
                .register(app, owner.principalId(), "https://navigation.example", "test", id());
        runtime.catalog().publish(login, manifest(app, 1), id());
        var p = new Partition(tenant, app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read", app + ".write")),
                                3600),
                        "nav-fixture",
                        id());
        var role = runtime.access().createRole(login, p, id(), "reader", 1, List.of(app + ".read"));
        runtime.access().enableStrict(login, p, id());
        var grant =
                runtime.access()
                        .grantScoped(
                                login,
                                p,
                                id(),
                                member.membershipId(),
                                1,
                                role.id(),
                                new Rule(
                                        1,
                                        "store",
                                        List.of(
                                                new Clause(
                                                        ScopeDtos.Kind.SPECIFIED_STORES,
                                                        List.of("S1"),
                                                        false))),
                                id(),
                                Instant.now().minusSeconds(1),
                                Instant.now().plusSeconds(600));
        project(runtime, graph, p);
        return new Fixture(owner, member, login, p, grant);
    }

    private static Manifest manifest(String app, long version) {
        return new Manifest(
                "1",
                app,
                version,
                List.of(
                        new Capability(app + ".read", "store", Risk.NORMAL),
                        new Capability(app + ".write", "store", Risk.HIGH)),
                List.of(
                        new Menu("root", null, "/admin", List.of(app + ".write"), "商品", 0),
                                new Menu(
                                        "page.read",
                                        "root",
                                        "/operations/products",
                                        List.of(app + ".read"),
                                        "商品档案",
                                        1),
                        new Menu(
                                        "page.write",
                                        "root",
                                        "/operations/private",
                                        List.of(app + ".write"),
                                        "管理",
                                        2),
                                new Menu(
                                        "page.collaboration",
                                        null,
                                        "/collaboration/products",
                                        List.of(app + ".read"),
                                        "门店协作",
                                        3)));
    }

    private static BootstrapCommand person(GovernanceRuntime runtime, String tenant, String code) {
        var value =
                new BootstrapCommand(
                        id(),
                        "navigation-test",
                        tenant,
                        code,
                        id(),
                        "https://navigation.example",
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "nav-fixture",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(value);
        return value;
    }

    private static AccessContext context(
            GovernanceRuntime runtime, BootstrapCommand member, Partition p) {
        var c =
                runtime.identity()
                        .contextForLogin(member.issuer(), member.subject(), p.tenantId(), 1L);
        return new AccessContext(
                c.principalId(),
                c.membershipId(),
                c.membershipGeneration(),
                c.membershipVersion(),
                c.principalVersion(),
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                "commerce-nav",
                "HUMAN",
                id());
    }

    private static GovernanceDatabase database() {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        assertThat(db.jdbcUrl()).contains("/auth_gov_p1_test_");
        return db;
    }

    private static SpiceDbProjectionGraph graph() {
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        return new SpiceDbProjectionGraph(
                p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(3));
    }

    private static JdbcTemplate jdbc(GovernanceDatabase db) {
        return new JdbcTemplate(
                new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
    }

    private static void project(
            GovernanceRuntime runtime, SpiceDbProjectionGraph graph, Partition p) {
        assertThat(
                        runtime.reliableProjector(graph)
                                .step(
                                        p,
                                        com.lrj.authz.governance.projection.domain.ProjectionModels
                                                .Kind.POLICY,
                                        id()))
                .isEqualTo(Step.READY);
        assertThat(
                        runtime.reliableProjector(graph)
                                .step(
                                        p,
                                        com.lrj.authz.governance.projection.domain.ProjectionModels
                                                .Kind.DIRECTORY,
                                        id()))
                .isEqualTo(Step.READY);
    }

    private static Map<String, List<String>> facts(JdbcTemplate jdbc) {
        var rows = new LinkedHashMap<String, List<String>>();
        for (String table :
                List.of(
                        "application_catalog",
                        "application_manifest",
                        "application_manifest_presentation",
                        "catalog_release",
                        "catalog_audit",
                        "catalog_release_preview",
                        "catalog_guard_policy",
                        "role_version",
                        "access_grant",
                        "access_delegation",
                        "grant_scope",
                        "execution_reference"))
            rows.put(
                    table,
                    jdbc.queryForList(
                            "select row_to_json(t)::text from auth_governance."
                                    + table
                                    + " t order by 1",
                            String.class));
        return rows;
    }
}
