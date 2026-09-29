package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 真实PG验证门户目录的租户/代际/分页边界；图端口在此只测试组合，真实图留跨进程验收。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    @BeforeAll void open() {
        Properties p = new Properties(); String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) p = GovernanceConfigurationFile.read(file);
        else { p.setProperty("jdbc.url", System.getenv("GOVERNANCE_TEST_DB_URL")); p.setProperty("jdbc.username", System.getenv("GOVERNANCE_TEST_DB_USER")); p.setProperty("jdbc.password", System.getenv("GOVERNANCE_TEST_DB_PASSWORD")); }
        assertThat(p.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(p); runtime = GovernanceRuntime.open(db, true);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
    }
    @AfterAll void close() { if (runtime != null) runtime.close(); }
    private String id() { return UUID.randomUUID().toString(); }
    private BootstrapCommand person(String tenant, String code) {
        var c = new BootstrapCommand(id(), "portal-test", tenant, code, id(), "https://portal.example", id(), id(), Instant.parse("2020-01-01T00:00:00Z"), null, "portal", id(), id());
        runtime.identity().bootstrapEmployee(c); return c;
    }
    private VerifiedLogin login(BootstrapCommand c) { return new VerifiedLogin(c.issuer(), c.subject()); }
    private Partition application(BootstrapCommand owner) {
        String app = "portal-" + id();
        runtime.catalog().register(app, owner.principalId(), "https://business.test", "test", id());
        runtime.catalog().publish(login(owner), new Manifest("1", app, 1, List.of(new Capability(app + ".read", "store", Risk.NORMAL)),
                List.of(new Menu("stores", null, "/stores", List.of(app + ".read")))), id());
        var p = new Partition(owner.tenantId(), app, "test");
        runtime.access().bootstrap(p, new Delegation(owner.membershipId(), 1, AccessValues.json(List.of(app + ".read")), 3600), "test", id()); return p;
    }
    private PortalDirectory portal(AccessPresentation.Checker checker) {
        // 当前测试复用真实Mapper和身份；受控判权端口避免把本测试宣称为真实图验证。
        var catalog = org.mockito.Mockito.mock(CatalogMapper.class);
        org.mockito.Mockito.when(catalog.application(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String app = inv.getArgument(0); return jdbc.queryForObject("select application_id,owner_principal_id,entry_origin,manifest_version from auth_governance.application_catalog where application_id=?",
                    (r,n) -> new Application(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)), app);
        });
        org.mockito.Mockito.when(catalog.snapshot(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            String app = inv.getArgument(0); Long version = inv.getArgument(1); return jdbc.queryForObject("select application_id,version,content_hash,manifest_json from auth_governance.application_manifest where application_id=? and version=?",
                    (r,n) -> new Snapshot(r.getString(1),r.getLong(2),r.getString(3),r.getString(4)), app, version);
        });
        return runtime.portal(new AccessPresentation(runtime.identity(), catalog, checker));
    }
    @Test void directoryDoesNotExposeUnrelatedApplicationsOrPromoteManagementToBusinessAccess() {
        var owner = person(id(), "tenant-" + id()); var member = person(owner.tenantId(), owner.tenantCode()); var p = application(owner);
        var portal = portal((context, cap, resource) -> false);
        assertThat(portal.applications(login(member), owner.tenantId(), null).items()).isEmpty();
        var apps = portal.applications(login(owner), owner.tenantId(), null).items();
        assertThat(apps).hasSize(1); assertThat(apps.getFirst().management()).isTrue(); assertThat(apps.getFirst().menus()).isEmpty();
        var role = runtime.access().createRole(login(owner), p, id(), "reader", 1, List.of(p.applicationId() + ".read"));
        runtime.access().grant(login(owner), p, id(), member.membershipId(), 1, role.id(), "TENANT_ALL", id(), Instant.now(), Instant.now().plusSeconds(300));
        assertThat(portal.applications(login(member), owner.tenantId(), null).items()).singleElement().satisfies(app -> { assertThat(app.management()).isFalse(); assertThat(app.menus()).isEmpty(); });
        assertThat(portal((context, cap, resource) -> true).applications(login(member), owner.tenantId(), null).items().getFirst().menus().getFirst().href()).isEqualTo("https://business.test/stores");
    }
    @Test void organizationsAreOwnAndCurrentAndForgedTenantIsDenied() {
        var a = person(id(), "tenant-" + id()); var other = person(id(), "tenant-" + id()); var portal = portal((c,cap,r) -> false);
        assertThat(portal.organizations(login(a))).singleElement().extracting(PortalDirectory.Organization::tenantCode).isEqualTo(a.tenantCode());
        assertThatThrownBy(() -> portal.applications(login(a), other.tenantId(), null)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        jdbc.update("update auth_governance.membership set status='SUSPENDED',version=version+1 where id=?", a.membershipId());
        assertThat(portal.organizations(login(a))).isEmpty();
        assertThatThrownBy(() -> portal.applications(login(a), a.tenantId(), null)).hasMessage("MEMBERSHIP_UNAVAILABLE");
    }
    @Test void boundedCursorCannotReplayDataFromAnotherOrganization() {
        var a = person(id(), "tenant-" + id());
        var b = new BootstrapCommand(id(), "portal-test", id(), "tenant-" + id(), a.principalId(), a.issuer(), a.subject(), id(), a.validFrom(), null, "portal", id(), id());
        runtime.identity().bootstrapEmployee(b);
        for (int n = 0; n < 21; n++) application(a);
        var portal = portal((c,cap,r) -> false); var first = portal.applications(login(a), a.tenantId(), null);
        assertThat(first.items()).hasSize(20); assertThat(first.nextCursor()).isNotNull();
        assertThat(portal.applications(login(a), a.tenantId(), first.nextCursor()).items()).hasSize(1);
        assertThat(portal.applications(login(a), b.tenantId(), first.nextCursor()).items()).isEmpty();
        assertThat(portal.organizations(login(a))).hasSize(2);
        assertThatThrownBy(() -> portal.applications(login(a), a.tenantId(), "../../foreign")).hasMessage("INVALID_ARGUMENT");
    }
    @Test void membershipChangeDuringRemoteReadRejectsLateResults() {
        var owner = person(id(), "tenant-" + id()); application(owner);
        var portal = portal((context, cap, resource) -> {
            jdbc.update("update auth_governance.membership set version=version+1 where id=?", owner.membershipId()); return true;
        });
        assertThatThrownBy(() -> portal.applications(login(owner), owner.tenantId(), null)).hasMessage("VERSION_CONFLICT");
    }
    @Test void dependencyErrorNeverReturnsPartialAllowedDirectory() {
        var owner = person(id(), "tenant-" + id()); application(owner);
        var portal = portal((context, cap, resource) -> { throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE); });
        assertThatThrownBy(() -> portal.applications(login(owner), owner.tenantId(), null)).hasMessage("DEPENDENCY_UNAVAILABLE");
    }
}
