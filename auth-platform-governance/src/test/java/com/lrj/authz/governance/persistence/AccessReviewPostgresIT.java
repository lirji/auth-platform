package com.lrj.authz.governance.persistence;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.AccessReviewDtos.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 真PG保护固定输入、资格、幂等与严格撤权原子性，不用SQL伪造完成图回执。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessReviewPostgresIT {
    private RoleMigrationFixture h;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private List<PortalDiagnosticAuthority> authorities(F f) {
        return List.of(new PortalDiagnosticAuthority(f.partition(), f.owner().membershipId(), 1));
    }

    private AccessReviews service(F f) {
        return h.runtime.accessReviews(authorities(f));
    }

    private Create input(F f, String... grants) {
        var p = f.partition();
        var r =
                h.runtime
                        .portalPermissions(authorities(f))
                        .personnelImpact(
                                f.login(),
                                p,
                                f.member().membershipId(),
                                null,
                                null,
                                h.runtime.personnelImpact());
        return new Create(
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                id(),
                f.member().membershipId(),
                f.owner().membershipId(),
                1,
                r.basisHash(),
                null,
                List.of(grants),
                "人工核对调岗后仍存例外");
    }

    private Detail create(F f, Grant... grants) {
        return service(f)
                .create(
                        f.login(),
                        input(f, Arrays.stream(grants).map(Grant::id).toArray(String[]::new)));
    }

    private Decide decide(F f, Detail t, int index, Decision d, boolean group) {
        var p = f.partition();
        var e = t.items().get(index);
        return new Decide(
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                id(),
                t.version(),
                e.id(),
                e.version(),
                t.currentReport().basisHash(),
                d,
                "核对实际来源并记录意见",
                group);
    }

    @Test
    void frozenScopeIdempotencyAndChangedCommandBody() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var b = h.grant(f, false);
        var c = input(f, a.id());
        var s = service(f);
        var t = s.create(f.login(), c);
        assertThat(t.items()).hasSize(1);
        assertThat(t.currentReport().sources()).hasSize(2);
        assertThat(t.canDecide()).isTrue();
        assertThat(s.create(f.login(), c).id()).isEqualTo(t.id());
        var other =
                new Create(
                        c.tenantId(),
                        c.applicationId(),
                        c.environment(),
                        c.commandId(),
                        c.membershipId(),
                        c.responsibleMembershipId(),
                        1,
                        c.basisHash(),
                        null,
                        List.of(b.id()),
                        c.reason());
        assertThatThrownBy(() -> s.create(f.login(), other)).hasMessage("COMMAND_CONFLICT");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.access_review_audit WHERE task_id=?",
                                Integer.class,
                                t.id()))
                .isEqualTo(1);
        var reused = decide(f, t, 0, Decision.KEEP, false);
        assertThatThrownBy(
                        () ->
                                s.decide(
                                        f.login(),
                                        t.id(),
                                        new Decide(
                                                reused.tenantId(),
                                                reused.applicationId(),
                                                reused.environment(),
                                                c.commandId(),
                                                reused.expectedTaskVersion(),
                                                reused.itemId(),
                                                reused.expectedItemVersion(),
                                                reused.currentBasisHash(),
                                                reused.decision(),
                                                reused.reason(),
                                                false)))
                .hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_review_item SET original_hash=?,version=version+1,state='KEPT',reason='非法覆盖' WHERE task_id=?",
                                        "a".repeat(64),
                                        t.id()))
                .hasMessageContaining("复核原来源不可修改");
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_review_task SET original_basis_hash=?,version=version+1 WHERE id=?",
                                        "b".repeat(64),
                                        t.id()))
                .hasMessageContaining("复核原输入不可修改");
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "DELETE FROM auth_governance.access_review_audit WHERE task_id=?",
                                        t.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void partialInvestigationKeepReplayAndCancelNeverChangeGrant() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var b = h.grant(f, false);
        var s = service(f);
        var t = create(f, a, b);
        var c = decide(f, t, 0, Decision.INVESTIGATE, false);
        t = s.decide(f.login(), t.id(), c);
        assertThat(t.state()).isEqualTo(TaskState.NEEDS_INVESTIGATION);
        assertThat(s.decide(f.login(), t.id(), c).version()).isEqualTo(t.version());
        var old = t;
        var keep = decide(f, t, 0, Decision.KEEP, false);
        t = s.decide(f.login(), t.id(), keep);
        assertThat(t.state()).isEqualTo(TaskState.RUNNING);
        var p = f.partition();
        var cancel =
                new Cancel(
                        p.tenantId(),
                        p.applicationId(),
                        p.environment(),
                        id(),
                        t.version(),
                        "停止其余未处理项");
        t = s.cancel(f.login(), t.id(), cancel);
        assertThat(t.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(t.items())
                .extracting(Item::state)
                .containsExactlyInAnyOrder(ItemState.KEPT, ItemState.CANCELLED);
        assertThat(s.cancel(f.login(), t.id(), cancel).version()).isEqualTo(t.version());
        assertThat(h.current(f, a.id()).version()).isEqualTo(1);
        assertThat(h.current(f, b.id()).version()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                s.decide(
                                        f.login(),
                                        old.id(),
                                        decide(f, old, 1, Decision.KEEP, false)))
                .hasMessage("VERSION_CONFLICT");
    }

    @Test
    void actualStrictRevokeIsPendingUntilRealProofAndCancelDoesNotRestore() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var b = h.grant(f, false);
        var s = service(f);
        var t = create(f, a, b);
        var c = decide(f, t, 0, Decision.REVOKE, false);
        String grant = t.items().getFirst().grantId();
        t = s.decide(f.login(), t.id(), c);
        assertThat(t.items().getFirst().state()).isEqualTo(ItemState.WAIT_REVOKE);
        assertThat(h.current(f, grant).state()).isEqualTo(GrantState.REVOKED);
        assertThat(s.decide(f.login(), t.id(), c).version()).isEqualTo(t.version());
        var p = f.partition();
        var e = t.items().getFirst();
        var confirm =
                new Confirm(
                        p.tenantId(),
                        p.applicationId(),
                        p.environment(),
                        id(),
                        t.version(),
                        e.id(),
                        e.version(),
                        "等待真实删除证明");
        var current = t;
        assertThatThrownBy(() -> s.confirm(f.login(), current.id(), confirm))
                .hasMessage("VERSION_CONFLICT");
        t =
                s.cancel(
                        f.login(),
                        t.id(),
                        new Cancel(
                                p.tenantId(),
                                p.applicationId(),
                                p.environment(),
                                id(),
                                t.version(),
                                "停止后续但保留已发撤权"));
        assertThat(t.items())
                .extracting(Item::state)
                .contains(ItemState.WAIT_REVOKE, ItemState.CANCELLED);
        assertThat(h.current(f, grant).state()).isEqualTo(GrantState.REVOKED);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.audit_event WHERE command_id=? AND operation='REVOKE_GRANT'",
                                Integer.class,
                                e.revokeCommand()))
                .isEqualTo(1);
    }

    @Test
    void sourceAndTargetBasisChangesRefuseOldDecision() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var t = create(f, a);
        var c = decide(f, t, 0, Decision.KEEP, false);
        h.grant(f, false);
        var s = service(f);
        assertThatThrownBy(() -> s.decide(f.login(), t.id(), c)).hasMessage("VERSION_CONFLICT");
        var fresh = s.get(f.login(), f.partition(), t.id());
        h.runtime.access().strictRevoke(f.login(), f.partition(), id(), a.id(), 1);
        var old = decide(f, fresh, 0, Decision.REVOKE, false);
        assertThatThrownBy(() -> s.decide(f.login(), t.id(), old)).hasMessage("VERSION_CONFLICT");
        assertThat(s.get(f.login(), f.partition(), t.id()).items().getFirst().state())
                .isEqualTo(ItemState.PENDING);
    }

    @Test
    void groupRevokeRequiresWholeGroupAcknowledgement() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        var grant = h.groupGrant(f, g);
        var s = service(f);
        var t = create(f, grant);
        assertThatThrownBy(
                        () -> s.decide(f.login(), t.id(), decide(f, t, 0, Decision.REVOKE, false)))
                .hasMessage("INVALID_ARGUMENT");
        var result = s.decide(f.login(), t.id(), decide(f, t, 0, Decision.REVOKE, true));
        assertThat(result.items().getFirst().state()).isEqualTo(ItemState.WAIT_REVOKE);
        assertThat(result.items().getFirst().original().grant().sourceType()).isEqualTo("GROUP");
        assertThat(h.current(f, grant.id()).state()).isEqualTo(GrantState.REVOKED);
    }

    @Test
    void ordinaryOwnerWithoutDiagnosticAndForeignTaskAreRefused() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var t = create(f, a);
        var s = service(f);
        var ordinary = new VerifiedLogin(f.member().issuer(), f.member().subject());
        assertThatThrownBy(() -> s.get(ordinary, f.partition(), t.id()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .accessReviews(List.of())
                                        .get(f.login(), f.partition(), t.id()))
                .hasMessage("ACCESS_DENIED");
        var other = h.fixture();
        assertThatThrownBy(() -> service(other).get(other.login(), other.partition(), t.id()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> s.get(f.login(), f.partition(), id())).hasMessage("ACCESS_DENIED");
        h.runtime
                .retirementExit()
                .exit(
                        f.partition(),
                        RetirementReferenceExit.Kind.DELEGATION,
                        f.owner().membershipId(),
                        h.runtime
                                .retirementExit()
                                .inspect(
                                        f.partition(),
                                        RetirementReferenceExit.Kind.DELEGATION,
                                        f.owner().membershipId()),
                        "mg18",
                        id(),
                        "复核资格退出");
        assertThatThrownBy(() -> s.decide(f.login(), t.id(), decide(f, t, 0, Decision.KEEP, false)))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void explicitQualifiedReassignmentRestoresResponsibilityWithoutGrantWrite() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var t = create(f, a);
        var p = f.partition();
        h.runtime
                .access()
                .bootstrap(
                        p,
                        new Delegation(
                                f.member().membershipId(),
                                1,
                                AccessValues.json(f.capabilities()),
                                3600),
                        "mg18",
                        id());
        var both =
                List.of(
                        new PortalDiagnosticAuthority(p, f.owner().membershipId(), 1),
                        new PortalDiagnosticAuthority(p, f.member().membershipId(), 1));
        var s = h.runtime.accessReviews(both);
        var manager = new VerifiedLogin(f.member().issuer(), f.member().subject());
        assertThat(s.responsibles(manager, p)).hasSize(2);
        h.runtime
                .retirementExit()
                .exit(
                        p,
                        RetirementReferenceExit.Kind.DELEGATION,
                        f.owner().membershipId(),
                        h.runtime
                                .retirementExit()
                                .inspect(
                                        p,
                                        RetirementReferenceExit.Kind.DELEGATION,
                                        f.owner().membershipId()),
                        "mg18",
                        id(),
                        "原负责人失权");
        var old = s.get(manager, p, t.id());
        assertThat(old.responsibleQualified()).isFalse();
        assertThat(old.canDecide()).isFalse();
        var r =
                new Reassign(
                        p.tenantId(),
                        p.applicationId(),
                        p.environment(),
                        id(),
                        old.version(),
                        f.member().membershipId(),
                        1,
                        "显式接手复核责任");
        var changed = s.reassign(manager, t.id(), r);
        assertThat(changed.canDecide()).isTrue();
        assertThat(s.reassign(manager, t.id(), r).version()).isEqualTo(changed.version());
        var e = changed.items().getFirst();
        var done =
                s.decide(
                        manager,
                        t.id(),
                        new Decide(
                                p.tenantId(),
                                p.applicationId(),
                                p.environment(),
                                id(),
                                changed.version(),
                                e.id(),
                                e.version(),
                                changed.currentReport().basisHash(),
                                Decision.KEEP,
                                "保留原例外，不产生新授权",
                                false));
        assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(
                        h.current(
                                        new F(
                                                f.member(),
                                                f.member(),
                                                p,
                                                f.oldRole(),
                                                f.newRole(),
                                                f.capabilities()),
                                        a.id())
                                .version())
                .isEqualTo(1);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.access_review_audit WHERE task_id=? AND operation='REASSIGN_ACCESS_REVIEW' AND previous_state=?",
                                Integer.class,
                                t.id(),
                                f.owner().membershipId() + ":1"))
                .isEqualTo(1);
    }

    @Test
    void currentManagementAloneCannotBeAssignedAsDiagnosticResponsible() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var p = f.partition();
        h.runtime
                .access()
                .bootstrap(
                        p,
                        new Delegation(
                                f.member().membershipId(),
                                1,
                                AccessValues.json(f.capabilities()),
                                3600),
                        "mg18",
                        id());
        var c = input(f, a.id());
        var unsupported =
                new Create(
                        c.tenantId(),
                        c.applicationId(),
                        c.environment(),
                        id(),
                        c.membershipId(),
                        f.member().membershipId(),
                        1,
                        c.basisHash(),
                        null,
                        c.grantIds(),
                        c.reason());
        assertThatThrownBy(() -> service(f).create(f.login(), unsupported))
                .hasMessage("ACCESS_DENIED");
        assertThat(service(f).responsibles(f.login(), p))
                .extracting(Responsible::membershipId)
                .containsExactly(f.owner().membershipId());
    }

    @Test
    void reviewAuditFailureRollsBackGrantOutboxAndCommand() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var t = create(f, a);
        var c = decide(f, t, 0, Decision.REVOKE, false);
        String fn = "mg18_fail_" + id().replace("-", "");
        h.jdbc.execute(
                "CREATE FUNCTION auth_governance."
                        + fn
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id='"
                        + t.id()
                        + "' THEN RAISE EXCEPTION 'mg18 deliberate audit fault'; END IF; RETURN NEW; END $$");
        h.jdbc.execute(
                "CREATE TRIGGER "
                        + fn
                        + " BEFORE INSERT ON auth_governance.access_review_audit FOR EACH ROW EXECUTE FUNCTION auth_governance."
                        + fn
                        + "()");
        try {
            assertThatThrownBy(() -> service(f).decide(f.login(), t.id(), c))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            h.jdbc.execute("DROP TRIGGER " + fn + " ON auth_governance.access_review_audit");
            h.jdbc.execute("DROP FUNCTION auth_governance." + fn + "()");
        }
        assertThat(h.current(f, a.id()).version()).isEqualTo(1);
        assertThat(service(f).get(f.login(), f.partition(), t.id()).items().getFirst().state())
                .isEqualTo(ItemState.PENDING);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.grant_projection WHERE grant_id=? AND grant_version=2",
                                Integer.class,
                                a.id()))
                .isZero();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.command_record WHERE command_id=?",
                                Integer.class,
                                c.commandId()))
                .isZero();
    }

    @Test
    void closedRuntimeRestoresCheckpointsAndAllTablesColumnsHaveComments() {
        var f = h.fixture();
        var a = h.grant(f, true);
        var t = create(f, a);
        var c = decide(f, t, 0, Decision.INVESTIGATE, false);
        t = service(f).decide(f.login(), t.id(), c);
        try (var reopened = GovernanceRuntime.open(h.database, false)) {
            var got = reopened.accessReviews(authorities(f)).get(f.login(), f.partition(), t.id());
            assertThat(got.version()).isEqualTo(t.version());
            assertThat(got.items().getFirst().state()).isEqualTo(ItemState.INVESTIGATE);
            assertThat(got.items().getFirst().original())
                    .isEqualTo(t.items().getFirst().original());
        }
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname='auth_governance' AND c.relname IN ('access_review_task','access_review_item','access_review_audit') AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='auth_governance' AND c.relname IN ('access_review_task','access_review_item','access_review_audit') AND obj_description(c.oid) IS NULL",
                                Integer.class))
                .isZero();
    }
}
