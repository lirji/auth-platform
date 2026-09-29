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
    @Test void dependencyErrorKeepsOwnedProgressContextButClosesEveryBusinessEntry() {
        var owner = person(id(), "tenant-" + id()); application(owner);
        for (var code : List.of(GovernanceException.Code.DEPENDENCY_UNAVAILABLE, GovernanceException.Code.AUTHZ_STATE_NOT_READY)) {
        var portal = portal((context, cap, resource) -> { throw new GovernanceException(code); });
        var application = portal.applications(login(owner), owner.tenantId(), null).items().getFirst();
        assertThat(application.entryState()).isEqualTo(PortalDirectory.EntryState.UNAVAILABLE);
        assertThat(application.menus()).isEmpty(); assertThat(application.capabilityHints()).isEmpty();
        assertThat(application.management()).isTrue();
        }
    }
    @Test void managementReadModelsRequireCurrentDelegationAndFilterTenantMembers() {
        var owner = person(id(), "tenant-" + id()); var member = person(owner.tenantId(), owner.tenantCode());
        var foreign = person(id(), "tenant-" + id()); var p = application(owner);
        var view = runtime.portalManagement();
        assertThat(view.management(login(owner), p).catalogOwner()).isTrue();
        assertThat(view.management(login(owner), p).capabilities()).extracting(com.lrj.authz.protocol.PortalDtos.Capability::code).containsExactly(p.applicationId() + ".read");
        assertThat(view.members(login(owner), p, null).items()).extracting(com.lrj.authz.protocol.PortalDtos.Member::membershipId)
                .containsExactlyInAnyOrder(owner.membershipId(), member.membershipId()).doesNotContain(foreign.membershipId());
        assertThatThrownBy(() -> view.management(login(member), p)).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> view.members(login(foreign), p, null)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        jdbc.update("update auth_governance.access_delegation set enabled=false where membership_id=?", owner.membershipId());
        assertThatThrownBy(() -> view.members(login(owner), p, null)).hasMessage("ACCESS_DENIED");
    }
    @Test void fixedRoleImpactDoesNotMigrateExistingGrantsAndRejectsForeignIds() {
        var owner = person(id(), "tenant-" + id()); var member = person(owner.tenantId(), owner.tenantCode()); var p = application(owner);
        var first = runtime.access().createRole(login(owner), p, id(), "reader", 1, List.of(p.applicationId() + ".read"));
        runtime.access().grant(login(owner), p, id(), member.membershipId(), 1, first.id(), "TENANT_ALL", id(), Instant.now(), Instant.now().plusSeconds(300));
        var second = runtime.access().createRole(login(owner), p, id(), "reader", 2, List.of(p.applicationId() + ".read"));
        var view = runtime.portalManagement();
        assertThat(view.roleImpact(login(owner), p, first.id()).referencingGrantCount()).isEqualTo(1);
        var impact = view.roleImpact(login(owner), p, second.id());
        assertThat(impact.previousRoleId()).isEqualTo(first.id()); assertThat(impact.added()).isEmpty();
        assertThat(impact.removed()).isEmpty(); assertThat(impact.referencingGrantCount()).isZero();
        assertThat(runtime.access().state(login(owner), p, null, null).grants()).singleElement().extracting(Grant::roleId).isEqualTo(first.id());
        var other = application(owner);
        assertThatThrownBy(() -> view.roleImpact(login(owner), other, first.id())).hasMessage("ACCESS_DENIED");
    }
    @Test void managementProgressAndDisabledCapabilityComeFromDatabase() {
        var owner = person(id(), "tenant-" + id()); var p = application(owner);
        runtime.access().enableStrict(login(owner), p, id());
        var view = runtime.portalManagement();
        assertThat(view.management(login(owner), p).policyState()).isEqualTo("UPDATING");
        runtime.catalog().changeCapability(login(owner), p.applicationId(), p.applicationId() + ".read", true, 0, "emergency", id());
        assertThat(view.management(login(owner), p).capabilities()).singleElement().extracting(com.lrj.authz.protocol.PortalDtos.Capability::disabled).isEqualTo(true);
    }

    private PortalInvitations invitationPortal(BootstrapCommand owner, Partition p) {
        return runtime.portalInvitations(List.of(new PortalInvitationAuthority(p,owner.membershipId(),1,3600,86400)),owner.issuer());
    }
    private com.lrj.authz.protocol.PortalInvitationDtos.Issue invitationInput(Partition p, String subject, String kind, String proof) {
        return new com.lrj.authz.protocol.PortalInvitationDtos.Issue(p.tenantId(),p.applicationId(),p.environment(),id(),id(),subject,kind,proof,null,null,"真实PG门户邀请");
    }
    @Test void portalInvitationRequiresExplicitScopeAndCurrentGenerationNotJustAppManagement() {
        var owner=person(id(),"tenant-"+id());var p=application(owner);
        assertThatThrownBy(() -> runtime.portalInvitations(List.of(),owner.issuer()).authority(login(owner),p)).hasMessage("ACCESS_DENIED");
        var portal=invitationPortal(owner,p);
        assertThat(portal.authority(login(owner),p).maxInvitationSeconds()).isEqualTo(3600);
        var other=application(owner);
        assertThatThrownBy(() -> portal.authority(login(owner),other)).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> runtime.portalInvitations(List.of(new PortalInvitationAuthority(p,owner.membershipId(),2,3600,86400)),owner.issuer()).authority(login(owner),p)).hasMessage("ACCESS_DENIED");
        jdbc.update("update auth_governance.access_delegation set enabled=false where membership_id=?",owner.membershipId());
        assertThatThrownBy(() -> portal.authority(login(owner),p)).hasMessage("ACCESS_DENIED");
    }
    @Test void invitationPortalKeepsProofPrivateReplaysAndRejectsPrivilegeOrDeadlineExpansion() {
        var owner=person(id(),"tenant-"+id());var p=application(owner);var portal=invitationPortal(owner,p);
        String proof=InvitationCommands.newToken();var input=invitationInput(p,id(),"PARTNER",proof);
        Instant expires=Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS), valid=expires.plusSeconds(600);
        var created=portal.issue(login(owner),p,input,expires,valid);
        assertThat(portal.issue(login(owner),p,input,expires,valid)).isEqualTo(created);
        assertThat(portal.list(login(owner),p,null).items()).singleElement().extracting(com.lrj.authz.protocol.PortalInvitationDtos.View::id).isEqualTo(created.id());
        assertThat(created.toString()).doesNotContain(proof,InvitationCommands.tokenHash(proof));
        assertThatThrownBy(() -> portal.issue(login(owner),p,invitationInput(p,id(),"EMPLOYEE",InvitationCommands.newToken()),expires,valid)).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> portal.issue(login(owner),p,invitationInput(p,id(),"GUEST",InvitationCommands.newToken()),expires.plusSeconds(7200),valid.plusSeconds(7200))).hasMessage("INVALID_ARGUMENT");
        assertThat(runtime.invitations().accept(created.id(),proof,new VerifiedLogin(owner.issuer(),input.targetSubject())).membershipStatus()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> portal.revoke(login(owner),p,created.id(),new com.lrj.authz.protocol.PortalInvitationDtos.Revoke(p.tenantId(),p.applicationId(),p.environment(),id(),created.version(),"撤销"))).hasMessage("VERSION_CONFLICT");
    }
    @Test void invitationPortalListAndRevocationCannotCrossOperatorOrPartition() {
        var owner=person(id(),"tenant-"+id());var other=person(owner.tenantId(),owner.tenantCode());var p=application(owner);
        runtime.access().bootstrap(p,new Delegation(other.membershipId(),1,AccessValues.json(List.of(p.applicationId()+".read")),3600),"test",id());
        var portals=runtime.portalInvitations(List.of(new PortalInvitationAuthority(p,owner.membershipId(),1,3600,86400),new PortalInvitationAuthority(p,other.membershipId(),1,3600,86400)),owner.issuer());
        var input=invitationInput(p,id(),"GUEST",InvitationCommands.newToken());var expires=Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var created=portals.issue(login(owner),p,input,expires,expires.plusSeconds(600));
        assertThat(portals.list(login(other),p,null).items()).isEmpty();
        var revoke=new com.lrj.authz.protocol.PortalInvitationDtos.Revoke(p.tenantId(),p.applicationId(),p.environment(),id(),1,"不再协作");
        assertThatThrownBy(() -> portals.revoke(login(other),p,created.id(),revoke)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThat(portals.revoke(login(owner),p,created.id(),revoke).state()).isEqualTo("REVOKED");
        assertThat(portals.revoke(login(owner),p,created.id(),revoke).state()).isEqualTo("REVOKED");
    }
    @Test void policyDisplayUsesImmutableRoleAndManagementReadNeedsDelegation() {
        var owner=person(id(),"tenant-"+id());var member=person(owner.tenantId(),owner.tenantCode());var p=application(owner);
        runtime.access().enableStrict(login(owner),p,id());
        var role=runtime.access().createRole(login(owner),p,id(),"reader",1,List.of(p.applicationId()+".read"));
        var rule=new com.lrj.authz.protocol.ScopeDtos.Rule(1,"store",List.of(new com.lrj.authz.protocol.ScopeDtos.Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S1"),false)));
        var policy=runtime.requests().registerPolicy(login(owner),p,id(),role.id(),rule,600,owner.membershipId(),1,1);
        var page=runtime.portalManagement().policies(login(owner),p,null);
        assertThat(page.items()).singleElement().extracting(com.lrj.authz.governance.domain.RequestModels.Policy::id).isEqualTo(policy.id());
        var view=runtime.requests().policyView(policy);assertThat(view.roleCode()).isEqualTo("reader");assertThat(view.roleVersion()).isEqualTo(1);
        assertThat(runtime.requests().policyPage(login(member),p,null).items()).hasSize(1);
        assertThatThrownBy(() -> runtime.portalManagement().policies(login(member),p,null)).hasMessage("ACCESS_DENIED");
    }

}
