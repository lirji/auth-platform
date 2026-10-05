package com.lrj.authz.governance.graph;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;

/** 真实独立PG与SpiceDB验证提交窗口，不用Mock授权结果证明一致性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrantGraphIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    private AuthzEngine graph;
    private Properties graphConfig;

    @BeforeAll
    void open() {
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG"));
        assertThat(p.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(p);
        runtime = GovernanceRuntime.open(db, true);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
        graphConfig = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_GRAPH_CONFIG"));
        assertThat(graphConfig.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18543");
        graph =
                new SpiceDbAuthzEngine(
                        graphConfig.getProperty("graph.http"),
                        graphConfig.getProperty("graph.key"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3));
        assertThat(graph.readSchema())
                .contains("definition gov_grant", "definition gov_membership");
    }

    @AfterAll
    void close() {
        if (runtime != null) runtime.close();
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private BootstrapCommand person(String tenant, String code) {
        var c =
                new BootstrapCommand(
                        id(),
                        "graph-test",
                        tenant,
                        code,
                        id(),
                        "https://graph.example",
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

    private VerifiedLogin login(BootstrapCommand c) {
        return new VerifiedLogin(c.issuer(), c.subject());
    }

    private Fixture fixture() {
        String tenant = id(), code = "t-" + id(), app = "app-" + id();
        var owner = person(tenant, code);
        var member = person(tenant, code);
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        runtime.catalog()
                .publish(
                        login(owner),
                        new Manifest(
                                "1",
                                app,
                                1,
                                List.of(
                                        new Capability(app + ".read", "store", Risk.NORMAL),
                                        new Capability(app + ".write", "store", Risk.HIGH)),
                                List.of()),
                        id());
        Partition p = new Partition(tenant, app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read", app + ".write")),
                                3600),
                        "test",
                        id());
        var role =
                runtime.access()
                        .createRole(login(owner), p, id(), "reader", 1, List.of(app + ".read"));
        var grant =
                runtime.access()
                        .grant(
                                login(owner),
                                p,
                                id(),
                                member.membershipId(),
                                1,
                                role.id(),
                                "TENANT_ALL",
                                id(),
                                Instant.now().minusSeconds(2),
                                Instant.now().plusSeconds(1200));
        var context =
                new AccessContext(
                        member.principalId(),
                        member.membershipId(),
                        1,
                        1,
                        1,
                        tenant,
                        app,
                        "test",
                        "graph-test",
                        "HUMAN",
                        id());
        return new Fixture(owner, member, p, grant, context);
    }

    private boolean allowed(Fixture f) {
        return runtime.authorization(graph)
                .allowed(f.context, f.p.applicationId() + ".read", "store");
    }

    private boolean graphAllows(Fixture f) {
        return graph.check(
                SubjectRef.of("gov_membership", f.member.membershipId() + "_g1"),
                "eligible",
                ResourceRef.of("gov_grant", f.grant.id()),
                Consistency.fullyConsistent());
    }

    @Test
    void realGrantProjectionAllowsAndRevokeDeniesBeforeGraphCleanup() {
        var f = fixture();
        assertThatThrownBy(() -> allowed(f)).hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(runtime.projector(graph).drain(f.p, 50).completed()).isEqualTo(1);
        assertThat(allowed(f)).isTrue();
        assertThat(graphAllows(f)).isTrue();
        assertThat(
                        runtime.authorization(graph)
                                .allowed(f.context, f.p.applicationId() + ".write", "store"))
                .isFalse();
        runtime.access().revoke(login(f.owner), f.p, id(), f.grant.id(), 1);
        assertThat(graphAllows(f)).isTrue();
        assertThatThrownBy(() -> allowed(f)).hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isFalse();
        assertThat(graphAllows(f)).isFalse();
    }

    @Test
    void graphFailureKeepsPendingAndRecoversWithBoundedRetry() throws Exception {
        var f = fixture();
        var denied =
                new SpiceDbAuthzEngine(
                        graphConfig.getProperty("graph.http"),
                        "wrong-test-key",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        var failed = runtime.projector(denied).drain(f.p, 50);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.pending()).isEqualTo(1);
        assertThat(
                        runtime.access()
                                .state(login(f.owner), f.p, null, null)
                                .grants()
                                .getFirst()
                                .state())
                .isEqualTo(GrantState.PENDING);
        assertThatThrownBy(() -> allowed(f)).hasMessage("DEPENDENCY_UNAVAILABLE");
        Thread.sleep(2200);
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isTrue();
    }

    @Test
    void remoteSuccessWithoutSqlReceiptNeverBecomesActive() throws Exception {
        var f = fixture();
        String constraint = "graph_fail_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.access_grant add constraint "
                        + constraint
                        + " check (id <> '"
                        + f.grant.id()
                        + "' or state <> 'ACTIVE')");
        try {
            assertThat(runtime.projector(graph).drain(f.p, 50).failed()).isEqualTo(1);
            assertThat(graphAllows(f)).isTrue();
            assertThatThrownBy(() -> allowed(f)).hasMessage("DEPENDENCY_UNAVAILABLE");
        } finally {
            jdbc.execute("alter table auth_governance.access_grant drop constraint " + constraint);
        }
        Thread.sleep(2200);
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isTrue();
    }

    @Test
    void revokeDuringRealGraphWriteCannotReactivateOldVersion() {
        var f = fixture();
        AuthzEngine intercepted =
                (AuthzEngine)
                        Proxy.newProxyInstance(
                                AuthzEngine.class.getClassLoader(),
                                new Class<?>[] {AuthzEngine.class},
                                (proxy, method, args) -> {
                                    Object result = method.invoke(graph, args);
                                    if (method.getName().equals("writeRelationships"))
                                        runtime.access()
                                                .revoke(login(f.owner), f.p, id(), f.grant.id(), 1);
                                    return result;
                                });
        assertThat(runtime.projector(intercepted).drain(f.p, 50).pending()).isEqualTo(1);
        assertThat(
                        runtime.access()
                                .state(login(f.owner), f.p, null, null)
                                .grants()
                                .getFirst()
                                .state())
                .isEqualTo(GrantState.REVOKED);
        assertThatThrownBy(() -> allowed(f)).hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isFalse();
    }

    @Test
    void currentMembershipExpiryAndPartitionAreAuthoritative() {
        var f = fixture();
        runtime.projector(graph).drain(f.p, 50);
        assertThat(allowed(f)).isTrue();
        jdbc.update(
                "update auth_governance.access_grant set valid_from=clock_timestamp()-interval '2 minutes',valid_to=clock_timestamp()-interval '1 minute' where id=?",
                f.grant.id());
        assertThat(allowed(f)).isFalse();
        assertThat(graphAllows(f)).isTrue();
        var other = fixture();
        runtime.projector(graph).drain(other.p, 50);
        jdbc.update(
                "update auth_governance.membership set status='SUSPENDED',version=version+1 where id=?",
                other.member.membershipId());
        assertThat(allowed(other)).isFalse();
        var third = fixture();
        runtime.projector(graph).drain(third.p, 50);
        jdbc.update(
                "update auth_governance.tenant_application set enabled=false where tenant_id=?",
                third.p.tenantId());
        assertThat(allowed(third)).isFalse();
    }

    @Test
    void exhaustedProjectionRequiresAuditedManagerRetry() {
        var f = fixture();
        jdbc.update(
                "update auth_governance.grant_projection set attempts=5,last_error='DEPENDENCY_UNAVAILABLE' where grant_id=?",
                f.grant.id());
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                runtime.access()
                                        .retryProjection(
                                                login(f.member), f.p, id(), f.grant.id(), 1))
                .hasMessage("ACCESS_DENIED");
        String command = id();
        runtime.access().retryProjection(login(f.owner), f.p, command, f.grant.id(), 1);
        runtime.access().retryProjection(login(f.owner), f.p, command, f.grant.id(), 1);
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isTrue();
        assertThatThrownBy(
                        () ->
                                runtime.access()
                                        .retryProjection(
                                                login(f.owner), f.p, id(), f.grant.id(), 1))
                .hasMessage("VERSION_CONFLICT");
    }

    @Test
    void revokedGrantDiscardsExhaustedOldIntentWithoutReactivating() {
        var f = fixture();
        jdbc.update(
                "update auth_governance.grant_projection set attempts=5,last_error='DEPENDENCY_UNAVAILABLE' where grant_id=?",
                f.grant.id());
        runtime.access().revoke(login(f.owner), f.p, id(), f.grant.id(), 1);
        assertThat(runtime.projector(graph).drain(f.p, 50).pending()).isZero();
        assertThat(allowed(f)).isFalse();
        assertThat(graphAllows(f)).isFalse();
    }

    private record Fixture(
            BootstrapCommand owner,
            BootstrapCommand member,
            Partition p,
            Grant grant,
            AccessContext context) {}
}
