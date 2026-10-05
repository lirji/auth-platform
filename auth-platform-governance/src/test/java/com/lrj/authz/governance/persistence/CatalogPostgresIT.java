package com.lrj.authz.governance.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实PostgreSQL证明清单并发唯一、审计原子和跨应用发布拒绝。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;

    @BeforeAll
    void open() throws Exception {
        Properties p = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) {
            p = GovernanceConfigurationFile.read(file);
        } else {
            p.setProperty("jdbc.url", System.getenv("GOVERNANCE_TEST_DB_URL"));
            p.setProperty("jdbc.username", System.getenv("GOVERNANCE_TEST_DB_USER"));
            p.setProperty("jdbc.password", System.getenv("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        assertThat(p.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(p);
        runtime = GovernanceRuntime.open(db, true);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
    }

    @AfterAll
    void close() {
        if (runtime != null) runtime.close();
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private BootstrapCommand person() {
        var c =
                new BootstrapCommand(
                        id(),
                        "catalog-test",
                        id(),
                        "tenant-" + id(),
                        id(),
                        "https://catalog.example",
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

    private VerifiedLogin login(BootstrapCommand p) {
        return new VerifiedLogin(p.issuer(), p.subject());
    }

    private String register(BootstrapCommand p) {
        String app = "app-" + id();
        runtime.catalog()
                .register(app, p.principalId(), "https://example.test", "catalog-test", id());
        return app;
    }

    private Manifest manifest(String app, long version) {
        return new Manifest(
                "1",
                app,
                version,
                List.of(new Capability(app + ".read", "store", Risk.NORMAL)),
                List.of(new Menu("stores", null, "/stores", List.of(app + ".read"))));
    }

    @Test
    void previewIsReadOnlyAndConcurrentPublishHasOneAudit() throws Exception {
        var p = person();
        String app = register(p);
        var m = manifest(app, 1);
        int auditBefore =
                jdbc.queryForObject(
                        "select count(*) from auth_governance.catalog_audit where application_id=?",
                        Integer.class,
                        app);
        assertThat(runtime.catalog().preview(login(p), m).added()).containsExactly(app + ".read");
        assertThat(runtime.catalog().current(login(p), app)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(auditBefore);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.role_version where tenant_id=?",
                                Integer.class,
                                p.tenantId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.access_grant where tenant_id=?",
                                Integer.class,
                                p.tenantId()))
                .isZero();
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<Preview> call = () -> runtime.catalog().publish(login(p), m, id());
            for (var result : executor.invokeAll(List.of(call, call, call)))
                assertThat(result.get().proposedVersion()).isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=? and operation='PUBLISH'",
                                Integer.class,
                                app))
                .isEqualTo(1);
        assertThat(runtime.catalog().current(login(p), app)).isEqualTo(m);
    }

    @Test
    void foreignOwnerAndNamespaceCannotOverwrite() {
        var owner = person();
        var other = person();
        String app = register(owner);
        assertThatThrownBy(() -> runtime.catalog().publish(login(other), manifest(app, 1), id()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .register(
                                                app,
                                                other.principalId(),
                                                "https://example.test",
                                                "catalog-test",
                                                id()))
                .hasMessage("BINDING_CONFLICT");
        assertThat(runtime.catalog().current(login(owner), app)).isNull();
    }

    @Test
    void immutableMeaningAndOldVersionAreRejected() {
        var p = person();
        String app = register(p);
        var first = manifest(app, 1);
        runtime.catalog().publish(login(p), first, id());
        var changed =
                new Manifest(
                        "1",
                        app,
                        2,
                        List.of(new Capability(app + ".read", "order", Risk.HIGH)),
                        List.of());
        assertThat(runtime.catalog().preview(login(p), changed).publishable()).isFalse();
        assertThat(runtime.catalog().preview(login(p), changed).violations())
                .extracting(CatalogViolation::code)
                .containsExactly(ViolationKind.CAPABILITY_CHANGED);
        assertThatThrownBy(() -> runtime.catalog().publish(login(p), changed, id()))
                .hasMessage("VERSION_CONFLICT");
        var next =
                new Manifest(
                        "1",
                        app,
                        2,
                        List.of(
                                new Capability(app + ".read", "store", Risk.NORMAL),
                                new Capability(app + ".write", "store", Risk.HIGH)),
                        first.menus());
        assertThat(runtime.catalog().publish(login(p), next, id()).added())
                .containsExactly(app + ".write");
        assertThatThrownBy(() -> runtime.catalog().publish(login(p), first, id()))
                .hasMessage("VERSION_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.application_manifest where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(2);
    }

    @Test
    void suspendedOwnerCannotPublish() {
        var p = person();
        String app = register(p);
        jdbc.update(
                "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                p.principalId());
        assertThatThrownBy(() -> runtime.catalog().publish(login(p), manifest(app, 1), id()))
                .hasMessage("MEMBERSHIP_UNAVAILABLE");
    }

    @Test
    void auditFailureRollsBackSnapshotAndPointer() {
        var p = person();
        String app = register(p);
        // 定向临时约束只拒绝本测试应用的发布，验证真实事务回滚后立刻移除本测试约束。
        String constraint = "test_catalog_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.catalog_audit add constraint "
                        + constraint
                        + " check (application_id <> '"
                        + app
                        + "' or operation <> 'PUBLISH')");
        try {
            assertThatThrownBy(() -> runtime.catalog().publish(login(p), named(app, 1, "门店"), id()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(runtime.catalog().current(login(p), app)).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.application_manifest where application_id=?",
                                    Integer.class,
                                    app))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.application_manifest_presentation where application_id=?",
                                    Integer.class,
                                    app))
                    .isZero();
        } finally {
            jdbc.execute("alter table auth_governance.catalog_audit drop constraint " + constraint);
        }
    }

    private Manifest named(String app, long version, String label) {
        var m = manifest(app, version);
        return new Manifest(
                "1",
                app,
                version,
                m.capabilities(),
                List.of(new Menu("stores", null, "/stores", List.of(app + ".read"), label, 0)));
    }

    @Test
    void displayPublicationIsImmutableAndDoesNotChangeLegacyManifestStorage() {
        var p = person();
        String app = register(p);
        var m = named(app, 1, "商家与门店");
        runtime.catalog().publish(login(p), m, id());
        runtime.catalog().publish(login(p), m, id());
        assertThat(runtime.catalog().current(login(p), app)).isEqualTo(m);
        assertThat(
                        jdbc.queryForObject(
                                "select manifest_json from auth_governance.application_manifest where application_id=?",
                                String.class,
                                app))
                .doesNotContain("label", "position");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.application_manifest_presentation where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=? and operation='PUBLISH'",
                                Integer.class,
                                app))
                .isEqualTo(1);
        assertThatThrownBy(() -> runtime.catalog().publish(login(p), named(app, 1, "改名"), id()))
                .hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(() -> runtime.catalog().publish(login(p), manifest(app, 1), id()))
                .hasMessage("VERSION_CONFLICT");
        var preview = runtime.catalog().preview(login(p), named(app, 1, "改名"));
        assertThat(preview.publishable()).isFalse();
        assertThat(preview.menuChanges().getFirst().before().label()).isEqualTo("商家与门店");
        assertThat(preview.menuChanges().getFirst().after().label()).isEqualTo("改名");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=? and operation='PUBLISH'",
                                Integer.class,
                                app))
                .isEqualTo(1);
        runtime.catalog().publish(login(p), named(app, 2, "商品与门店"), id());
        assertThat(runtime.catalog().current(login(p), app).menus().getFirst().label())
                .isEqualTo("商品与门店");
    }
}
