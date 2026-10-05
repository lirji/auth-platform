package com.lrj.authz.governance.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogGuardModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实PostgreSQL验证固定预览、单向门禁及原成功回执，所有数据只在专用测试库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogGuardPostgresIT {
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

    private PreviewInput input(Candidate c) {
        return new PreviewInput(c.manifest(), c.source(), c.reason(), c.decision(), null);
    }

    @Test
    void fixedTicketPublishesOnceAndChangedTicketConflictsForSameCommand() {
        var owner = person();
        String app = register(owner), command = id();
        var first =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 1, "门店")), null);
        var second =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 1, "门店")), null);
        var receipt =
                runtime.catalog()
                        .releasePublish(
                                login(owner), new PublishCommand(command, first.previewId()), null);
        var later =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 2, "商品门店")), null);
        runtime.catalog()
                .releasePublish(login(owner), new PublishCommand(id(), later.previewId()), null);
        assertThat(
                        runtime.catalog()
                                .releasePublish(
                                        login(owner),
                                        new PublishCommand(command, first.previewId()),
                                        null))
                .isEqualTo(receipt);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(command, second.previewId()),
                                                null))
                .hasMessage("COMMAND_CONFLICT");
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(2);
    }

    @Test
    void guardedPolicyBlocksEveryOldPublisherButAllowsOriginalSuccessfulReceipt() {
        var owner = person();
        String app = register(owner), original = id();
        var first = manifest(app, 1);
        var receipt = runtime.catalog().publish(login(owner), first, original);
        assertThat(runtime.catalog().guardPolicy(login(owner), app).mode()).isEqualTo(Mode.LEGACY);
        var enable = new Enable(app, id(), 0, true, "隔离验收已退出旧写节点");
        var policy = runtime.catalog().enableGuard(login(owner), enable);
        assertThat(runtime.catalog().enableGuard(login(owner), enable)).isEqualTo(policy);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .enableGuard(
                                                login(owner),
                                                new Enable(app, enable.commandId(), 0, true, "改体")))
                .hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(() -> runtime.catalog().publish(login(owner), manifest(app, 2), id()))
                .hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .publishSource(
                                                login(owner), candidate(app, 2, "新门店"), id()))
                .hasMessage("VERSION_CONFLICT");
        assertThat(runtime.catalog().publish(login(owner), first, original)).isEqualTo(receipt);
        var ticket =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 2, "新门店")), null);
        assertThat(
                        runtime.catalog()
                                .releasePublish(
                                        login(owner),
                                        new PublishCommand(id(), ticket.previewId()),
                                        null)
                                .version())
                .isEqualTo(2);
    }

    @Test
    void staleBaseAndSuspendedOwnerCannotPublishTicket() {
        var owner = person();
        String app = register(owner);
        runtime.catalog().publish(login(owner), manifest(app, 1), id());
        var stale =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 2, "新门店")), null);
        runtime.catalog().publish(login(owner), manifest(app, 2), id());
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(id(), stale.previewId()),
                                                null))
                .hasMessage("VERSION_CONFLICT");
        var current =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 3, "新门店")), null);
        jdbc.update(
                "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                owner.principalId());
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(id(), current.previewId()),
                                                null))
                .hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                "select manifest_version from auth_governance.application_catalog where application_id=?",
                                Long.class,
                                app))
                .isEqualTo(2);
    }

    @Test
    void routeChangeOrReusedEntryNeedsReasonAndDecisionAndForeignOwnerDenied() {
        var owner = person();
        String app = register(owner);
        runtime.catalog().publish(login(owner), manifest(app, 1), id());
        var m = manifest(app, 2);
        var route =
                new Manifest(
                        "1",
                        app,
                        2,
                        m.capabilities(),
                        List.of(
                                new Menu(
                                        "stores",
                                        null,
                                        "/new-store-page",
                                        List.of(app + ".read"))));
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePreview(
                                                login(owner),
                                                new PreviewInput(route, null, null, null, null),
                                                null))
                .hasMessage("INVALID_ARGUMENT");
        var reused =
                new Manifest(
                        "1",
                        app,
                        2,
                        m.capabilities(),
                        List.of(
                                m.menus().getFirst(),
                                new Menu("other", null, "/other", List.of(app + ".read"))));
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePreview(
                                                login(owner),
                                                new PreviewInput(reused, null, null, null, null),
                                                null))
                .hasMessage("INVALID_ARGUMENT");
        var allowed =
                runtime.catalog()
                        .releasePreview(
                                login(owner),
                                new PreviewInput(
                                        route,
                                        null,
                                        "入口调整，保持原授权",
                                        Decision.KEEP_CURRENT_GRANTS,
                                        null),
                                null);
        var other = person();
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(other),
                                                new PublishCommand(id(), allowed.previewId()),
                                                null))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .enableGuard(
                                                login(owner),
                                                new Enable(app, id(), 0, false, "尚未升级")))
                .hasMessage("INVALID_ARGUMENT");
    }

    @Test
    void legacyOptionalReasonNullTextCannotAliasMissingReasonCommand() {
        var owner = person();
        String app = register(owner), command = id();
        var manifest = manifest(app, 1);
        runtime.catalog().publish(login(owner), manifest, command);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .publishSource(
                                                login(owner),
                                                new Candidate(manifest, null, "null", null),
                                                command))
                .hasMessage("COMMAND_CONFLICT");
    }

    @Test
    void expiredTicketIsRejectedUsingPgTimeWithoutEditingImmutableRows() {
        var owner = person();
        String app = register(owner);
        var ticket =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 1, "门店")), null);
        String expired = id();
        // 仅隔离PG构造一个过去已生成的票据；不改生产TTL、不更新原不可变记录、不睡10分钟。
        jdbc.update(
                "insert into auth_governance.catalog_release_preview select ?,application_id,owner_principal,candidate_json,candidate_hash,preview_json,base_version,base_content_hash,base_presentation_hash,impact_json,created_at-interval '11 minutes',expires_at-interval '11 minutes' from auth_governance.catalog_release_preview where id=?",
                expired,
                ticket.previewId());
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(id(), expired),
                                                null))
                .hasMessage("VERSION_CONFLICT");
        assertThat(runtime.catalog().current(login(owner), app)).isNull();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.catalog_release_preview set candidate_hash=? where id=?",
                                        "c".repeat(64),
                                        ticket.previewId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void optionalImpactRequiresIndependentAuthorityAndDetectsSourceChanges() {
        var owner = person();
        String app = register(owner);
        runtime.catalog().publish(login(owner), manifest(app, 1), id());
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
                        "guard-test",
                        id());
        var role =
                runtime.access()
                        .createRole(login(owner), p, id(), "reader", 1, List.of(app + ".read"));
        var member =
                new BootstrapCommand(
                        id(),
                        "guard-test",
                        owner.tenantId(),
                        owner.tenantCode(),
                        id(),
                        owner.issuer(),
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "guard-test",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(member);
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
                                Instant.now().plusSeconds(500));
        var diagnostics =
                runtime.portalPermissions(
                        List.of(new PortalDiagnosticAuthority(p, owner.membershipId(), 1)));
        var c = candidate(app, 2, "商家门店");
        var report = diagnostics.catalogImpact(login(owner), p, c.manifest(), null);
        var impact = new Impact(p.tenantId(), app, p.environment(), report.basisHash());
        var input = new PreviewInput(c.manifest(), c.source(), c.reason(), c.decision(), impact);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePreview(
                                                login(owner),
                                                input,
                                                runtime.portalPermissions(List.of())))
                .hasMessage("ACCESS_DENIED");
        var fixed = runtime.catalog().releasePreview(login(owner), input, diagnostics);
        runtime.access().revoke(login(owner), p, id(), grant.id(), 1);
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(id(), fixed.previewId()),
                                                diagnostics))
                .hasMessage("VERSION_CONFLICT");
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(1);
        // 重新确认变化后的依据，仅目录成功；现有角色、来源、委派和范围逐行完全保留。
        var before = authorizationFacts(app);
        var refreshed = diagnostics.catalogImpact(login(owner), p, c.manifest(), null);
        var valid =
                runtime.catalog()
                        .releasePreview(
                                login(owner),
                                new PreviewInput(
                                        c.manifest(),
                                        c.source(),
                                        c.reason(),
                                        c.decision(),
                                        new Impact(
                                                p.tenantId(),
                                                app,
                                                p.environment(),
                                                refreshed.basisHash())),
                                diagnostics);
        runtime.catalog()
                .releasePublish(
                        login(owner), new PublishCommand(id(), valid.previewId()), diagnostics);
        assertThat(authorizationFacts(app)).isEqualTo(before);
    }

    private List<String> authorizationFacts(String app) {
        return jdbc.queryForList(
                "select 'role:'||row_to_json(r)::text value from auth_governance.role_version r where application_id=? union all select 'grant:'||row_to_json(g)::text from auth_governance.access_grant g where application_id=? union all select 'delegation:'||row_to_json(d)::text from auth_governance.access_delegation d where application_id=? union all select 'scope:'||row_to_json(s)::text from auth_governance.grant_scope s where application_id=? order by value",
                String.class,
                app,
                app,
                app,
                app);
    }

    @Test
    void concurrentOwnerTicketsWithSameBaseAllowOnePublication() throws Exception {
        var owner = person();
        String app = register(owner);
        var tickets =
                List.of(
                        runtime.catalog()
                                .releasePreview(
                                        login(owner), input(candidate(app, 1, "门店甲")), null),
                        runtime.catalog()
                                .releasePreview(
                                        login(owner), input(candidate(app, 1, "门店乙")), null));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<String>>();
            for (var ticket : tickets)
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    try {
                                        runtime.catalog()
                                                .releasePublish(
                                                        login(owner),
                                                        new PublishCommand(
                                                                id(), ticket.previewId()),
                                                        null);
                                        return "PUBLISHED";
                                    } catch (GovernanceException failure) {
                                        return failure.getMessage();
                                    }
                                }));
            assertThat(
                            List.of(
                                    futures.get(0).get(10, TimeUnit.SECONDS),
                                    futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PUBLISHED", "VERSION_CONFLICT");
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_release where application_id=?",
                                Integer.class,
                                app))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.catalog_audit where application_id=? and operation='PUBLISH'",
                                Integer.class,
                                app))
                .isEqualTo(1);
    }

    @Test
    void presentationBasisIsCheckedIndependentlyOfMatchingContentBasis() {
        var owner = person();
        String app = register(owner);
        runtime.catalog().publish(login(owner), manifest(app, 1), id());
        var ticket =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 2, "门店")), null);
        String wrongDisplay = id();
        // 新建隔离损坏依据夹具，不更新已生成票据或已发布不可变展示历史。
        jdbc.update(
                "insert into auth_governance.catalog_release_preview select ?,application_id,owner_principal,candidate_json,candidate_hash,preview_json,base_version,base_content_hash,?,impact_json,created_at,expires_at from auth_governance.catalog_release_preview where id=?",
                wrongDisplay,
                "c".repeat(64),
                ticket.previewId());
        assertThatThrownBy(
                        () ->
                                runtime.catalog()
                                        .releasePublish(
                                                login(owner),
                                                new PublishCommand(id(), wrongDisplay),
                                                null))
                .hasMessage("VERSION_CONFLICT");
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(1);
    }

    @Test
    void expiredAlreadySuccessfulTicketReadsOriginalReceiptEvenAfterNewerVersion() {
        var owner = person();
        String app = register(owner), expired = id(), command = id();
        var c = new Candidate(manifest(app, 1), null, null, null);
        var ticket = runtime.catalog().releasePreview(login(owner), input(c), null);
        // 构造符合正式表约束的过去成功事实，验证读取路径；不缩短生产TTL或篡改原不可变记录。
        // 正常实时发布路径已由前述测试证明；这里不把夹具插入声称为过去真实HTTP执行。
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()));
        tx.execute(
                status -> {
                    jdbc.update(
                            "insert into auth_governance.catalog_release_preview select ?,application_id,owner_principal,candidate_json,candidate_hash,preview_json,base_version,base_content_hash,base_presentation_hash,impact_json,created_at-interval '11 minutes',expires_at-interval '11 minutes' from auth_governance.catalog_release_preview where id=?",
                            expired,
                            ticket.previewId());
                    jdbc.update(
                            "insert into auth_governance.application_manifest(application_id,version,content_hash,manifest_json,published_by) values(?,1,?,?,?)",
                            app,
                            ticket.preview().contentHash(),
                            CatalogManifest.json(CatalogManifest.normalize(c.manifest())),
                            owner.principalId());
                    jdbc.update(
                            "update auth_governance.application_catalog set manifest_version=1 where application_id=? and manifest_version=0",
                            app);
                    jdbc.update(
                            "insert into auth_governance.catalog_audit(id,application_id,operator_ref,operation,manifest_version,command_id) values(?,?,?,'PUBLISH',1,?)",
                            id(),
                            app,
                            owner.principalId(),
                            command);
                    jdbc.update(
                            "insert into auth_governance.catalog_release(application_id,version,content_hash,presentation_hash,published_by,command_id,command_hash,base_version,base_content_hash,base_presentation_hash,preview_json) select application_id,1,?,?,owner_principal,?,?,base_version,base_content_hash,base_presentation_hash,preview_json from auth_governance.catalog_release_preview where id=?",
                            ticket.preview().contentHash(),
                            ticket.preview().presentationHash(),
                            command,
                            AccessValues.hash("catalog-release-publish/v1", expired),
                            expired);
                    return null;
                });
        var receipt =
                runtime.catalog()
                        .releasePublish(login(owner), new PublishCommand(command, expired), null);
        assertThat(receipt.preview()).isEqualTo(ticket.preview());
        var next =
                runtime.catalog()
                        .releasePreview(login(owner), input(candidate(app, 2, "新门店")), null);
        runtime.catalog()
                .releasePublish(login(owner), new PublishCommand(id(), next.previewId()), null);
        assertThat(
                        runtime.catalog()
                                .releasePublish(
                                        login(owner), new PublishCommand(command, expired), null))
                .isEqualTo(receipt);
        assertThat(runtime.catalog().current(login(owner), app).manifestVersion()).isEqualTo(2);
    }
}
