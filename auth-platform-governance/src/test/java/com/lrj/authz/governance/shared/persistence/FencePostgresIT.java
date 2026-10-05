package com.lrj.authz.governance.shared.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** 真PG验证快照、原子版本及旧入口封锁；测试水位占位不证明真实图已确认。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FencePostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;

    @BeforeAll
    void open() {
        Properties p = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) p = GovernanceConfigurationFile.read(file);
        else {
            p.setProperty("jdbc.url", System.getenv("GOVERNANCE_TEST_DB_URL"));
            p.setProperty("jdbc.username", System.getenv("GOVERNANCE_TEST_DB_USER"));
            p.setProperty("jdbc.password", System.getenv("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        assertThat(p.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(p);
        runtime = GovernanceRuntime.open(db, true);
        source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = new JdbcTemplate(source);
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
                        "fence-test",
                        tenant,
                        code,
                        id(),
                        "https://fence.example",
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
        String tenant = id(), code = "tenant-" + id(), app = "app-" + id();
        var owner = person(tenant, code);
        var member = person(tenant, code);
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        var login = new VerifiedLogin(owner.issuer(), owner.subject());
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
        return new Fixture(p, owner, member, role);
    }

    private AccessContext context(Fixture f) {
        var c =
                runtime.identity()
                        .contextForLogin(
                                f.member.issuer(), f.member.subject(), f.p.tenantId(), null);
        return new AccessContext(
                c.principalId(),
                c.membershipId(),
                c.membershipGeneration(),
                c.membershipVersion(),
                c.principalVersion(),
                c.tenantId(),
                f.p.applicationId(),
                f.p.environment(),
                "test",
                "HUMAN",
                id());
    }

    private Grant grant(Fixture f) {
        return runtime.access()
                .grant(
                        new VerifiedLogin(f.owner.issuer(), f.owner.subject()),
                        f.p,
                        id(),
                        f.member.membershipId(),
                        1,
                        f.role.id(),
                        "TENANT_ALL",
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60));
    }

    private long policy(Fixture f) {
        return jdbc.queryForObject(
                "select desired_epoch from auth_governance.policy_partition where tenant_id=? and application_id=? and environment=?",
                Long.class,
                f.p.tenantId(),
                f.p.applicationId(),
                f.p.environment());
    }

    private long directory(Fixture f) {
        return jdbc.queryForObject(
                "select desired_epoch from auth_governance.directory_fence where tenant_id=?",
                Long.class,
                f.p.tenantId());
    }

    // 这里只验证数据库读栅栏，图确认必须在后续真实图IT中完成，不能复用本方法宣称ALLOW。
    private void readyFixture(Fixture f) {
        jdbc.update(
                "update auth_governance.policy_partition set applied_epoch=desired_epoch,state='READY',zed_token='test-policy-opaque' where tenant_id=?",
                f.p.tenantId());
        jdbc.update(
                "update auth_governance.directory_fence set applied_epoch=desired_epoch,state='READY',zed_token='test-directory-opaque' where tenant_id=?",
                f.p.tenantId());
    }

    @Test
    void newStrictPartitionIsNotReadyAndLegacyEntryPointsRefuse() {
        var f = fixture();
        var c = context(f);
        assertThatThrownBy(() -> runtime.readFence().snapshot(c, () -> true))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        assertThatThrownBy(
                        () ->
                                runtime.authorization(mock(AuthzEngine.class))
                                        .allowed(c, f.p.applicationId() + ".read", "store"))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        assertThatThrownBy(() -> runtime.projector(mock(AuthzEngine.class)).drain(f.p, 10))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.policy_partition set state='READY' where tenant_id=?",
                                        f.p.tenantId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void grantAndAuditRollbackKeepPolicyEpochAtomic() {
        var f = fixture();
        long before = policy(f);
        var g = grant(f);
        assertThat(policy(f)).isEqualTo(before + 1);
        runtime.access()
                .revoke(
                        new VerifiedLogin(f.owner.issuer(), f.owner.subject()),
                        f.p,
                        id(),
                        g.id(),
                        1);
        assertThat(policy(f)).isEqualTo(before + 2);
        String constraint = "fence_audit_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.audit_event add constraint "
                        + constraint
                        + " check (tenant_id <> '"
                        + f.p.tenantId()
                        + "' or operation <> 'CREATE_GRANT') NOT VALID");
        try {
            assertThatThrownBy(() -> grant(f)).isInstanceOf(RuntimeException.class);
            assertThat(policy(f)).isEqualTo(before + 2);
        } finally {
            jdbc.execute("alter table auth_governance.audit_event drop constraint " + constraint);
        }
    }

    @Test
    void secondReadUsesNewTransactionEvenUnderOuterRepeatableRead() {
        var f = fixture();
        readyFixture(f);
        var c = context(f);
        var fence = runtime.readFence();
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.setTimeout(5);
        outer.executeWithoutResult(
                status -> {
                    var a = fence.snapshot(c, () -> "A");
                    long old = policy(f); // 此查询固定外层事务的旧快照。
                    grant(f); // Runtime使用独立数据源事务，另一个连接提交新epoch。
                    assertThat(policy(f)).isEqualTo(old);
                    assertThatThrownBy(() -> fence.snapshot(c, () -> "C"))
                            .hasMessage("AUTHZ_STATE_NOT_READY");
                    assertThat(a.stamp().policyEpoch()).isEqualTo(old);
                });
    }

    @Test
    void primarySnapshotsDetectPolicyChangesEvenAfterReconfirmation() {
        var f = fixture();
        readyFixture(f);
        var c = context(f);
        var fence = runtime.readFence();
        var a = fence.snapshot(c, () -> "A");
        grant(f);
        readyFixture(f);
        var b = fence.snapshot(c, () -> "C");
        assertThatThrownBy(() -> fence.requireSame(a.stamp(), b.stamp()))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        fence.requireSame(b.stamp(), fence.snapshot(c, () -> "D").stamp());
    }

    @Test
    void memberPrincipalAdmissionAndCatalogChangesFenceExistingReads() {
        var f = fixture();
        readyFixture(f);
        var c = context(f);
        long d = directory(f);
        jdbc.update(
                "update auth_governance.membership set version=version+1 where id=?",
                f.member.membershipId());
        assertThat(directory(f)).isEqualTo(d + 1);
        readyFixture(f);
        assertThatThrownBy(() -> runtime.readFence().snapshot(c, () -> true))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        jdbc.update(
                "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                f.member.principalId());
        assertThat(directory(f)).isEqualTo(d + 2);
        long p = policy(f);
        jdbc.update(
                "update auth_governance.tenant_application set enabled=false where tenant_id=? and application_id=?",
                f.p.tenantId(),
                f.p.applicationId());
        assertThat(policy(f)).isEqualTo(p + 1);
        runtime.catalog()
                .publish(
                        new VerifiedLogin(f.owner.issuer(), f.owner.subject()),
                        new Manifest(
                                "1",
                                f.p.applicationId(),
                                2,
                                List.of(
                                        new Capability(
                                                f.p.applicationId() + ".read",
                                                "store",
                                                Risk.NORMAL),
                                        new Capability(
                                                f.p.applicationId() + ".write",
                                                "store",
                                                Risk.HIGH)),
                                List.of()),
                        id());
        assertThat(policy(f)).isEqualTo(p + 2);
    }

    @Test
    void ordinaryChangesCannotClearQuarantine() {
        var f = fixture();
        jdbc.update(
                "update auth_governance.policy_partition set state='BLOCKED' where tenant_id=?",
                f.p.tenantId());
        grant(f);
        assertThat(
                        jdbc.queryForObject(
                                "select state from auth_governance.policy_partition where tenant_id=?",
                                String.class,
                                f.p.tenantId()))
                .isEqualTo("BLOCKED");
    }

    private record Fixture(
            Partition p, BootstrapCommand owner, BootstrapCommand member, RoleVersion role) {}
}
