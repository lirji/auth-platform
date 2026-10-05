package com.lrj.authz.governance.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogDriftModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实PostgreSQL验证漂移核对只读、旧事实兼容、Owner及损坏依赖拒绝。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogDriftPostgresIT {
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

    private Source source() {
        return new Source("a".repeat(40), "b".repeat(64));
    }

    private Candidate candidate(String app, long version) {
        return new Candidate(manifest(app, version), source(), null, null);
    }

    @Test
    void fixedVersionContentDisplayAndSourceClassifiedWithoutPublishing() {
        var owner = person();
        String app = register(owner);
        var c = candidate(app, 1);
        var missing = runtime.catalog().drift(login(owner), c, List.of());
        assertThat(missing.state()).isEqualTo(State.NOT_PUBLISHED);
        assertThat(missing.diff()).isNull();
        runtime.catalog().publishSource(login(owner), c, id());
        var match = runtime.catalog().drift(login(owner), c, List.of());
        assertThat(match.state()).isEqualTo(State.MATCHED_DECLARATION);
        assertThat(match.runtimeState()).isEqualTo(State.UNKNOWN);
        var display =
                new Candidate(
                        new Manifest(
                                "1",
                                app,
                                1,
                                c.manifest().capabilities(),
                                List.of(
                                        new Menu(
                                                "stores",
                                                null,
                                                "/stores",
                                                List.of(app + ".read"),
                                                "门店管理",
                                                0))),
                        source(),
                        null,
                        null);
        var shown = runtime.catalog().drift(login(owner), display, List.of());
        assertThat(shown.state()).isEqualTo(State.DISPLAY_MISMATCH);
        assertThat(shown.diff().menuChanges()).hasSize(1);
        var route =
                new Candidate(
                        new Manifest(
                                "1",
                                app,
                                1,
                                c.manifest().capabilities(),
                                List.of(
                                        new Menu(
                                                "stores",
                                                null,
                                                "/new-stores",
                                                List.of(app + ".read")))),
                        source(),
                        null,
                        null);
        assertThat(runtime.catalog().drift(login(owner), route, List.of()).state())
                .isEqualTo(State.SOURCE_MISMATCH);
        assertThat(
                        runtime.catalog()
                                .drift(
                                        login(owner),
                                        new Candidate(c.manifest(), null, null, null),
                                        List.of())
                                .state())
                .isEqualTo(State.UNKNOWN);
        assertThat(
                        runtime.catalog()
                                .drift(
                                        login(owner),
                                        new Candidate(
                                                c.manifest(),
                                                new Source("c".repeat(40), source().artifactHash()),
                                                null,
                                                null),
                                        List.of())
                                .state())
                .isEqualTo(State.SOURCE_MISMATCH);
        runtime.catalog().publishSource(login(owner), candidate(app, 2), id());
        assertThat(runtime.catalog().drift(login(owner), c, List.of()).state())
                .isEqualTo(State.SOURCE_MISMATCH);
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(2);
    }

    @Test
    void olderUnknownHistoryStillHasActualEmptyPresentationWithoutBackfill() {
        var owner = person();
        String app = register(owner);
        var c = candidate(app, 1);
        jdbc.update(
                "insert into auth_governance.application_manifest(application_id,version,content_hash,manifest_json,published_by) values(?,1,?,?,?)",
                app,
                CatalogManifest.hash(c.manifest()),
                CatalogManifest.json(CatalogManifest.normalize(c.manifest())),
                owner.principalId());
        jdbc.update(
                "update auth_governance.application_catalog set manifest_version=1 where application_id=?",
                app);
        var unknown = runtime.catalog().drift(login(owner), c, List.of());
        assertThat(unknown.state()).isEqualTo(State.UNKNOWN);
        assertThat(unknown.published().presentationHash())
                .isEqualTo(CatalogManifest.presentationHash(c.manifest()));
        var display =
                new Candidate(
                        new Manifest(
                                "1",
                                app,
                                1,
                                c.manifest().capabilities(),
                                List.of(
                                        new Menu(
                                                "stores",
                                                null,
                                                "/stores",
                                                List.of(app + ".read"),
                                                "门店",
                                                0))),
                        source(),
                        null,
                        null);
        assertThat(runtime.catalog().drift(login(owner), display, List.of()).state())
                .isEqualTo(State.DISPLAY_MISMATCH);
        assertThat(
                        runtime.catalog()
                                .history(login(owner), app, null)
                                .items()
                                .getFirst()
                                .presentationHash())
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_release where application_id=?",
                                Integer.class,
                                app))
                .isZero();
    }

    @Test
    void trustedDeploymentDeclarationNeverBecomesRuntimeProofAndReadsPreserveAllFacts() {
        var owner = person();
        String app = register(owner);
        var c = candidate(app, 1);
        runtime.catalog().publishSource(login(owner), c, id());
        var p =
                new com.lrj.authz.governance.domain.AccessModels.Partition(
                        owner.tenantId(), app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new com.lrj.authz.governance.domain.AccessModels.Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read")),
                                3600),
                        "drift-test",
                        id());
        var role =
                runtime.access()
                        .createRole(login(owner), p, id(), "reader", 1, List.of(app + ".read"));
        // 现有规则禁止管理员向自己直接授予；使用同企业独立成员，不能为夹具绕过规则。
        var member =
                new BootstrapCommand(
                        id(),
                        "drift-test",
                        owner.tenantId(),
                        owner.tenantCode(),
                        id(),
                        owner.issuer(),
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "drift-test",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(member);
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
                        Instant.now().plusSeconds(500));
        var before = facts(app);
        var declaration =
                new DeploymentDeclaration(
                        app,
                        1,
                        CatalogManifest.hash(c.manifest()),
                        CatalogManifest.presentationHash(c.manifest()),
                        source(),
                        Instant.now().toString(),
                        "isolated-build:1");
        var report = runtime.catalog().drift(login(owner), c, List.of(declaration));
        assertThat(report.deployment().state()).isEqualTo(State.MATCHED_DECLARATION);
        assertThat(report.runtimeState()).isEqualTo(State.UNKNOWN);
        var older =
                new DeploymentDeclaration(
                        app,
                        2,
                        declaration.contentHash(),
                        declaration.presentationHash(),
                        source(),
                        declaration.declaredAt(),
                        "other-build:2");
        assertThat(runtime.catalog().drift(login(owner), c, List.of(older)).deployment().state())
                .isEqualTo(State.SOURCE_MISMATCH);
        var foreign =
                new DeploymentDeclaration(
                        "other",
                        1,
                        declaration.contentHash(),
                        declaration.presentationHash(),
                        source(),
                        declaration.declaredAt(),
                        "foreign-build:1");
        assertThat(runtime.catalog().drift(login(owner), c, List.of(foreign)).deployment().state())
                .isEqualTo(State.UNKNOWN);
        assertThat(facts(app)).isEqualTo(before);
    }

    private List<String> facts(String app) {
        var result = new ArrayList<String>();
        // 表名来自测试内固定允许列表，值参数绑定；比较整行避免只证明计数相等。
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
                        "grant_scope"))
            result.addAll(
                    jdbc
                            .queryForList(
                                    "select row_to_json(r)::text from auth_governance."
                                            + table
                                            + " r where application_id=? order by row_to_json(r)::text",
                                    String.class,
                                    app)
                            .stream()
                            .map(value -> table + ":" + value)
                            .toList());
        return result;
    }

    @Test
    void currentOwnerAndCorruptDependencyFailuresCannotMasqueradeAsUnknownOrEmpty() {
        var owner = person();
        String app = register(owner);
        var c = candidate(app, 1);
        var other = person();
        assertThatThrownBy(() -> runtime.catalog().drift(login(other), c, List.of()))
                .hasMessage("ACCESS_DENIED");
        jdbc.update(
                "insert into auth_governance.application_manifest(application_id,version,content_hash,manifest_json,published_by) values(?,1,?,?,?)",
                app,
                "f".repeat(64),
                CatalogManifest.json(CatalogManifest.normalize(c.manifest())),
                owner.principalId());
        jdbc.update(
                "update auth_governance.application_catalog set manifest_version=1 where application_id=?",
                app);
        assertThatThrownBy(() -> runtime.catalog().drift(login(owner), c, List.of()))
                .hasMessage("DEPENDENCY_UNAVAILABLE");
        jdbc.update(
                "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                owner.principalId());
        assertThatThrownBy(() -> runtime.catalog().drift(login(owner), c, List.of()))
                .hasMessage("MEMBERSHIP_UNAVAILABLE");
    }
}
