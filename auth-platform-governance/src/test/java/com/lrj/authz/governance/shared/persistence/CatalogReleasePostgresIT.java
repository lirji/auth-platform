package com.lrj.authz.governance.shared.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogReleaseModels.*;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实PostgreSQL证明清单并发唯一、审计原子和跨应用发布拒绝。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogReleasePostgresIT {
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

    private Candidate candidate(String app, long version, String label) {
        var m = manifest(app, version);
        return new Candidate(
                new Manifest(
                        "1",
                        app,
                        version,
                        m.capabilities(),
                        List.of(
                                new Menu(
                                        "stores",
                                        null,
                                        "/stores",
                                        List.of(app + ".read"),
                                        label,
                                        0))),
                new Source("a".repeat(40), "b".repeat(64)),
                "仅更新界面名称",
                Decision.KEEP_CURRENT_GRANTS);
    }

    @Test
    void originalReceiptSurvivesLaterPublicationAndChangedBodyConflicts() {
        var owner = person();
        String app = register(owner), command = id();
        var first = candidate(app, 1, "门店");
        var receipt = runtime.catalog().publishSource(login(owner), first, command);
        runtime.catalog().publishSource(login(owner), candidate(app, 2, "商家门店"), id());
        assertThat(runtime.catalog().publishSource(login(owner), first, command))
                .isEqualTo(receipt);
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .publishSource(
                                                login(owner),
                                                new Candidate(
                                                        first.manifest(),
                                                        new Source("c".repeat(40), "b".repeat(64)),
                                                        first.reason(),
                                                        first.decision()),
                                                command))
                .hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .publishSource(
                                                login(owner), candidate(app, 2, "商家门店"), id()))
                .hasMessage("VERSION_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_release where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=? and operation='PUBLISH'",
                                Integer.class,
                                app))
                .isEqualTo(2);
        var detail = runtime.catalog().releaseDetail(login(owner), app, 1);
        assertThat(detail.release()).isEqualTo(receipt);
        assertThat(detail.manifest()).isEqualTo(first.manifest());
        assertThat(
                        jdbc.queryForObject(
                                "select manifest_json from auth_governance.application_manifest where application_id=? and version=1",
                                String.class,
                                app))
                .isEqualTo(CatalogManifest.json(first.manifest()));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.access_grant where tenant_id=?",
                                Integer.class,
                                owner.tenantId()))
                .isZero();
    }

    @Test
    void legacyNewPublicationRecordsUnknownAndRepeatedOriginalCommandKeepsOldPreview() {
        var owner = person();
        String app = register(owner), cmd = id();
        var first = manifest(app, 1);
        var original = runtime.catalog().publish(login(owner), first, cmd);
        runtime.catalog().publish(login(owner), manifest(app, 2), id());
        assertThat(runtime.catalog().publish(login(owner), first, cmd)).isEqualTo(original);
        assertThat(runtime.catalog().history(login(owner), app, null).items())
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.source()).isNull());
        assertThatThrownBy(() -> runtime.catalog().publish(login(owner), manifest(app, 3), cmd))
                .hasMessage("COMMAND_CONFLICT");
    }

    @Test
    void historicalUnknownVersionsArePagedWithoutInventingSourceAndOtherOwnerDenied() {
        var owner = person();
        String app = register(owner);
        var rows = new ArrayList<Object[]>();
        for (int n = 1; n <= 120; n++) {
            var m = manifest(app, n);
            rows.add(
                    new Object[] {
                        app,
                        n,
                        CatalogManifest.hash(m),
                        CatalogManifest.json(m),
                        owner.principalId()
                    });
        }
        jdbc.batchUpdate(
                "insert into auth_governance.application_manifest(application_id,version,content_hash,manifest_json,published_by) values(?,?,?,?,?)",
                rows);
        jdbc.update(
                "update auth_governance.application_catalog set manifest_version=120 where application_id=?",
                app);
        var first = runtime.catalog().history(login(owner), app, null);
        assertThat(first.items()).hasSize(100);
        assertThat(first.nextBeforeVersion()).isEqualTo(21);
        assertThat(first.items())
                .allSatisfy(
                        r -> {
                            assertThat(r.source()).isNull();
                            assertThat(r.commandId()).isNull();
                            assertThat(r.preview()).isNull();
                        });
        var second = runtime.catalog().history(login(owner), app, first.nextBeforeVersion());
        assertThat(second.items()).hasSize(20);
        assertThat(second.nextBeforeVersion()).isNull();
        assertThat(runtime.catalog().releaseDetail(login(owner), app, 119).manifest())
                .isEqualTo(manifest(app, 119));
        var other = person();
        assertThatThrownBy(() -> runtime.catalog().history(login(other), app, null))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> runtime.catalog().releaseDetail(login(owner), app, 121))
                .hasMessage("NOT_FOUND");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_release where application_id=?",
                                Integer.class,
                                app))
                .isZero();
    }

    @Test
    void auditOrMetadataFailureRollsBackAllArtifactsAndHistoryIsImmutable() {
        var owner = person();
        String app = register(owner);
        for (String table : List.of("catalog_audit", "catalog_release")) {
            String constraint = "test_release_" + id().replace("-", "");
            // 仅隔离PG且标识为本测试UUID，临时约束不会拒绝其他夹具应用。
            jdbc.execute(
                    "alter table auth_governance."
                            + table
                            + " add constraint "
                            + constraint
                            + " check (application_id <> '"
                            + app
                            + "'"
                            + (table.equals("catalog_audit") ? " or operation <> 'PUBLISH'" : "")
                            + ")");
            try {
                assertThatThrownBy(
                                () ->
                                        runtime.catalog()
                                                .publishSource(
                                                        login(owner),
                                                        candidate(app, 1, "门店"),
                                                        id()))
                        .isInstanceOf(RuntimeException.class);
                assertThat(runtime.catalog().current(login(owner), app)).isNull();
                for (String artifact :
                        List.of(
                                "application_manifest",
                                "application_manifest_presentation",
                                "catalog_release"))
                    assertThat(
                                    jdbc.queryForObject(
                                            "select count(*) from auth_governance."
                                                    + artifact
                                                    + " where application_id=?",
                                            Integer.class,
                                            app))
                            .isZero();
            } finally {
                jdbc.execute(
                        "alter table auth_governance." + table + " drop constraint " + constraint);
            }
        }
        runtime.catalog().publishSource(login(owner), candidate(app, 1, "门店"), id());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.catalog_release set reason='伪造' where application_id=?",
                                        app))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from auth_governance.catalog_release where application_id=?",
                                        app))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentSameCommandPublishesOnceAndReturnsSameOriginalReceipt() throws Exception {
        var owner = person();
        String app = register(owner), command = id();
        var c = candidate(app, 1, "门店");
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<Release> call =
                    () -> runtime.catalog().publishSource(login(owner), c, command);
            var results = executor.invokeAll(List.of(call, call, call));
            var first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_release where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(1);
    }
}
