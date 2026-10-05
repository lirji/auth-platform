package com.lrj.authz.governance.shared.persistence;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.access.application.CapabilityRetirement;
import com.lrj.authz.governance.access.application.PortalDiagnosticAuthority;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.application.CatalogRetirementProof;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.migration.application.RetirementReferenceExit;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.support.RetirementProofFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.*;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.State;
import com.lrj.authz.protocol.CapabilityRetirementDtos.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实PG验证未来／在途引用和最终状态；签名夹具不标为真实运行扫描。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityRetirementPostgresIT {
    private RoleMigrationFixture h;
    @TempDir Path temporary;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private CapabilityRetirement none() {
        return h.runtime.retirement(new CatalogRetirementProof(List.of()));
    }

    private String cap(F f) {
        return f.capabilities().getFirst();
    }

    private Report report(F f) {
        return none().report(f.login(), f.partition().applicationId(), cap(f));
    }

    private void deprecate(F f) {
        h.runtime
                .catalog()
                .changeLifecycle(
                        f.login(),
                        new Change(
                                f.partition().applicationId(),
                                cap(f),
                                State.DEPRECATED,
                                0L,
                                "退出新增",
                                id()));
    }

    private Retire command(F f, Report r) {
        return new Retire(
                f.partition().applicationId(),
                cap(f),
                r.lifecycleVersion(),
                r.basisHash(),
                "完整退出后最终退役",
                id());
    }

    private long count(Report r, String kind) {
        return r.counts().stream()
                .filter(c -> c.kind().equals(kind))
                .mapToLong(Count::blocking)
                .sum();
    }

    /** 使用现有身份创建独立无图分区，PG单独验证状态，不伪造图回执。 */
    private F bare() {
        var source = h.fixture();
        String app = "mg16-" + id();
        var p = new Partition(source.partition().tenantId(), app, "test");
        var caps = List.of(app + ".read", app + ".write");
        h.runtime
                .catalog()
                .register(app, source.owner().principalId(), "https://mg16.example", "mg16", id());
        h.runtime
                .catalog()
                .publish(
                        source.login(),
                        new Manifest(
                                "1",
                                app,
                                1,
                                caps.stream()
                                        .map(c -> new Capability(c, "store", Risk.NORMAL))
                                        .toList(),
                                List.of()),
                        id());
        h.runtime
                .access()
                .bootstrap(
                        p,
                        new Delegation(
                                source.owner().membershipId(), 1, AccessValues.json(caps), 3600),
                        "mg16",
                        id());
        var old =
                h.runtime
                        .access()
                        .createRole(source.login(), p, id(), "reader", 1, List.of(caps.getFirst()));
        var next =
                h.runtime
                        .access()
                        .createRole(source.login(), p, id(), "reader", 2, List.of(caps.getFirst()));
        return new F(source.owner(), source.member(), p, old, next, caps);
    }

    /** 受控运维单向退出真实委派，Owner原目录入口仍可用。 */
    private void exitDelegation(F f) {
        var exit = h.runtime.retirementExit();
        String hash =
                exit.inspect(
                        f.partition(),
                        RetirementReferenceExit.Kind.DELEGATION,
                        f.owner().membershipId());
        exit.exit(
                f.partition(),
                RetirementReferenceExit.Kind.DELEGATION,
                f.owner().membershipId(),
                hash,
                "mg16-ops",
                id(),
                "停止新增委派引用");
    }

    private CapabilityRetirement signed(F f) throws Exception {
        var r = report(f);
        var proof = new RetirementProofFixture(temporary, f.partition().applicationId());
        proof.write(proof.payload(r));
        return h.runtime.retirement(proof.reader());
    }

    @Test
    void zeroMenuStillBlocksFutureGrantAndUnavailableRecipient() {
        var f = bare();
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
                        Instant.now().plusSeconds(60),
                        Instant.now().plusSeconds(120));
        deprecate(f);
        h.jdbc.update(
                "UPDATE auth_governance.membership SET status='SUSPENDED',version=version+1 WHERE id=?",
                f.member().membershipId());
        var r = report(f);
        assertThat(count(r, "MENU")).isZero();
        assertThat(count(r, "GRANT")).isEqualTo(1);
        assertThat(count(r, "DELEGATION")).isEqualTo(1);
        assertThat(r.proofState()).isEqualTo("UNPROVEN");
        assertThat(r.eligible()).isFalse();
        assertThatThrownBy(() -> none().retire(f.login(), command(f, r)))
                .hasMessage("RETIREMENT_BLOCKED");
    }

    @Test
    void missingRuntimeProofNeverMeansZeroAndOwnerCannotSeeOthers() {
        var f = bare();
        exitDelegation(f);
        deprecate(f);
        var r = report(f);
        assertThat(r.counts()).allMatch(c -> c.blocking() == 0);
        assertThat(r.proofReason()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.eligible()).isFalse();
        assertThatThrownBy(() -> none().retire(f.login(), command(f, r)))
                .hasMessage("RETIREMENT_BLOCKED");
        var member = new VerifiedLogin(f.member().issuer(), f.member().subject());
        assertThatThrownBy(() -> none().report(member, f.partition().applicationId(), cap(f)))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> none().ownerCatalog(member, f.partition().applicationId()))
                .hasMessage("ACCESS_DENIED");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.capability_retirement_evidence WHERE application_id=?",
                                Integer.class,
                                f.partition().applicationId()))
                .isZero();
    }

    @Test
    void finalRetirementKeepsHistoryAndCannotRestoreOrReplayIntoNewAuthorization()
            throws Exception {
        var f = bare();
        exitDelegation(f);
        deprecate(f);
        var before = h.runtime.catalog().current(f.login(), f.partition().applicationId());
        String roles =
                h.jdbc.queryForObject(
                        "SELECT string_agg(content_hash,',' ORDER BY id) FROM auth_governance.role_version WHERE application_id=?",
                        String.class,
                        f.partition().applicationId());
        var service = signed(f);
        var r = service.report(f.login(), f.partition().applicationId(), cap(f));
        assertThat(r.eligible()).isTrue();
        var command = command(f, r);
        var first = service.retire(f.login(), command);
        assertThat(first.current().state()).isEqualTo(State.RETIRED);
        assertThat(service.retire(f.login(), command)).isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                service.retire(
                                        f.login(),
                                        new Retire(
                                                command.applicationId(),
                                                command.capability(),
                                                command.expectedVersion(),
                                                command.basisHash(),
                                                "改体",
                                                command.commandId())))
                .hasMessage("COMMAND_CONFLICT");
        assertThat(h.runtime.catalog().current(f.login(), f.partition().applicationId()))
                .isEqualTo(before);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT string_agg(content_hash,',' ORDER BY id) FROM auth_governance.role_version WHERE application_id=?",
                                String.class,
                                f.partition().applicationId()))
                .isEqualTo(roles);
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .catalog()
                                        .changeLifecycle(
                                                f.login(),
                                                new Change(
                                                        command.applicationId(),
                                                        cap(f),
                                                        State.ACTIVE,
                                                        2L,
                                                        "不可恢复",
                                                        id())))
                .hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .access()
                                        .createRole(
                                                f.login(),
                                                f.partition(),
                                                id(),
                                                "reader",
                                                3,
                                                List.of(cap(f))))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.capability_lifecycle SET state='ACTIVE',version=version+1 WHERE application_id=?",
                                        f.partition().applicationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "DELETE FROM auth_governance.capability_retirement_evidence WHERE application_id=?",
                                        f.partition().applicationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .catalog()
                                        .publish(
                                                f.login(),
                                                new Manifest(
                                                        "1",
                                                        before.application(),
                                                        2,
                                                        before.capabilities(),
                                                        List.of(
                                                                new Menu(
                                                                        "again",
                                                                        null,
                                                                        "/again",
                                                                        List.of(cap(f))))),
                                                id()))
                .hasMessage("CAPABILITY_DEPRECATED");
    }

    @Test
    void ownerEntrySurvivesLastDelegationExitWithoutGrantingDiagnostics() throws Exception {
        var f = bare();
        deprecate(f);
        h.jdbc.update(
                "UPDATE auth_governance.access_delegation SET enabled=false WHERE application_id=?",
                f.partition().applicationId());
        assertThat(none().ownerCatalog(f.login(), f.partition().applicationId()).capabilities())
                .allMatch(c -> !c.grantable());
        assertThat(count(report(f), "DELEGATION")).isZero();
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .portalPermissions(List.of())
                                        .retirementReferences(
                                                f.login(), f.partition(), cap(f), null, none()))
                .hasMessage("ACCESS_DENIED");
        var service = signed(f);
        assertThat(
                        service.retire(
                                        f.login(),
                                        command(
                                                f,
                                                service.report(
                                                        f.login(),
                                                        f.partition().applicationId(),
                                                        cap(f))))
                                .current()
                                .state())
                .isEqualTo(State.RETIRED);
    }

    @Test
    void signedVerifierRefusesForgeryExpiryMissingTargetsAndChangedBasis() throws Exception {
        var f = bare();
        exitDelegation(f);
        deprecate(f);
        var r = report(f);
        var proof = new RetirementProofFixture(temporary, f.partition().applicationId());
        var service = h.runtime.retirement(proof.reader());
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).proofReason())
                .isEqualTo("PROOF_UNAVAILABLE");
        var n = proof.payload(r);
        n.put("valid_until", Instant.now().minusSeconds(1).toString());
        proof.write(n);
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).proofReason())
                .isEqualTo("EXPIRED_OR_INVALID_TIME");
        n = proof.payload(r);
        n.put("deployments", List.of());
        proof.write(n);
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).proofReason())
                .isEqualTo("TARGET_MISMATCH");
        n = proof.payload(r);
        n.put("manifest_version", 2);
        proof.write(n);
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).proofReason())
                .isEqualTo("BASIS_MISMATCH");
        n = proof.payload(r);
        proof.write(n);
        String raw = java.nio.file.Files.readString(proof.file);
        java.nio.file.Files.writeString(
                proof.file, raw.replace("signature\":\"", "signature\":\"AAAA"));
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).proofState())
                .isEqualTo("UNPROVEN");
        proof.write(proof.payload(r));
        assertThat(service.report(f.login(), f.partition().applicationId(), cap(f)).eligible())
                .isTrue();
        assertThat(
                        CatalogRetirementProof.from(proof.properties())
                                .verify(
                                        r.applicationId(),
                                        r.capability(),
                                        r.manifestVersion(),
                                        r.contentHash(),
                                        r.presentationHash(),
                                        r.lifecycleVersion())
                                .proven())
                .isTrue();
        var config = proof.properties();
        config.remove("catalog.retirement.count");
        assertThatThrownBy(() -> CatalogRetirementProof.from(config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingRequestsPoliciesAndMigrationRemainVisible() {
        var f = h.fixture();
        var policy = h.oaPolicy(f, f.newRole());
        var request = h.oaRequest(f, policy);
        var grant = h.sqlActive(f, true);
        var migration = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant));
        deprecate(f);
        var r = report(f);
        assertThat(count(r, "REQUEST")).isEqualTo(1);
        assertThat(count(r, "POLICY")).isEqualTo(1);
        assertThat(count(r, "MIGRATION")).isEqualTo(1);
        assertThat(count(r, "RECEIPT")).isGreaterThan(0);
        h.cancel(f, migration);
        h.runtime
                .requests()
                .cancel(
                        new VerifiedLogin(f.member().issuer(), f.member().subject()),
                        f.partition(),
                        id(),
                        request.id(),
                        request.stateVersion());
        assertThat(count(report(f), "MIGRATION")).isZero();
        assertThat(count(report(f), "REQUEST")).isZero();
    }

    @Test
    void concurrentExecutionReferenceAndRetirementHaveOneDatabaseOrder() throws Exception {
        var f = bare();
        exitDelegation(f);
        deprecate(f);
        var service = signed(f);
        var r = service.report(f.login(), f.partition().applicationId(), cap(f));
        var pool = Executors.newSingleThreadExecutor();
        try (var connection =
                java.sql.DriverManager.getConnection(
                        h.database.jdbcUrl(), h.database.username(), h.database.password())) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT application_id FROM auth_governance.application_catalog WHERE application_id=? FOR SHARE")) {
                lock.setString(1, f.partition().applicationId());
                lock.executeQuery().close();
            }
            var pending = pool.submit(() -> service.retire(f.login(), command(f, r)));
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO auth_governance.execution_reference(id,caller,application,environment,member,request_id,fingerprint,context_json,capability,resource_type,paths_json,expires_at) VALUES(?,'mg16',?,'test',?,?,'fixed','{}',?,'store','{}',clock_timestamp()+interval '60 seconds')")) {
                insert.setString(1, id());
                insert.setString(2, f.partition().applicationId());
                insert.setString(3, f.member().membershipId());
                insert.setString(4, id());
                insert.setString(5, cap(f));
                insert.executeUpdate();
            }
            connection.commit();
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(GovernanceException.class);
            assertThat(report(f).lifecycleState()).isEqualTo(State.DEPRECATED);
            assertThat(count(report(f), "EXECUTION")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void paginatedReferencesRequireIndependentDiagnosticsAndDetectChangedBasis() {
        var f = bare();
        for (int i = 3; i < 109; i++)
            h.runtime
                    .access()
                    .createRole(f.login(), f.partition(), id(), "reader", i, List.of(cap(f)));
        var authority = new PortalDiagnosticAuthority(f.partition(), f.owner().membershipId(), 1);
        var portal = h.runtime.portalPermissions(List.of(authority));
        var first = portal.retirementReferences(f.login(), f.partition(), cap(f), null, none());
        assertThat(first.items()).hasSize(100);
        assertThat(first.nextCursor()).isNotNull();
        var second =
                portal.retirementReferences(
                        f.login(), f.partition(), cap(f), first.nextCursor(), none());
        assertThat(second.items()).hasSize(9);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.basisHash()).isEqualTo(first.basisHash());
        h.runtime
                .access()
                .createRole(f.login(), f.partition(), id(), "reader", 109, List.of(cap(f)));
        assertThatThrownBy(
                        () ->
                                portal.retirementReferences(
                                        f.login(),
                                        f.partition(),
                                        cap(f),
                                        first.nextCursor(),
                                        none()))
                .hasMessage("VERSION_CONFLICT");
        var r = report(f);
        assertThat(
                        r.counts().stream()
                                .filter(c -> c.kind().equals("ROLE"))
                                .findFirst()
                                .orElseThrow()
                                .historical())
                .isEqualTo(109);
        assertThat(r.toString()).doesNotContain(f.member().membershipId());
    }

    @Test
    void operatorExitHasExactHashReplayAndAuditWithoutChangingGrantOrRequest() {
        var f = h.fixture();
        var grant = h.grant(f, true);
        var policy = h.oaPolicy(f, f.newRole());
        var request = h.oaRequest(f, policy);
        var exit = h.runtime.retirementExit();
        String command = id(),
                hash =
                        exit.inspect(
                                f.partition(), RetirementReferenceExit.Kind.POLICY, policy.id());
        assertThatThrownBy(
                        () ->
                                exit.exit(
                                        f.partition(),
                                        RetirementReferenceExit.Kind.POLICY,
                                        policy.id(),
                                        "a".repeat(64),
                                        "mg16-ops",
                                        command,
                                        "固定退出"))
                .hasMessage("VERSION_CONFLICT");
        exit.exit(
                f.partition(),
                RetirementReferenceExit.Kind.POLICY,
                policy.id(),
                hash,
                "mg16-ops",
                command,
                "固定退出");
        exit.exit(
                f.partition(),
                RetirementReferenceExit.Kind.POLICY,
                policy.id(),
                hash,
                "mg16-ops",
                command,
                "固定退出");
        assertThatThrownBy(
                        () ->
                                exit.exit(
                                        f.partition(),
                                        RetirementReferenceExit.Kind.POLICY,
                                        policy.id(),
                                        hash,
                                        "mg16-ops",
                                        command,
                                        "改体"))
                .hasMessage("COMMAND_CONFLICT");
        assertThat(h.current(f, grant.id()).state()).isEqualTo(GrantState.PENDING);
        assertThat(
                        h.runtime
                                .requests()
                                .owned(
                                        new VerifiedLogin(
                                                f.member().issuer(), f.member().subject()),
                                        f.partition(),
                                        request.id())
                                .state()
                                .code())
                .isEqualTo("SUBMITTED");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.retirement_reference_exit WHERE command_id=? AND before_hash<>after_hash AND reason='固定退出'",
                                Integer.class,
                                command))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "DELETE FROM auth_governance.retirement_reference_exit WHERE command_id=?",
                                        command))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void legacySqlCannotRetireWithoutVerifiedEvidenceAndNewFieldsAreCommented() {
        var f = bare();
        exitDelegation(f);
        deprecate(f);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.capability_lifecycle SET state='RETIRED',version=version+1 WHERE application_id=?",
                                        f.partition().applicationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM pg_attribute WHERE attrelid='auth_governance.capability_retirement_evidence'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT obj_description('auth_governance.capability_retirement_evidence'::regclass)",
                                String.class))
                .isNotBlank();
    }
}
