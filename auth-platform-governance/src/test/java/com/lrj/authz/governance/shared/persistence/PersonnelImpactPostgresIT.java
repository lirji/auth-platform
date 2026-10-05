package com.lrj.authz.governance.shared.persistence;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.access.application.PortalDiagnosticAuthority;
import com.lrj.authz.governance.access.application.PortalPermissions;
import com.lrj.authz.governance.directory.application.DirectoryAuthority;
import com.lrj.authz.governance.identity.application.LifecycleCommand;
import com.lrj.authz.governance.migration.application.RetirementReferenceExit;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.*;
import com.lrj.authz.protocol.DirectoryEvents.*;
import com.lrj.authz.protocol.PersonnelImpactDtos.*;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 真PG证明事件原子前后值、历史来源、游标依据和诊断边界，不用SQL ACTIVE证明实际访问。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonnelImpactPostgresIT {
    private RoleMigrationFixture h;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private PortalPermissions portal(F f) {
        return h.runtime.portalPermissions(
                List.of(new PortalDiagnosticAuthority(f.partition(), f.owner().membershipId(), 1)));
    }

    private Report read(F f) {
        return portal(f)
                .personnelImpact(
                        f.login(),
                        f.partition(),
                        f.member().membershipId(),
                        null,
                        null,
                        h.runtime.personnelImpact());
    }

    private Event event(F f, G g, long sequence, long version, String status, String org) {
        var payload =
                new Payload(
                        new Employee(
                                "1",
                                f.member().subject(),
                                status,
                                List.of(
                                        new Assignment(
                                                "1", org, "PRIMARY", false, "2020-01-01", null)),
                                List.of()),
                        null,
                        null);
        var a = g.authority();
        return new Event(
                1,
                id(),
                a.source(),
                a.environment(),
                a.sourceTenantRef(),
                sequence,
                DirectoryAggregateType.EMPLOYEE,
                "1",
                version,
                Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
                null,
                payload,
                com.lrj.authz.protocol.DirectoryEvents.payloadHash(
                        DirectoryAggregateType.EMPLOYEE, payload));
    }

    private void accept(G g, Event e) {
        h.runtime.directory().accept(g.authority(), e);
    }

    private Change employee(Report r, long version) {
        return r.changes().stream()
                .filter(
                        c ->
                                c.aggregateType().equals("EMPLOYEE")
                                        && c.aggregateVersion() == version)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void realBeforeAfterReplayAndObsoleteEventCannotReviveDirectory() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        var first = read(f);
        assertThat(employee(first, 1).before()).isNull();
        assertThat(first.directorySources()).allMatch(s -> s.sourceSync() == SourceSync.UNKNOWN);
        assertThat(first.directories()).allMatch(d -> !d.historyMissing());
        var left = event(f, g, 4, 3, "LEFT", "2");
        accept(g, left);
        var r = read(f);
        var c = employee(r, 3);
        assertThat(c.before().facts().assignments().getFirst().orgId()).isEqualTo("1");
        assertThat(c.after().facts().assignments().getFirst().orgId()).isEqualTo("2");
        assertThat(c.before().member().status()).isEqualTo("ACTIVE");
        assertThat(c.after().member().status()).isEqualTo("LEFT");
        assertThat(h.runtime.directory().accept(g.authority(), left).replay()).isTrue();
        assertThat(read(f).changes()).hasSize(r.changes().size());
        accept(g, event(f, g, 5, 2, "ACTIVE", "1"));
        r = read(f);
        assertThat(r.target().status()).isEqualTo("LEFT");
        assertThat(employee(r, 2).outcome()).isEqualTo("OBSOLETE");
        assertThat(employee(r, 2).before()).isEqualTo(employee(r, 2).after());
        assertThat(r.directories().getFirst().aggregateVersion()).isEqualTo(3);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_change_evidence WHERE source_id=?",
                                Integer.class,
                                g.authority().id()))
                .isEqualTo(5);
        String json =
                h.jdbc.queryForObject(
                        "SELECT after_json::text FROM auth_governance.directory_change_evidence WHERE source_id=? AND partition_sequence=4",
                        String.class,
                        g.authority().id());
        assertThat(json)
                .doesNotContain(
                        "user_id",
                        "principal_id",
                        "login_subject",
                        "reporting_lines",
                        f.member().subject());
    }

    @Test
    void leaveRejoinRetainsOldPersonalSourcesAndHistoricalGroupRelations() throws Exception {
        var f = h.fixture();
        var direct = h.grant(f, true);
        var g = h.directoryGroup(f);
        var group = h.groupGrant(f, g);
        var policy = h.oaPolicy(f, f.oldRole());
        var request = h.oaRequest(f, policy);
        h.decideOa(f, policy, request, "APPROVED");
        accept(g, event(f, g, 4, 2, "LEFT", "2"));
        accept(g, event(f, g, 5, 3, "ACTIVE", "2"));
        var r = read(f);
        assertThat(r.target().generation()).isEqualTo(2);
        assertThat(r.sources())
                .extracting(s -> s.grant().sourceType())
                .contains("DIRECT", "OA_REQUEST", "GROUP");
        assertThat(
                        r.sources().stream()
                                .filter(s -> s.grant().grantId().equals(direct.id()))
                                .findFirst()
                                .orElseThrow()
                                .currentGeneration())
                .isFalse();
        var groupSource =
                r.sources().stream()
                        .filter(s -> s.grant().grantId().equals(group.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(groupSource.groupRelations()).anyMatch(x -> x.generation() == 1 && !x.current());
        assertThat(groupSource.grant().effectiveState()).isNotEqualTo("ACTIVE");
        assertThat(h.current(f, direct.id()).state().code()).isEqualTo("PENDING");
        assertThat(h.runtime.access().state(f.login(), f.partition(), null, null).grants())
                .hasSize(3);
    }

    @Test
    void manualMemberSuspensionAndGlobalSuspensionRemainSeparate() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        h.runtime
                .lifecycle()
                .apply(
                        new LifecycleCommand(
                                id(),
                                "mg17",
                                f.partition().tenantId(),
                                LifecycleCommand.Operation.SUSPEND_MEMBER,
                                f.member().membershipId(),
                                1,
                                "手工暂停保护"));
        accept(g, event(f, g, 4, 2, "LEFT", "2"));
        accept(g, event(f, g, 5, 3, "ACTIVE", "2"));
        var r = read(f);
        assertThat(r.target().status()).isEqualTo("SUSPENDED");
        assertThat(r.target().generation()).isEqualTo(1);
        assertThat(r.directories().getFirst().facts().status()).isEqualTo("ACTIVE");
        assertThat(employee(r, 3).after().member().status()).isEqualTo("SUSPENDED");
        assertThat(r.target().principalStatus()).isEqualTo("ACTIVE");
        h.runtime
                .lifecycle()
                .apply(
                        new LifecycleCommand(
                                id(),
                                "mg17",
                                "GLOBAL",
                                LifecycleCommand.Operation.SUSPEND_PRINCIPAL,
                                f.member().principalId(),
                                1,
                                "全局主体暂停"));
        r = read(f);
        assertThat(r.target().principalStatus()).isEqualTo("SUSPENDED");
        assertThat(r.target().status()).isEqualTo("SUSPENDED");
    }

    @Test
    void changedVersionConflictsQuarantineWithoutEvidenceOrCurrentMutation() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        var bad = event(f, g, 4, 1, "LEFT", "2");
        assertThatThrownBy(() -> accept(g, bad)).hasMessage("BINDING_CONFLICT");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_change_evidence WHERE source_id=?",
                                Integer.class,
                                g.authority().id()))
                .isEqualTo(3);
        assertThat(h.runtime.directory().inspect(g.authority()).lastSequence()).isEqualTo(3);
        var r = read(f);
        assertThat(r.directorySources().getFirst().quarantined()).isTrue();
        assertThat(r.directorySources().getFirst().conflictReasons())
                .containsExactly("VERSION_CHANGED");
        assertThat(r.directories().getFirst().facts().status()).isEqualTo("ACTIVE");
    }

    @Test
    void pagingBindsBothKindsTargetPartitionAndCurrentBasis() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        for (int n = 2; n <= 106; n++)
            accept(g, event(f, g, n + 2, n, "ACTIVE", n % 2 == 0 ? "1" : "2"));
        // 正式入口限制100个活动来源；先实际撤销一个，再证明101条当前＋历史来源分页。
        for (int n = 0; n < 101; n++) {
            var grant = h.grant(f, true);
            if (n == 0)
                h.runtime
                        .access()
                        .strictRevoke(f.login(), f.partition(), id(), grant.id(), grant.version());
        }
        var first = read(f);
        assertThat(first.changes()).hasSize(100);
        assertThat(first.sources()).hasSize(100);
        assertThat(first.nextChangeCursor()).isNotNull();
        assertThat(first.nextSourceCursor()).isNotNull();
        var second =
                portal(f)
                        .personnelImpact(
                                f.login(),
                                f.partition(),
                                f.member().membershipId(),
                                first.nextChangeCursor(),
                                first.nextSourceCursor(),
                                h.runtime.personnelImpact());
        assertThat(second.changes()).hasSize(8);
        assertThat(second.sources()).hasSize(1);
        assertThat(second.nextChangeCursor()).isNull();
        assertThat(second.nextSourceCursor()).isNull();
        assertThatThrownBy(
                        () ->
                                portal(f)
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                f.owner().membershipId(),
                                                first.nextChangeCursor(),
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                portal(f)
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                f.member().membershipId(),
                                                first.nextSourceCursor(),
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("VERSION_CONFLICT");
        accept(g, event(f, g, 109, 107, "LEFT", "2"));
        assertThatThrownBy(
                        () ->
                                portal(f)
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                f.member().membershipId(),
                                                first.nextChangeCursor(),
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("VERSION_CONFLICT");
    }

    @Test
    void qualificationMissingTargetsAndForeignTargetsRefuseWithAudit() {
        var f = h.fixture();
        var foreign = h.fixture();
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .portalPermissions(List.of())
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                f.member().membershipId(),
                                                null,
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                portal(f)
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                foreign.member().membershipId(),
                                                null,
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                portal(f)
                                        .personnelImpact(
                                                f.login(),
                                                f.partition(),
                                                id(),
                                                null,
                                                null,
                                                h.runtime.personnelImpact()))
                .hasMessage("ACCESS_DENIED");
        assertThat(read(f).sources()).isEmpty();
        assertThat(read(f).directories()).isEmpty();
        var exit = h.runtime.retirementExit();
        exit.exit(
                f.partition(),
                RetirementReferenceExit.Kind.DELEGATION,
                f.owner().membershipId(),
                exit.inspect(
                        f.partition(),
                        RetirementReferenceExit.Kind.DELEGATION,
                        f.owner().membershipId()),
                "mg17",
                id(),
                "读取资格退出");
        assertThatThrownBy(() -> read(f)).hasMessage("ACCESS_DENIED");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.portal_diagnostic_audit WHERE tenant_id=? AND operation='READ_PERSONNEL_IMPACT' AND outcome='DENIED'",
                                Integer.class,
                                f.partition().tenantId()))
                .isEqualTo(4);
    }

    @Test
    void appliedAuditFailureRollsBackEvidenceInboxCurrentAndCheckpoint() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        String name = "mg17_fault_" + id().replace("-", "");
        h.jdbc.execute(
                "CREATE FUNCTION auth_governance."
                        + name
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'MG17_TEST_AUDIT_FAILURE'; END $$");
        h.jdbc.execute(
                "CREATE TRIGGER "
                        + name
                        + " BEFORE INSERT ON auth_governance.audit_event FOR EACH ROW WHEN (NEW.tenant_id='"
                        + f.partition().tenantId()
                        + "' AND NEW.operation='DIRECTORY_CONSUME') EXECUTE FUNCTION auth_governance."
                        + name
                        + "()");
        assertThatThrownBy(() -> accept(g, event(f, g, 4, 2, "LEFT", "2")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(h.runtime.directory().checkpoint(g.authority()).lastSequence()).isEqualTo(3);
        var r = read(f);
        assertThat(r.target().status()).isEqualTo("ACTIVE");
        assertThat(r.directories().getFirst().aggregateVersion()).isEqualTo(1);
        assertThat(r.changes()).hasSize(2);
        // 无关组织2从未与该人员关联，不泄露其事件；来源总证据仍必须保持原三条。
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_change_evidence WHERE source_id=?",
                                Integer.class,
                                g.authority().id()))
                .isEqualTo(3);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_inbox WHERE source_id=? AND partition_sequence=4",
                                Integer.class,
                                g.authority().id()))
                .isZero();
    }

    @Test
    void evidenceCannotBeChangedAndEveryColumnHasChineseDocumentation() {
        var f = h.fixture();
        var g = h.directoryGroup(f);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.directory_change_evidence SET outcome='OBSOLETE' WHERE source_id=?",
                                        g.authority().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM pg_attribute WHERE attrelid='auth_governance.directory_change_evidence'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT obj_description('auth_governance.directory_change_evidence'::regclass)",
                                String.class))
                .isNotBlank();
    }

    @Test
    void firstObservedHigherVersionExplicitlyMarksMissingPriorHistory() {
        var f = h.fixture();
        var a =
                new DirectoryAuthority(
                        id(),
                        "mg17-" + id(),
                        "test",
                        "1",
                        f.partition().tenantId(),
                        f.member().issuer());
        h.runtime.directory().register(a, "mg17");
        var g = new G(a, null);
        accept(g, event(f, g, 1, 7, "ACTIVE", "1"));
        var r = read(f);
        assertThat(r.directories().getFirst().historyMissing()).isTrue();
        assertThat(r.changes())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.before()).isNull();
                            assertThat(c.aggregateVersion()).isEqualTo(7);
                        });
    }

    @Test
    void actualValidityBoundaryChangesBasisWithoutChangingAnySourceRow()
            throws InterruptedException {
        var f = h.fixture();
        Instant from = Instant.now().plusSeconds(2);
        var grant =
                h.runtime
                        .access()
                        .grant(
                                f.login(),
                                f.partition(),
                                id(),
                                f.member().membershipId(),
                                1,
                                f.oldRole().id(),
                                "TENANT_ALL",
                                id(),
                                from,
                                from.plusSeconds(600));
        var before = read(f);
        // 等待真实时间跨过开始边界，不改数据库时间或把同一行误认成永远相同的依据。
        while (Instant.now().isBefore(from.plusMillis(10))) Thread.sleep(25);
        var after = read(f);
        assertThat(after.basisHash()).isNotEqualTo(before.basisHash());
        assertThat(h.current(f, grant.id()).version()).isEqualTo(grant.version());
        assertThat(h.current(f, grant.id()).state()).isEqualTo(grant.state());
    }
}
