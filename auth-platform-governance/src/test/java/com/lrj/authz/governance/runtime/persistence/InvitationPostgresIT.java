package com.lrj.authz.governance.runtime.persistence;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.application.LifecycleCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.identity.domain.IdentityModels.*;
import com.lrj.authz.governance.identity.invitation.application.InvitationCommands;
import com.lrj.authz.governance.identity.invitation.application.InvitationCommands.*;
import com.lrj.authz.governance.identity.invitation.application.InvitationGovernance;
import com.lrj.authz.governance.identity.invitation.domain.InvitationModels.InvitationState;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** 独立 PostgreSQL 证明邀请单次性、租户/代际边界与真实事务失败，不用 Mock 证明原子性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvitationPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;

    @BeforeAll
    void openOwnedDatabase() throws Exception {
        Properties p = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) {
            p = GovernanceConfigurationFile.read(file);
        } else {
            p.setProperty("jdbc.url", required("GOVERNANCE_TEST_DB_URL"));
            p.setProperty("jdbc.username", required("GOVERNANCE_TEST_DB_USER"));
            p.setProperty("jdbc.password", required("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        assertThat(p.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var database = GovernanceDatabase.from(p);
        runtime = GovernanceRuntime.open(database, true);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                database.jdbcUrl(), database.username(), database.password()));
    }

    @AfterAll
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void createsExternalIdentityOnceWithoutChangingSponsorOrAssigningEmployeeKind() {
        var f = issue(sponsor(), id(), MemberKind.PARTNER);
        var first = accept(f);
        assertThat(accept(f)).isEqualTo(first);
        var member = runtime.mapper().membership(first.membershipId());
        assertThat(member.memberKind()).isEqualTo(MemberKind.PARTNER);
        assertThat(member.sponsorMembershipId())
                .isEqualTo(f.command.authority().sponsorMembershipId());
        assertThat(runtime.mapper().membership(member.sponsorMembershipId()).memberKind())
                .isEqualTo(MemberKind.EMPLOYEE);
        assertThat(member.validTo()).isEqualTo(f.command.membershipValidTo());
        assertThat(
                        runtime.identity()
                                .contextForLogin(
                                        f.command.issuer(),
                                        f.command.subject(),
                                        member.tenantId(),
                                        1L)
                                .membershipId())
                .isEqualTo(member.id());
        assertThat(audits(f, "ACCEPT_INVITATION")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.invitation_audit_detail WHERE invitation_id=?",
                                Integer.class,
                                f.command.invitationId()))
                .isEqualTo(2);
    }

    @Test
    void concurrentAcceptsReturnOneMembershipAndAudit() throws Exception {
        var f = issue(sponsor(), id(), MemberKind.GUEST);
        try (var threads = Executors.newFixedThreadPool(4)) {
            Callable<String> action = () -> accept(f).membershipId();
            var results = threads.invokeAll(List.of(action, action, action, action));
            Set<String> ids = new HashSet<>();
            for (var result : results) {
                ids.add(result.get());
            }
            assertThat(ids).hasSize(1);
        }
        assertThat(audits(f, "ACCEPT_INVITATION")).isEqualTo(1);
    }

    @Test
    void issueAndRevokeAreIdempotentAndRejectChangedPayloadOrOperator() {
        var f = issue(sponsor(), id(), MemberKind.GUEST);
        assertThat(runtime.invitations().issue(f.command).id()).isEqualTo(f.command.invitationId());
        var c = f.command;
        var conflict =
                new Issue(
                        c.commandId(),
                        c.invitationId(),
                        c.authority(),
                        c.issuer(),
                        c.subject(),
                        c.memberKind(),
                        c.tokenHash(),
                        c.expiresAt(),
                        c.membershipValidTo(),
                        "changed reason");
        code(() -> runtime.invitations().issue(conflict), COMMAND_CONFLICT);
        var wrong =
                new Authority(
                        "other-operator",
                        c.authority().tenantId(),
                        c.authority().sponsorMembershipId());
        code(
                () ->
                        runtime.invitations()
                                .revoke(new Revoke(id(), c.invitationId(), wrong, 1, "revoke")),
                MEMBERSHIP_UNAVAILABLE);
        var revoke = new Revoke(id(), c.invitationId(), c.authority(), 1, "revoke");
        assertThat(runtime.invitations().revoke(revoke).state()).isEqualTo(InvitationState.REVOKED);
        assertThat(runtime.invitations().revoke(revoke).version()).isEqualTo(2);
        code(() -> accept(f), INVALID_CREDENTIAL);
        assertThat(audits(f, "ISSUE_INVITATION")).isEqualTo(1);
        assertThat(audits(f, "REVOKE_INVITATION")).isEqualTo(1);
    }

    @Test
    void wrongProofSubjectIssuerAndExpiredLinkNeverBindIdentity() {
        var f = issue(sponsor(), id(), MemberKind.GUEST);
        code(
                () ->
                        runtime.invitations()
                                .accept(
                                        f.command.invitationId(),
                                        InvitationCommands.newToken(),
                                        login(f)),
                INVALID_CREDENTIAL);
        code(
                () ->
                        runtime.invitations()
                                .accept(
                                        f.command.invitationId(),
                                        f.token,
                                        new VerifiedLogin(f.command.issuer(), "other")),
                INVALID_CREDENTIAL);
        code(
                () ->
                        runtime.invitations()
                                .accept(
                                        f.command.invitationId(),
                                        f.token,
                                        new VerifiedLogin(
                                                "https://other.example", f.command.subject())),
                INVALID_CREDENTIAL);
        jdbc.update(
                "UPDATE auth_governance.invitation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                f.command.invitationId());
        code(() -> accept(f), INVALID_CREDENTIAL);
        assertThat(runtime.mapper().loginIdentity(f.command.issuer(), f.command.subject()))
                .isNull();
    }

    @Test
    void sponsorMustBeCurrentEmployeeOfSameTenant() {
        var sponsor = sponsor();
        var f = issue(sponsor, id(), MemberKind.PARTNER);
        runtime.lifecycle()
                .suspend(
                        new LifecycleCommand(
                                id(),
                                "test",
                                sponsor.tenantId(),
                                LifecycleCommand.Operation.SUSPEND_MEMBER,
                                sponsor.membershipId(),
                                1,
                                "sponsor stopped"));
        code(() -> accept(f), MEMBERSHIP_UNAVAILABLE);
        var other = sponsor();
        var cross =
                new Issue(
                        id(),
                        id(),
                        new Authority("test", other.tenantId(), sponsor.membershipId()),
                        "https://invite.example",
                        id(),
                        MemberKind.GUEST,
                        InvitationCommands.tokenHash(InvitationCommands.newToken()),
                        future(3600),
                        future(86400),
                        "cross tenant");
        code(() -> runtime.invitations().issue(cross), MEMBERSHIP_UNAVAILABLE);
        assertThat(runtime.mapper().loginIdentity(f.command.issuer(), f.command.subject()))
                .isNull();
    }

    @Test
    void activeAndSuspendedMembersCannotBeExpandedByAnotherInvitation() {
        var sponsor = sponsor();
        var f = issue(sponsor, id(), MemberKind.PARTNER);
        var member = accept(f);
        var another = issue(sponsor, f.command.subject(), MemberKind.PARTNER);
        code(() -> accept(another), BINDING_CONFLICT);
        runtime.lifecycle()
                .suspend(
                        new LifecycleCommand(
                                id(),
                                "test",
                                sponsor.tenantId(),
                                LifecycleCommand.Operation.SUSPEND_MEMBER,
                                member.membershipId(),
                                1,
                                "hold"));
        code(() -> accept(another), MEMBERSHIP_UNAVAILABLE);
        code(() -> accept(f), MEMBERSHIP_UNAVAILABLE);
        code(
                () ->
                        runtime.lifecycle()
                                .apply(
                                        new LifecycleCommand(
                                                id(),
                                                "test",
                                                sponsor.tenantId(),
                                                LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER,
                                                member.membershipId(),
                                                2,
                                                "cannot bypass hold")),
                VERSION_CONFLICT);
    }

    @Test
    void controlledLeaveAndRejoinIncreaseGenerationAndRejectOldProof() {
        var sponsor = sponsor();
        var f = issue(sponsor, id(), MemberKind.GUEST);
        var first = accept(f);
        var leave =
                new LifecycleCommand(
                        id(),
                        "test",
                        sponsor.tenantId(),
                        LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER,
                        first.membershipId(),
                        1,
                        "engagement ended");
        assertThat(runtime.lifecycle().apply(leave).status()).isEqualTo("LEFT");
        assertThat(runtime.lifecycle().apply(leave).version()).isEqualTo(2);
        code(() -> accept(f), MEMBERSHIP_UNAVAILABLE);
        var rejoin = issue(sponsor, f.command.subject(), MemberKind.GUEST);
        var second = accept(rejoin);
        assertThat(second.membershipId()).isEqualTo(first.membershipId());
        assertThat(second.membershipGeneration()).isEqualTo(2);
        assertThat(runtime.mapper().membership(second.membershipId()).version()).isEqualTo(3);
        code(() -> accept(f), GENERATION_MISMATCH);
        code(
                () ->
                        runtime.identity()
                                .contextForLogin(
                                        f.command.issuer(),
                                        f.command.subject(),
                                        sponsor.tenantId(),
                                        1L),
                GENERATION_MISMATCH);
        code(
                () ->
                        runtime.lifecycle()
                                .apply(
                                        new LifecycleCommand(
                                                id(),
                                                "test",
                                                sponsor.tenantId(),
                                                LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER,
                                                sponsor.membershipId(),
                                                1,
                                                "employee is OA owned")),
                BINDING_CONFLICT);
    }

    @Test
    void auditFailureRollsBackInvitationPrincipalBindingAndMembership() {
        var f = issue(sponsor(), id(), MemberKind.GUEST);
        String function = "invitation_fail_" + id().replace("-", "");
        jdbc.execute(
                "CREATE FUNCTION auth_governance."
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated invitation audit failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE INSERT ON auth_governance.audit_event FOR EACH ROW WHEN (NEW.operation='ACCEPT_INVITATION' AND NEW.target_id='"
                        + f.command.invitationId()
                        + "') EXECUTE FUNCTION auth_governance."
                        + function
                        + "()");
        assertThatThrownBy(() -> accept(f))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("isolated invitation audit failure");
        assertThat(runtime.mapper().loginIdentity(f.command.issuer(), f.command.subject()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM auth_governance.invitation WHERE id=?",
                                String.class,
                                f.command.invitationId()))
                .isEqualTo("PENDING");
        assertThat(audits(f, "ACCEPT_INVITATION")).isZero();
    }

    @Test
    void serviceOrGloballySuspendedIdentityCannotAccept() {
        for (String mode : List.of("service", "suspended")) {
            var employee = sponsor();
            var sponsor = sponsor();
            var f = issue(sponsor, employee.subject(), MemberKind.GUEST, employee.issuer());
            if (mode.equals("service")) {
                jdbc.update(
                        "UPDATE auth_governance.principal SET kind='SERVICE' WHERE id=?",
                        employee.principalId());
            } else {
                runtime.lifecycle()
                        .suspend(
                                new LifecycleCommand(
                                        id(),
                                        "global-operator",
                                        LifecycleCommand.GLOBAL_SCOPE,
                                        LifecycleCommand.Operation.SUSPEND_PRINCIPAL,
                                        employee.principalId(),
                                        1,
                                        "global hold"));
            }
            code(() -> accept(f), MEMBERSHIP_UNAVAILABLE);
        }
    }

    private BootstrapCommand sponsor() {
        var c =
                new BootstrapCommand(
                        id(),
                        "invite-fixture-" + id(),
                        id(),
                        "invite-" + id(),
                        id(),
                        "https://invite.example",
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "invitation-fixture",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(c);
        return c;
    }

    private Fixture issue(BootstrapCommand sponsor, String subject, MemberKind kind) {
        return issue(sponsor, subject, kind, "https://invite.example");
    }

    private Fixture issue(
            BootstrapCommand sponsor, String subject, MemberKind kind, String issuer) {
        String token = InvitationCommands.newToken();
        var command =
                new Issue(
                        id(),
                        id(),
                        new Authority(
                                sponsor.operatorRef(), sponsor.tenantId(), sponsor.membershipId()),
                        issuer,
                        subject,
                        kind,
                        InvitationCommands.tokenHash(token),
                        future(3600),
                        future(86400),
                        "isolated invitation test");
        runtime.invitations().issue(command);
        return new Fixture(command, token);
    }

    private InvitationGovernance.Acceptance accept(Fixture f) {
        return runtime.invitations().accept(f.command.invitationId(), f.token, login(f));
    }

    private VerifiedLogin login(Fixture f) {
        return new VerifiedLogin(f.command.issuer(), f.command.subject());
    }

    private int audits(Fixture f, String operation) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_governance.audit_event WHERE target_id=? AND operation=?",
                Integer.class,
                f.command.invitationId(),
                operation);
    }

    private static Instant future(long seconds) {
        return Instant.now().plusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void code(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable work,
            GovernanceException.Code code) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(
                        GovernanceException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null) {
            throw new IllegalStateException("isolated test configuration missing");
        }
        return value;
    }

    private record Fixture(Issue command, String token) {
        @Override
        public String toString() {
            return "InvitationFixture[proof=redacted]";
        }
    }
}
