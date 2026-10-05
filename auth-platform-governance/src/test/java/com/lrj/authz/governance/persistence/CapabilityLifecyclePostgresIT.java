package com.lrj.authz.governance.persistence;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.RequestModels.Policy;
import com.lrj.authz.governance.domain.RequestModels.Request;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.Change;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.Mutation;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.State;
import com.lrj.authz.protocol.ScopeDtos.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实PG验证停止新增、历史保留、旧写节点栅栏与锁顺序；不把SQL ACTIVE当作图生效。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityLifecyclePostgresIT {
    private RoleMigrationFixture h;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private Change change(F f, State state, long version) {
        return new Change(
                f.partition().applicationId(),
                f.capabilities().getFirst(),
                state,
                version,
                "隔离能力弃用验收",
                id());
    }

    private Mutation deprecate(F f) {
        return h.runtime.catalog().changeLifecycle(f.login(), change(f, State.DEPRECATED, 0));
    }

    private VerifiedLogin member(F f) {
        return new VerifiedLogin(f.member().issuer(), f.member().subject());
    }

    private Rule rule() {
        return new Rule(
                1, "store", List.of(new Clause(Kind.SPECIFIED_STORES, List.of("S1"), false)));
    }

    private Policy policy(F f, String command) {
        return h.runtime
                .requests()
                .registerPolicy(
                        f.login(),
                        f.partition(),
                        command,
                        f.newRole().id(),
                        rule(),
                        600,
                        f.owner().membershipId(),
                        1,
                        1);
    }

    private Request submit(F f, Policy policy, String command, Instant from, Instant to) {
        return h.runtime
                .requests()
                .submit(member(f), f.partition(), command, policy.id(), from, to, "原范围申请");
    }

    private String raw(String table, String column, String key) {
        return h.jdbc.queryForObject(
                "SELECT " + column + " FROM auth_governance." + table + " WHERE id=?",
                String.class,
                key);
    }

    @Test
    void deprecationChangesOptionsButKeepsAllFixedHistory() {
        var f = h.fixture();
        var g = h.sqlActive(f, true);
        var original = raw("access_grant", "to_jsonb(access_grant)::text", g.id());
        var scope = h.scope(g.id());
        var role = raw("role_version", "to_jsonb(role_version)::text", f.oldRole().id());
        var manifest = h.runtime.catalog().current(f.login(), f.partition().applicationId());
        var before = h.runtime.portalManagement().publishedCatalog(f.login(), f.partition());
        assertThat(before.lifecycleOwner()).isTrue();
        assertThat(before.capabilities().getFirst().lifecycleVersion()).isZero();
        var receipt = deprecate(f);
        var after = h.runtime.portalManagement().publishedCatalog(f.login(), f.partition());
        assertThat(after.viewHash()).isNotEqualTo(before.viewHash());
        assertThat(after.capabilities().getFirst().lifecycleState()).isEqualTo(State.DEPRECATED);
        assertThat(after.capabilities().getFirst().grantable()).isFalse();
        assertThat(after.capabilities().getFirst().disabled()).isFalse();
        assertThat(receipt.current().version()).isEqualTo(1);
        assertThat(raw("access_grant", "to_jsonb(access_grant)::text", g.id())).isEqualTo(original);
        assertThat(h.scope(g.id())).isEqualTo(scope);
        assertThat(raw("role_version", "to_jsonb(role_version)::text", f.oldRole().id()))
                .isEqualTo(role);
        assertThat(h.runtime.catalog().current(f.login(), f.partition().applicationId()))
                .isEqualTo(manifest);
        h.runtime
                .catalog()
                .publish(
                        f.login(),
                        new Manifest(
                                "1",
                                manifest.application(),
                                2,
                                manifest.capabilities(),
                                manifest.menus()),
                        id());
        assertThat(
                        h.runtime
                                .portalManagement()
                                .publishedCatalog(f.login(), f.partition())
                                .capabilities()
                                .getFirst()
                                .lifecycleState())
                .isEqualTo(State.DEPRECATED);
    }

    @Test
    void currentOwnerRequiredEvenForReplayAndRestoreDoesNotClearEmergencyStop() {
        var f = h.fixture();
        h.runtime
                .access()
                .bootstrap(
                        f.partition(),
                        new Delegation(
                                f.member().membershipId(),
                                1,
                                AccessValues.json(f.capabilities()),
                                3600),
                        "mg15",
                        id());
        var cmd = change(f, State.DEPRECATED, 0);
        var original = h.runtime.catalog().changeLifecycle(f.login(), cmd);
        assertThat(
                        h.runtime
                                .portalManagement()
                                .publishedCatalog(member(f), f.partition())
                                .lifecycleOwner())
                .isFalse();
        assertThatThrownBy(() -> h.runtime.catalog().changeLifecycle(member(f), cmd))
                .hasMessage("ACCESS_DENIED");
        h.runtime
                .catalog()
                .changeCapability(
                        f.login(),
                        f.partition().applicationId(),
                        f.capabilities().getFirst(),
                        true,
                        0,
                        "紧急停用独立控制",
                        id());
        h.runtime.catalog().changeLifecycle(f.login(), change(f, State.ACTIVE, 1));
        var replay = h.runtime.catalog().changeLifecycle(f.login(), cmd);
        assertThat(replay.receipt()).isEqualTo(original.receipt());
        assertThat(replay.current().state()).isEqualTo(State.ACTIVE);
        assertThat(replay.current().version()).isEqualTo(2);
        assertThat(
                        h.runtime
                                .portalManagement()
                                .publishedCatalog(f.login(), f.partition())
                                .capabilities()
                                .getFirst()
                                .disabled())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .catalog()
                                        .changeLifecycle(
                                                f.login(),
                                                new Change(
                                                        cmd.applicationId(),
                                                        cmd.capability(),
                                                        cmd.state(),
                                                        cmd.expectedVersion(),
                                                        "另一个原因",
                                                        cmd.commandId())))
                .hasMessage("COMMAND_CONFLICT");
        h.jdbc.update(
                "UPDATE auth_governance.principal SET status='SUSPENDED',version=version+1 WHERE id=?",
                f.owner().principalId());
        assertThatThrownBy(() -> h.runtime.catalog().changeLifecycle(f.login(), cmd))
                .hasMessage("MEMBERSHIP_UNAVAILABLE");
    }

    @Test
    void newRoleDirectGroupDelegationPolicyAndRequestAreRejected() {
        var f = h.fixture();
        var policy = policy(f, id());
        var authority =
                new DirectoryAuthority(
                        id(),
                        "mg15-" + id(),
                        "test",
                        "1",
                        f.partition().tenantId(),
                        f.owner().issuer());
        h.runtime.directory().register(authority, "mg15");
        h.jdbc.update(
                "UPDATE auth_governance.directory_source SET business_zone='UTC' WHERE id=?",
                authority.id());
        String group = id();
        h.jdbc.update(
                "INSERT INTO auth_governance.directory_group(id,source_id,tenant_id,org_ref,active) VALUES(?,?,?,?,true)",
                group,
                authority.id(),
                f.partition().tenantId(),
                id());
        var originalGroup =
                h.runtime
                        .access()
                        .grantGroup(
                                f.login(),
                                f.partition(),
                                id(),
                                group,
                                f.newRole().id(),
                                rule(),
                                id(),
                                Instant.now().minusSeconds(1),
                                Instant.now().plusSeconds(60));
        deprecate(f);
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
                                                f.capabilities()))
                .hasMessage("CAPABILITY_DEPRECATED");
        assertThatThrownBy(() -> h.grant(f, true)).hasMessage("CAPABILITY_DEPRECATED");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .access()
                                        .grantGroup(
                                                f.login(),
                                                f.partition(),
                                                id(),
                                                group,
                                                f.newRole().id(),
                                                rule(),
                                                id(),
                                                Instant.now(),
                                                Instant.now().plusSeconds(60)))
                .hasMessage("CAPABILITY_DEPRECATED");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .access()
                                        .bootstrap(
                                                f.partition(),
                                                new Delegation(
                                                        f.member().membershipId(),
                                                        1,
                                                        AccessValues.json(f.capabilities()),
                                                        3600),
                                                "mg15",
                                                id()))
                .hasMessage("CAPABILITY_DEPRECATED");
        assertThatThrownBy(() -> policy(f, id())).hasMessage("CAPABILITY_DEPRECATED");
        assertThatThrownBy(
                        () -> submit(f, policy, id(), Instant.now(), Instant.now().plusSeconds(60)))
                .hasMessage("CAPABILITY_DEPRECATED");
        assertThat(h.runtime.requests().policies(member(f), f.partition(), null)).isEmpty();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",
                                Integer.class,
                                f.partition().tenantId()))
                .isEqualTo(1);
        assertThat(h.current(f, originalGroup.id()).groupId()).isEqualTo(group);
        // 弃用的应用作用域覆盖本实例另一环境，不能通过改environment重新建立新增使用。
        var otherEnv =
                new Partition(f.partition().tenantId(), f.partition().applicationId(), "staging");
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .access()
                                        .bootstrap(
                                                otherEnv,
                                                new Delegation(
                                                        f.owner().membershipId(),
                                                        1,
                                                        AccessValues.json(f.capabilities()),
                                                        3600),
                                                "mg15",
                                                id()))
                .hasMessage("CAPABILITY_DEPRECATED");
    }

    @Test
    void successfulCommandsReplayWithoutNewEffectsOrExtendedTime() {
        var f = h.fixture();
        String roleCmd = id(), grantCmd = id(), policyCmd = id(), requestCmd = id();
        var role =
                h.runtime
                        .access()
                        .createRole(
                                f.login(),
                                f.partition(),
                                roleCmd,
                                "reader",
                                3,
                                List.of(f.capabilities().getFirst()));
        var from = Instant.now().minusSeconds(1);
        var to = from.plusSeconds(120);
        var g =
                h.runtime
                        .access()
                        .grant(
                                f.login(),
                                f.partition(),
                                grantCmd,
                                f.member().membershipId(),
                                1,
                                role.id(),
                                "TENANT_ALL",
                                "old-source",
                                from,
                                to);
        var policy = policy(f, policyCmd);
        var r = submit(f, policy, requestCmd, from, to);
        deprecate(f);
        assertThat(
                        h.runtime
                                .access()
                                .createRole(
                                        f.login(),
                                        f.partition(),
                                        roleCmd,
                                        "reader",
                                        3,
                                        List.of(f.capabilities().getFirst())))
                .isEqualTo(role);
        assertThat(
                        h.runtime
                                .access()
                                .grant(
                                        f.login(),
                                        f.partition(),
                                        grantCmd,
                                        f.member().membershipId(),
                                        1,
                                        role.id(),
                                        "TENANT_ALL",
                                        "old-source",
                                        from,
                                        to))
                .isEqualTo(g);
        assertThat(policy(f, policyCmd)).isEqualTo(policy);
        assertThat(submit(f, policy, requestCmd, from, to)).isEqualTo(r);
        h.runtime
                .access()
                .bootstrap(
                        f.partition(),
                        new Delegation(
                                f.owner().membershipId(),
                                1,
                                AccessValues.json(f.capabilities()),
                                3600),
                        "mg15",
                        id());
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",
                                Integer.class,
                                f.partition().tenantId()))
                .isEqualTo(1);
    }

    @Test
    void legacySqlCannotCreateReferencesOrExpandButProjectionAndReductionRemainAllowed() {
        var f = h.fixture();
        var g = h.grant(f, true);
        var policy = policy(f, id());
        var r = submit(f, policy, id(), Instant.now(), Instant.now().plusSeconds(60));
        deprecate(f);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.role_version(id,tenant_id,application_id,environment,role_code,version,capabilities_json,content_hash,created_by) SELECT ?,tenant_id,application_id,environment,'legacy',1,capabilities_json,content_hash,created_by FROM auth_governance.role_version WHERE id=?",
                                        id(),
                                        f.oldRole().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.access_grant(id,tenant_id,application_id,environment,membership_id,generation,role_id,scope,source_type,source_id,valid_from,valid_to,state,version,created_by) SELECT ?,tenant_id,application_id,environment,membership_id,generation,role_id,scope,'DIRECT',?,valid_from,valid_to,'PENDING',1,created_by FROM auth_governance.access_grant WHERE id=?",
                                        id(),
                                        id(),
                                        g.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.access_delegation(tenant_id,application_id,environment,membership_id,generation,capabilities_json,max_duration_seconds,created_by) SELECT tenant_id,application_id,environment,?,generation,capabilities_json,max_duration_seconds,created_by FROM auth_governance.access_delegation WHERE membership_id=?",
                                        f.member().membershipId(),
                                        f.owner().membershipId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.request_policy(id,tenant_id,application_id,environment,role_id,scope_json,max_duration_seconds,approver_membership_id,approver_generation,delegation_hash,policy_version,content_hash,enabled,created_by) SELECT ?,tenant_id,application_id,environment,role_id,scope_json,max_duration_seconds,approver_membership_id,approver_generation,delegation_hash,policy_version+1,content_hash,enabled,created_by FROM auth_governance.request_policy WHERE id=?",
                                        id(),
                                        policy.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.access_request SELECT ?,tenant_id,application_id,environment,requester_principal,membership_id,generation,policy_id,role_id,capabilities_json,scope_json,policy_hash,member_valid_to,valid_from,valid_to,reason,request_version,snapshot_hash,state,state_version,approval_instance_id,grant_id,?,created_at,updated_at FROM auth_governance.access_request WHERE id=?",
                                        id(),
                                        id(),
                                        r.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_grant SET valid_to=valid_to+interval '1 second' WHERE id=?",
                                        g.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        h.jdbc.update(
                                "UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?",
                                g.id()))
                .isEqualTo(1);
        h.runtime.access().revoke(f.login(), f.partition(), id(), g.id(), 1);
        assertThat(h.current(f, g.id()).state()).isEqualTo(GrantState.REVOKED);
        h.runtime.requests().cancel(member(f), f.partition(), id(), r.id(), 1);
        assertThat(h.runtime.requests().owned(member(f), f.partition(), r.id()).state().code())
                .isEqualTo("CANCELLED");
        h.jdbc.update(
                "UPDATE auth_governance.request_policy SET enabled=false WHERE id=?", policy.id());
        h.jdbc.update(
                "UPDATE auth_governance.access_delegation SET max_duration_seconds=300,capabilities_json=? WHERE membership_id=?",
                AccessValues.json(List.of(f.capabilities().getFirst())),
                f.owner().membershipId());
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_delegation SET max_duration_seconds=301 WHERE membership_id=?",
                                        f.owner().membershipId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_delegation SET enabled=false,max_duration_seconds=301 WHERE membership_id=?",
                                        f.owner().membershipId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        h.jdbc.update(
                "UPDATE auth_governance.access_delegation SET enabled=false WHERE membership_id=?",
                f.owner().membershipId());
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.access_delegation SET enabled=true WHERE membership_id=?",
                                        f.owner().membershipId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void pendingApprovalRevalidatesAndCannotCreateGrantAfterDeprecation() throws Exception {
        var f = h.fixture();
        var p = policy(f, id());
        var r = submit(f, p, id(), Instant.now(), Instant.now().plusSeconds(120));
        ApprovalGateway gateway =
                new ApprovalGateway() {
                    public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(
                            com.lrj.authz.protocol.ApprovalDtos.Lookup q) {
                        return Optional.empty();
                    }

                    public com.lrj.authz.protocol.ApprovalDtos.Instance start(
                            com.lrj.authz.protocol.ApprovalDtos.Start c) {
                        return new com.lrj.authz.protocol.ApprovalDtos.Instance(
                                c.requestId(), 1, c.snapshotHash(), "mg15-instance");
                    }
                };
        assertThat(h.runtime.approvalStarts(gateway).step(f.partition())).isTrue();
        deprecate(f);
        var event =
                new com.lrj.authz.protocol.ApprovalDtos.Decision(
                        id(),
                        "ACCESS_REQUEST_DECIDED",
                        1,
                        "oa-platform",
                        f.partition().tenantId(),
                        f.partition().applicationId(),
                        "test",
                        r.id(),
                        1,
                        r.snapshotHash(),
                        "mg15-instance",
                        p.id(),
                        1,
                        1,
                        "APPROVED",
                        f.owner().membershipId(),
                        1,
                        Instant.now().toString(),
                        id());
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setPropertyNamingStrategy(
                                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        byte[] body = json.writeValueAsBytes(event);
        String key = "k".repeat(43);
        h.runtime
                .approvalInbox()
                .receive(
                        f.partition(),
                        key,
                        com.lrj.authz.protocol.ApprovalSignature.sign(
                                key,
                                "oa-platform",
                                "test",
                                ApprovalInbox.PATH,
                                body,
                                Instant.now()),
                        body);
        assertThat(h.runtime.approvalDecisions().step(f.partition())).isEqualTo(1);
        assertThat(h.runtime.requests().owned(member(f), f.partition(), r.id()).state().code())
                .isEqualTo("REJECTED");
        assertThat(h.runtime.requests().owned(member(f), f.partition(), r.id()).grantId()).isNull();
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT result FROM auth_governance.approval_inbox WHERE event_id=?",
                                String.class,
                                event.eventId()))
                .isEqualTo("APPROVAL_REVALIDATION_FAILED");
    }

    @Test
    void twoOwnersCompeteOnExpectedVersionAndOneImmutableReceiptWins() throws Exception {
        var f = h.fixture();
        try (var second = GovernanceRuntime.open(h.database, false);
                var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var calls =
                    List.of(h.runtime, second).stream()
                            .map(
                                    r ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            return r.catalog()
                                                                    .changeLifecycle(
                                                                            f.login(),
                                                                            change(
                                                                                    f,
                                                                                    State
                                                                                            .DEPRECATED,
                                                                                    0))
                                                                    .current()
                                                                    .state()
                                                                    .code();
                                                        } catch (GovernanceException e) {
                                                            return e.getMessage();
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            assertThat(
                            List.of(
                                    calls.get(0).get(10, TimeUnit.SECONDS),
                                    calls.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("DEPRECATED", "VERSION_CONFLICT");
        }
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.capability_lifecycle_command WHERE application_id=?",
                                Integer.class,
                                f.partition().applicationId()))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.capability_lifecycle_command SET reason='改写历史' WHERE application_id=?",
                                        f.partition().applicationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void auditFailureRollsBackMetadataAndCommand() {
        var f = h.fixture();
        String constraint = "mg15_" + id().replace("-", "");
        h.jdbc.execute(
                "ALTER TABLE auth_governance.capability_lifecycle_command ADD CONSTRAINT "
                        + constraint
                        + " CHECK(application_id<>'"
                        + f.partition().applicationId()
                        + "')");
        try {
            assertThatThrownBy(() -> deprecate(f)).isInstanceOf(RuntimeException.class);
            assertThat(
                            h.jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_governance.capability_lifecycle WHERE application_id=?",
                                    Integer.class,
                                    f.partition().applicationId()))
                    .isZero();
        } finally {
            h.jdbc.execute(
                    "ALTER TABLE auth_governance.capability_lifecycle_command DROP CONSTRAINT "
                            + constraint);
        }
    }

    @Test
    void sharedUsageLockCommitsBeforeOwnerDeprecation() throws Exception {
        var f = h.fixture();
        var ds =
                new DriverManagerDataSource(
                        h.database.jdbcUrl(), h.database.username(), h.database.password());
        try (var c = ds.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            try (var s =
                    c.prepareStatement(
                            "SELECT application_id FROM auth_governance.application_catalog WHERE application_id=? FOR SHARE")) {
                s.setString(1, f.partition().applicationId());
                s.executeQuery();
            }
            var begun = new CountDownLatch(1);
            var owner =
                    pool.submit(
                            () -> {
                                begun.countDown();
                                return deprecate(f);
                            });
            assertThat(begun.await(1, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(f);
            assertThat(owner.isDone()).isFalse();
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO auth_governance.role_version(id,tenant_id,application_id,environment,role_code,version,capabilities_json,content_hash,created_by) SELECT ?,tenant_id,application_id,environment,'before-deprecation',1,capabilities_json,content_hash,created_by FROM auth_governance.role_version WHERE id=?")) {
                s.setString(1, id());
                s.setString(2, f.oldRole().id());
                assertThat(s.executeUpdate()).isEqualTo(1);
            }
            c.commit();
            assertThat(owner.get(5, TimeUnit.SECONDS).current().state())
                    .isEqualTo(State.DEPRECATED);
            assertThatThrownBy(
                            () ->
                                    h.runtime
                                            .access()
                                            .createRole(
                                                    f.login(),
                                                    f.partition(),
                                                    id(),
                                                    "after-deprecation",
                                                    1,
                                                    f.capabilities()))
                    .hasMessage("CAPABILITY_DEPRECATED");
        }
    }

    @Test
    void legacyWriterWaitsForOwnerCommitAndThenRejectsFreshDeprecatedState() throws Exception {
        var f = h.fixture();
        var ds =
                new DriverManagerDataSource(
                        h.database.jdbcUrl(), h.database.username(), h.database.password());
        try (var c = ds.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            try (var s =
                    c.prepareStatement(
                            "SELECT application_id FROM auth_governance.application_catalog WHERE application_id=? FOR UPDATE")) {
                s.setString(1, f.partition().applicationId());
                s.executeQuery();
            }
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO auth_governance.capability_lifecycle(application_id,capability,state,version,reason,changed_by) VALUES(?,?,'DEPRECATED',1,'并发Owner弃用',?)")) {
                s.setString(1, f.partition().applicationId());
                s.setString(2, f.capabilities().getFirst());
                s.setString(3, f.owner().principalId());
                s.executeUpdate();
            }
            var begun = new CountDownLatch(1);
            var writer =
                    pool.submit(
                            () -> {
                                begun.countDown();
                                try {
                                    h.jdbc.update(
                                            "INSERT INTO auth_governance.role_version(id,tenant_id,application_id,environment,role_code,version,capabilities_json,content_hash,created_by) SELECT ?,tenant_id,application_id,environment,'legacy-race',1,capabilities_json,content_hash,created_by FROM auth_governance.role_version WHERE id=?",
                                            id(),
                                            f.oldRole().id());
                                    return "unexpected";
                                } catch (org.springframework.dao.DataAccessException e) {
                                    return "rejected";
                                }
                            });
            assertThat(begun.await(1, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(f);
            assertThat(writer.isDone()).isFalse();
            c.commit();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo("rejected");
        }
    }

    /** 等待真实PG锁等待者出现，避免仅凭线程启动或固定睡眠声称并发顺序通过。 */
    private void awaitBlocked(F f) throws Exception {
        long until = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < until) {
            if (h.jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
                            Integer.class)
                    > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("未观测到真实PG锁等待");
    }
}
