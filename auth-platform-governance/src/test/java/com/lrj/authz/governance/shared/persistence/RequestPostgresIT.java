package com.lrj.authz.governance.shared.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.approval.application.ApprovalInbox;
import com.lrj.authz.governance.approval.port.ApprovalGateway;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 申请快照、SQL完整性、幂等并发和审计回滚的真实PostgreSQL验证。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;

    @BeforeAll
    void open() {
        Properties p = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) p = GovernanceConfigurationFile.read(file);
        else {
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

    private BootstrapCommand person(String tenant, String code) {
        var c =
                new BootstrapCommand(
                        id(),
                        "access-test",
                        tenant,
                        code,
                        id(),
                        "https://access.example",
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

    private VerifiedLogin login(BootstrapCommand c) {
        return new VerifiedLogin(c.issuer(), c.subject());
    }

    private Fixture fixture() {
        String tenant = id(), code = "tenant-" + id(), app = "app-" + id();
        var owner = person(tenant, code);
        var member = person(tenant, code);
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        runtime.catalog()
                .publish(
                        login(owner),
                        new Manifest(
                                "1",
                                app,
                                1,
                                List.of(
                                        new Capability(app + ".read", "store", Risk.NORMAL),
                                        new Capability(app + ".refund", "store", Risk.HIGH)),
                                List.of()),
                        id());
        Partition p = new Partition(tenant, app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read", app + ".refund")),
                                3600),
                        "test",
                        id());
        var role =
                runtime.access()
                        .createRole(login(owner), p, id(), "reader", 1, List.of(app + ".read"));
        runtime.access().enableStrict(login(owner), p, id());
        return new Fixture(owner, member, p, role);
    }

    private com.lrj.authz.protocol.ScopeDtos.Rule scope() {
        return new com.lrj.authz.protocol.ScopeDtos.Rule(
                1,
                "store",
                List.of(
                        new com.lrj.authz.protocol.ScopeDtos.Clause(
                                com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,
                                List.of("S001"),
                                false)));
    }

    private com.lrj.authz.governance.approval.domain.RequestModels.Policy policy(Fixture f) {
        return runtime.requests()
                .registerPolicy(
                        login(f.owner),
                        f.p,
                        id(),
                        f.role.id(),
                        scope(),
                        600,
                        f.owner.membershipId(),
                        1,
                        1);
    }

    private com.lrj.authz.governance.approval.domain.RequestModels.Request submit(
            Fixture f, String policy, String command, Instant from, Instant to, String reason) {
        return runtime.requests().submit(login(f.member), f.p, command, policy, from, to, reason);
    }

    @Test
    void roleChangesCannotChangeSubmittedSnapshotOrWindow() {
        var f = fixture();
        var policy = policy(f);
        Instant from = Instant.now(), to = from.plusSeconds(300);
        String command = id();
        var request = submit(f, policy.id(), command, from, to, "门店试点");
        runtime.access()
                .createRole(
                        login(f.owner),
                        f.p,
                        id(),
                        "reader",
                        2,
                        List.of(f.p.applicationId() + ".read", f.p.applicationId() + ".refund"));
        var replay = submit(f, policy.id(), command, from, to, "门店试点");
        assertThat(replay).isEqualTo(request);
        assertThat(replay.capabilitiesJson()).doesNotContain("refund");
        assertThat(replay.validTo())
                .isEqualTo(to.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(replay.grantId()).isNull();
        assertThat(runtime.access().state(login(f.owner), f.p, null, null).grants()).isEmpty();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.access_request set valid_to=valid_to+interval '1 second' where id=?",
                                        request.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update auth_governance.request_policy set max_duration_seconds=1000 where id=?",
                                        policy.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void parallelRetriesCreateOneSnapshotOneStartIntentAndOneAudit() throws Exception {
        var f = fixture();
        var policy = policy(f);
        String command = id();
        Instant start = Instant.now(), end = start.plusSeconds(60);
        try (var pool = Executors.newFixedThreadPool(3)) {
            Callable<String> call = () -> submit(f, policy.id(), command, start, end, "same").id();
            var results = pool.invokeAll(List.of(call, call, call));
            String request = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(request);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.request_start_outbox where request_id=?",
                                    Integer.class,
                                    request))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.audit_event where target_id=? and operation='SUBMIT_ACCESS_REQUEST'",
                                    Integer.class,
                                    request))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    submit(
                                            f,
                                            policy.id(),
                                            command,
                                            start,
                                            end.plusSeconds(1),
                                            "same"))
                    .hasMessage("COMMAND_CONFLICT");
        }
    }

    @Test
    void selfApprovalUnknownPolicyAndCrossTenantReadAreDenied() {
        var f = fixture();
        var other = fixture();
        var policy = policy(f);
        Instant start = Instant.now(), end = start.plusSeconds(60);
        assertThatThrownBy(
                        () ->
                                runtime.requests()
                                        .submit(
                                                login(f.owner),
                                                f.p,
                                                id(),
                                                policy.id(),
                                                start,
                                                end,
                                                "self"))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(() -> submit(f, policy(other).id(), id(), start, end, "other"))
                .hasMessage("ACCESS_DENIED");
        var request = submit(f, policy.id(), id(), start, end, "own");
        assertThatThrownBy(() -> runtime.requests().owned(login(f.owner), f.p, request.id()))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () -> runtime.requests().owned(login(other.member), other.p, request.id()))
                .hasMessage("ACCESS_DENIED");
        assertThat(runtime.requests().policies(login(f.owner), f.p, null)).isEmpty();
        assertThat(runtime.requests().policies(login(f.member), f.p, null)).hasSize(1);
        assertThatThrownBy(
                        () ->
                                runtime.requests()
                                        .registerPolicy(
                                                login(f.member),
                                                f.p,
                                                id(),
                                                f.role.id(),
                                                scope(),
                                                60,
                                                f.owner.membershipId(),
                                                1,
                                                1))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void currentCeilingDisabledPolicyAndMembershipExpiryBoundSubmission() {
        var f = fixture();
        var policy = policy(f);
        Instant start = Instant.now(), end = start.plusSeconds(601);
        assertThatThrownBy(() -> submit(f, policy.id(), id(), start, end, "too long"))
                .hasMessage("ACCESS_DENIED");
        jdbc.update(
                "update auth_governance.membership set valid_to=clock_timestamp()+interval '30 seconds',version=version+1 where id=?",
                f.member.membershipId());
        assertThatThrownBy(
                        () ->
                                submit(
                                        f,
                                        policy.id(),
                                        id(),
                                        start,
                                        start.plusSeconds(60),
                                        "past member end"))
                .hasMessage("ACCESS_DENIED");
        jdbc.update(
                "update auth_governance.request_policy set enabled=false where id=?", policy.id());
        assertThatThrownBy(
                        () ->
                                submit(
                                        f,
                                        policy.id(),
                                        id(),
                                        start,
                                        start.plusSeconds(10),
                                        "disabled"))
                .hasMessage("ACCESS_DENIED");
        var g = fixture();
        var gp = policy(g);
        jdbc.update(
                "update auth_governance.access_delegation set enabled=false where membership_id=?",
                g.owner.membershipId());
        assertThatThrownBy(
                        () ->
                                submit(
                                        g,
                                        gp.id(),
                                        id(),
                                        start,
                                        start.plusSeconds(60),
                                        "ceiling removed"))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void auditFailureRollsBackSnapshotCommandAndOutbox() {
        var f = fixture();
        var policy = policy(f);
        String constraint = "test_req_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.audit_event add constraint "
                        + constraint
                        + " check (tenant_id <> '"
                        + f.p.tenantId()
                        + "' or operation <> 'SUBMIT_ACCESS_REQUEST')");
        try {
            assertThatThrownBy(
                            () ->
                                    submit(
                                            f,
                                            policy.id(),
                                            id(),
                                            Instant.now(),
                                            Instant.now().plusSeconds(60),
                                            "rollback"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.access_request where tenant_id=?",
                                    Integer.class,
                                    f.p.tenantId()))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.command_record where tenant_id=? and operation='SUBMIT_ACCESS_REQUEST'",
                                    Integer.class,
                                    f.p.tenantId()))
                    .isZero();
        } finally {
            jdbc.execute("alter table auth_governance.audit_event drop constraint " + constraint);
        }
    }

    @Test
    void databaseRejectsForeignPolicyAndMemberReferences() {
        var f = fixture();
        var other = fixture();
        var policy = policy(f);
        var foreign = policy(other);
        var request =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "constraint");
        // 先验证不可变边界；跨租户插入由复合外键最终兜底。
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into auth_governance.access_request select ?,tenant_id,application_id,environment,requester_principal,membership_id,generation,?,role_id,capabilities_json,scope_json,policy_hash,member_valid_to,valid_from,valid_to,reason,request_version,snapshot_hash,state,state_version,approval_instance_id,grant_id,?,created_at,updated_at from auth_governance.access_request where id=?",
                                        id(),
                                        foreign.id(),
                                        id(),
                                        request.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void startTimeoutQueriesBeforeRetryAndBindsOriginalInstance() {
        var f = fixture();
        var policy = policy(f);
        var request =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "timeout");
        var created =
                new java.util.concurrent.atomic.AtomicReference<
                        com.lrj.authz.protocol.ApprovalDtos.Instance>();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        ApprovalGateway gateway =
                new ApprovalGateway() {
                    public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(
                            com.lrj.authz.protocol.ApprovalDtos.Lookup q) {
                        return Optional.ofNullable(created.get());
                    }

                    public com.lrj.authz.protocol.ApprovalDtos.Instance start(
                            com.lrj.authz.protocol.ApprovalDtos.Start command) {
                        starts.incrementAndGet();
                        created.set(
                                new com.lrj.authz.protocol.ApprovalDtos.Instance(
                                        command.requestId(),
                                        command.requestVersion(),
                                        command.snapshotHash(),
                                        "100"));
                        throw new GovernanceException(
                                GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
                    }
                };
        assertThat(runtime.approvalStarts(gateway).step(f.p)).isTrue();
        assertThat(
                        runtime.requests()
                                .owned(login(f.member), f.p, request.id())
                                .approvalInstanceId())
                .isNull();
        jdbc.update(
                "update auth_governance.request_start_outbox set next_attempt_at=clock_timestamp() where request_id=?",
                request.id());
        assertThat(runtime.approvalStarts(gateway).step(f.p)).isTrue();
        assertThat(starts.get()).isEqualTo(1);
        var bound = runtime.requests().owned(login(f.member), f.p, request.id());
        assertThat(bound.approvalInstanceId()).isEqualTo("100");
        assertThat(bound.state().code()).isEqualTo("IN_REVIEW");
        assertThat(bound.grantId()).isNull();
    }

    @Test
    void failedLookupNeverCreatesAndEventuallyExhausts() {
        var f = fixture();
        var policy = policy(f);
        var request =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "offline");
        ApprovalGateway gateway =
                new ApprovalGateway() {
                    public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(
                            com.lrj.authz.protocol.ApprovalDtos.Lookup q) {
                        throw new GovernanceException(
                                GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
                    }

                    public com.lrj.authz.protocol.ApprovalDtos.Instance start(
                            com.lrj.authz.protocol.ApprovalDtos.Start command) {
                        throw new AssertionError("must not start on unknown lookup");
                    }
                };
        for (int i = 0; i < 5; i++) {
            jdbc.update(
                    "update auth_governance.request_start_outbox set next_attempt_at=clock_timestamp() where request_id=?",
                    request.id());
            assertThat(runtime.approvalStarts(gateway).step(f.p)).isTrue();
        }
        assertThat(runtime.approvalStarts(gateway).step(f.p)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select state from auth_governance.request_start_outbox where request_id=?",
                                String.class,
                                request.id()))
                .isEqualTo("DEAD");
    }

    @Test
    void signedInboxDeduplicatesBusinessEventsAndKeepsConflictEvidence() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var request =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "callback");
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setPropertyNamingStrategy(
                                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        String key = "k".repeat(43), event = id();
        var decision =
                new com.lrj.authz.protocol.ApprovalDtos.Decision(
                        event,
                        "ACCESS_REQUEST_DECIDED",
                        1,
                        "oa-platform",
                        f.p.tenantId(),
                        f.p.applicationId(),
                        f.p.environment(),
                        request.id(),
                        1,
                        request.snapshotHash(),
                        "123",
                        policy.id(),
                        1,
                        1,
                        "APPROVED",
                        f.owner.membershipId(),
                        1,
                        Instant.now().toString(),
                        id());
        byte[] body = json.writeValueAsBytes(decision);
        String signature =
                com.lrj.authz.protocol.ApprovalSignature.sign(
                        key, "oa-platform", "test", ApprovalInbox.PATH, body, Instant.now());
        assertThat(runtime.approvalInbox().receive(f.p, key, signature, body).status())
                .isEqualTo("RECEIVED");
        assertThatThrownBy(() -> runtime.approvalInbox().receive(f.p, key, signature, body))
                .hasMessage("COMMAND_CONFLICT");
        String retry =
                com.lrj.authz.protocol.ApprovalSignature.sign(
                        key, "oa-platform", "test", ApprovalInbox.PATH, body, Instant.now());
        assertThat(runtime.approvalInbox().receive(f.p, key, retry, body).status())
                .isEqualTo("RECEIVED");
        byte[] changed =
                new String(body, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("APPROVED", "REJECTED")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String altered =
                com.lrj.authz.protocol.ApprovalSignature.sign(
                        key, "oa-platform", "test", ApprovalInbox.PATH, changed, Instant.now());
        assertThat(runtime.approvalInbox().receive(f.p, key, altered, changed).status())
                .isEqualTo("CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.approval_conflict where event_id=?",
                                Integer.class,
                                event))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select payload_json from auth_governance.approval_inbox where event_id=?",
                                String.class,
                                event))
                .contains("APPROVED");
        assertThatThrownBy(() -> runtime.approvalInbox().receive(f.p, key, signature, changed))
                .hasMessage("INVALID_CREDENTIAL");
        assertThat(runtime.requests().owned(login(f.member), f.p, request.id()).grantId()).isNull();
    }

    @Test
    void oldRequestVersionAndWrongApproverArePersistentlyRejected() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "old event");
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setPropertyNamingStrategy(
                                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        for (int n = 0; n < 2; n++) {
            var event =
                    new com.lrj.authz.protocol.ApprovalDtos.Decision(
                            id(),
                            "ACCESS_REQUEST_DECIDED",
                            1,
                            "oa-platform",
                            f.p.tenantId(),
                            f.p.applicationId(),
                            f.p.environment(),
                            r.id(),
                            n == 0 ? 2 : 1,
                            r.snapshotHash(),
                            "123",
                            policy.id(),
                            1,
                            1,
                            "APPROVED",
                            n == 0 ? f.owner.membershipId() : f.member.membershipId(),
                            1,
                            Instant.now().toString(),
                            id());
            byte[] body = json.writeValueAsBytes(event);
            String key = "k".repeat(43);
            String signature =
                    com.lrj.authz.protocol.ApprovalSignature.sign(
                            key, "oa-platform", "test", ApprovalInbox.PATH, body, Instant.now());
            assertThat(runtime.approvalInbox().receive(f.p, key, signature, body).status())
                    .isEqualTo("REJECTED");
            assertThat(
                            jdbc.queryForObject(
                                    "select status from auth_governance.approval_inbox where event_id=?",
                                    String.class,
                                    event.eventId()))
                    .isEqualTo("REJECTED");
        }
    }

    private String approve(
            Fixture f,
            com.lrj.authz.governance.approval.domain.RequestModels.Policy policy,
            com.lrj.authz.governance.approval.domain.RequestModels.Request r)
            throws Exception {
        ApprovalGateway gateway =
                new ApprovalGateway() {
                    public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(
                            com.lrj.authz.protocol.ApprovalDtos.Lookup q) {
                        return Optional.empty();
                    }

                    public com.lrj.authz.protocol.ApprovalDtos.Instance start(
                            com.lrj.authz.protocol.ApprovalDtos.Start command) {
                        return new com.lrj.authz.protocol.ApprovalDtos.Instance(
                                command.requestId(), 1, command.snapshotHash(), "123");
                    }
                };
        runtime.approvalStarts(gateway).step(f.p);
        var event =
                new com.lrj.authz.protocol.ApprovalDtos.Decision(
                        id(),
                        "ACCESS_REQUEST_DECIDED",
                        1,
                        "oa-platform",
                        f.p.tenantId(),
                        f.p.applicationId(),
                        f.p.environment(),
                        r.id(),
                        1,
                        r.snapshotHash(),
                        "123",
                        policy.id(),
                        1,
                        1,
                        "APPROVED",
                        f.owner.membershipId(),
                        1,
                        Instant.now().toString(),
                        id());
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setPropertyNamingStrategy(
                                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        byte[] body = json.writeValueAsBytes(event);
        String key = "k".repeat(43);
        String signature =
                com.lrj.authz.protocol.ApprovalSignature.sign(
                        key, "oa-platform", "test", ApprovalInbox.PATH, body, Instant.now());
        runtime.approvalInbox().receive(f.p, key, signature, body);
        return event.eventId();
    }

    @Test
    void approvedRequestCreatesOneFixedGrantAndCannotClaimActiveWithoutGraph() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r = submit(f, policy.id(), id(), Instant.now(), Instant.now().plusSeconds(60), "apply");
        approve(f, policy, r);
        assertThat(runtime.approvalDecisions().step(f.p)).isEqualTo(1);
        var approved = runtime.requests().owned(login(f.member), f.p, r.id());
        assertThat(approved.state().code()).isEqualTo("APPROVED");
        var grants = runtime.access().state(login(f.owner), f.p, null, null).grants();
        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().sourceType()).isEqualTo("OA_REQUEST");
        assertThat(grants.getFirst().roleId()).isEqualTo(r.roleId());
        assertThat(runtime.requests().execution(login(f.member), f.p, r.id()).displayState())
                .isEqualTo("PENDING_APPLY");
        approve(f, policy, r);
        runtime.approvalDecisions().step(f.p);
        assertThat(runtime.access().state(login(f.owner), f.p, null, null).grants()).hasSize(1);
        jdbc.update(
                "update auth_governance.policy_partition set state='BLOCKED' where tenant_id=?",
                f.p.tenantId());
        assertThat(runtime.requests().execution(login(f.member), f.p, r.id()).displayState())
                .isEqualTo("APPLY_FAILED");
    }

    @Test
    void failedApprovalAuditRollsBackInboxRequestGrantScopeAndProjection() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "atomic apply");
        String event = approve(f, policy, r), constraint = "p4_atomic_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.audit_event add constraint "
                        + constraint
                        + " check(tenant_id <> '"
                        + f.p.tenantId()
                        + "' or operation <> 'APPROVE_ACCESS_REQUEST')");
        try {
            assertThatThrownBy(() -> runtime.approvalDecisions().step(f.p))
                    .isInstanceOf(RuntimeException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "select status from auth_governance.approval_inbox where event_id=?",
                                    String.class,
                                    event))
                    .isEqualTo("RECEIVED");
            assertThat(runtime.requests().owned(login(f.member), f.p, r.id()).state().code())
                    .isEqualTo("IN_REVIEW");
            assertThat(runtime.access().state(login(f.owner), f.p, null, null).grants()).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.grant_scope where tenant_id=?",
                                    Integer.class,
                                    f.p.tenantId()))
                    .isZero();
        } finally {
            jdbc.execute("alter table auth_governance.audit_event drop constraint " + constraint);
        }
        assertThat(runtime.approvalDecisions().step(f.p)).isEqualTo(1);
        assertThat(runtime.requests().owned(login(f.member), f.p, r.id()).grantId()).isNotNull();
    }

    @Test
    void approvedEventCannotOutliveCurrentManagementCeiling() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "revalidate");
        String event = approve(f, policy, r);
        jdbc.update(
                "update auth_governance.access_delegation set enabled=false where membership_id=?",
                f.owner.membershipId());
        assertThat(runtime.approvalDecisions().step(f.p)).isEqualTo(1);
        assertThat(runtime.requests().owned(login(f.member), f.p, r.id()).state().code())
                .isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "select result from auth_governance.approval_inbox where event_id=?",
                                String.class,
                                event))
                .isEqualTo("APPROVAL_REVALIDATION_FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.access_grant where tenant_id=?",
                                Integer.class,
                                f.p.tenantId()))
                .isZero();
    }

    @Test
    void cancellationWinsOrRevokesApprovalWithoutTouchingIndependentGrant() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            var f = fixture();
            var policy = policy(f);
            var r =
                    submit(
                            f,
                            policy.id(),
                            id(),
                            Instant.now(),
                            Instant.now().plusSeconds(60),
                            "race");
            var independent =
                    runtime.access()
                            .grant(
                                    login(f.owner),
                                    f.p,
                                    id(),
                                    f.member.membershipId(),
                                    1,
                                    f.role.id(),
                                    "TENANT_ALL",
                                    id(),
                                    Instant.now(),
                                    Instant.now().plusSeconds(60));
            approve(f, policy, r);
            var barrier = new CyclicBarrier(2);
            String command = id();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var cancel =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return runtime.requests()
                                            .cancel(login(f.member), f.p, command, r.id(), 2);
                                });
                var decide =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return runtime.approvalDecisions().step(f.p);
                                });
                cancel.get();
                decide.get();
            }
            var done = runtime.requests().owned(login(f.member), f.p, r.id());
            assertThat(runtime.requests().cancel(login(f.member), f.p, command, r.id(), 2))
                    .isEqualTo(done);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.access_grant where tenant_id=? and source_type='OA_REQUEST' and state<>'REVOKED'",
                                    Integer.class,
                                    f.p.tenantId()))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select state from auth_governance.access_grant where id=?",
                                    String.class,
                                    independent.id()))
                    .isEqualTo("PENDING");
            approve(f, policy, r);
            runtime.approvalDecisions().step(f.p);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.access_grant where tenant_id=? and source_type='OA_REQUEST' and state<>'REVOKED'",
                                    Integer.class,
                                    f.p.tenantId()))
                    .isZero();
        }
    }

    @Test
    void cancelledUnstartedRequestDoesNotCallOaAndOtherMembersCannotCancel() {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "withdraw");
        assertThatThrownBy(() -> runtime.requests().cancel(login(f.owner), f.p, id(), r.id(), 1))
                .hasMessage("ACCESS_DENIED");
        assertThat(runtime.requests().cancel(login(f.member), f.p, id(), r.id(), 1).state().code())
                .isEqualTo("CANCELLED");
        ApprovalGateway never =
                new ApprovalGateway() {
                    public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(
                            com.lrj.authz.protocol.ApprovalDtos.Lookup q) {
                        throw new AssertionError("cancelled");
                    }

                    public com.lrj.authz.protocol.ApprovalDtos.Instance start(
                            com.lrj.authz.protocol.ApprovalDtos.Start q) {
                        throw new AssertionError("cancelled");
                    }
                };
        assertThat(runtime.approvalStarts(never).step(f.p)).isFalse();
    }

    @Test
    void expiredWindowAndDepartedGenerationAreSettledOnce() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var from = Instant.now();
        var r = submit(f, policy.id(), id(), from, from.plusSeconds(3), "expired");
        approve(f, policy, r);
        runtime.approvalDecisions().step(f.p);
        Thread.sleep(
                Math.max(
                        1, java.time.Duration.between(Instant.now(), r.validTo()).toMillis() + 50));
        assertThat(runtime.requests().execution(login(f.member), f.p, r.id()).displayState())
                .isEqualTo("EXPIRED");
        assertThat(runtime.requests().settle(f.p)).isEqualTo(1);
        assertThat(runtime.requests().settle(f.p)).isZero();
        var g = fixture();
        var gp = policy(g);
        var gr = submit(g, gp.id(), id(), Instant.now(), Instant.now().plusSeconds(60), "left");
        approve(g, gp, gr);
        runtime.approvalDecisions().step(g.p);
        jdbc.update(
                "update auth_governance.membership set status='LEFT',version=version+1 where id=?",
                g.member.membershipId());
        assertThat(runtime.requests().settle(g.p)).isEqualTo(1);
        jdbc.update(
                "update auth_governance.membership set status='ACTIVE',generation=generation+1,version=version+1 where id=?",
                g.member.membershipId());
        assertThatThrownBy(() -> runtime.requests().owned(login(g.member), g.p, gr.id()))
                .hasMessage("ACCESS_DENIED");
        assertThat(
                        jdbc.queryForObject(
                                "select state from auth_governance.access_grant where tenant_id=?",
                                String.class,
                                g.p.tenantId()))
                .isEqualTo("REVOKED");
    }

    @Test
    void noticeFailureRetriesIndependentlyAndOnlyCurrentOwnerCanRead() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "notify");
        approve(f, policy, r);
        runtime.approvalDecisions().step(f.p);
        String constraint = "p4_notice_" + id().replace("-", "");
        jdbc.execute(
                "alter table auth_governance.request_site_notice add constraint "
                        + constraint
                        + " check(request_id <> '"
                        + r.id()
                        + "')");
        try {
            assertThat(runtime.requestNotifications().step(f.p)).isTrue();
            assertThat(runtime.requests().owned(login(f.member), f.p, r.id()).state().code())
                    .isEqualTo("APPROVED");
            assertThat(runtime.requests().notices(login(f.member), f.p, null)).isEmpty();
        } finally {
            jdbc.execute(
                    "alter table auth_governance.request_site_notice drop constraint "
                            + constraint);
        }
        jdbc.update(
                "update auth_governance.request_notice_outbox set next_attempt_at=clock_timestamp() where request_id=?",
                r.id());
        for (int i = 0; i < 5; i++) runtime.requestNotifications().step(f.p);
        assertThat(runtime.requests().notices(login(f.member), f.p, null))
                .hasSize(3)
                .allSatisfy(n -> assertThat(n.messageKey()).isEqualTo("REQUEST_PROGRESS_UPDATED"));
        assertThat(runtime.requestNotifications().step(f.p)).isFalse();
        assertThat(runtime.requests().notices(login(f.owner), f.p, null)).isEmpty();
        assertThat(runtime.requests().mine(login(f.owner), f.p, null)).isEmpty();
        assertThat(runtime.requests().mine(login(f.member), f.p, null))
                .extracting(com.lrj.authz.governance.approval.domain.RequestModels.Request::id)
                .containsExactly(r.id());
        assertThat(runtime.requests().mine(login(f.member), f.p, r.id())).isEmpty();
        var execution = runtime.requests().execution(login(f.member), f.p, r.id());
        assertThat(execution.startState()).isEqualTo("DONE");
        assertThat(execution.callbackStatus()).isEqualTo("APPLIED");
    }

    @Test
    void filteredPolicyPageKeepsCursorToLaterValidPolicies() {
        var f = fixture();
        var policy = policy(f);
        // 保留真实不可变策略内容，批量夹具只替换唯一ID；当前上限被移除后整页仍给扫描游标。
        jdbc.update(
                "insert into auth_governance.request_policy select gen_random_uuid()::text,tenant_id,application_id,environment,role_id,scope_json,max_duration_seconds,approver_membership_id,approver_generation,delegation_hash,policy_version,content_hash,enabled,created_by,created_at from auth_governance.request_policy cross join generate_series(1,100) where id=?",
                policy.id());
        jdbc.update(
                "update auth_governance.access_delegation set enabled=false where membership_id=?",
                f.owner.membershipId());
        var page = runtime.requests().policyPage(login(f.member), f.p, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNotNull();
        assertThat(
                        runtime.requests()
                                .policyPage(login(f.member), f.p, page.nextCursor())
                                .nextCursor())
                .isNull();
    }

    @Test
    void emergencyCapabilityDisableRejectsApprovalAndStopsActiveDisplay() throws Exception {
        var f = fixture();
        var policy = policy(f);
        var r =
                submit(
                        f,
                        policy.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "disabled");
        approve(f, policy, r);
        runtime.catalog()
                .changeCapability(
                        login(f.owner),
                        f.p.applicationId(),
                        f.p.applicationId() + ".read",
                        true,
                        0,
                        "incident",
                        id());
        runtime.approvalDecisions().step(f.p);
        assertThat(runtime.requests().owned(login(f.member), f.p, r.id()).state().code())
                .isEqualTo("REJECTED");
        var g = fixture();
        var gp = policy(g);
        var gr =
                submit(
                        g,
                        gp.id(),
                        id(),
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        "after grant");
        approve(g, gp, gr);
        runtime.approvalDecisions().step(g.p);
        runtime.catalog()
                .changeCapability(
                        login(g.owner),
                        g.p.applicationId(),
                        g.p.applicationId() + ".read",
                        true,
                        0,
                        "incident",
                        id());
        assertThat(runtime.requests().execution(login(g.member), g.p, gr.id()).displayState())
                .isEqualTo("UNAVAILABLE");
    }

    private record Fixture(
            BootstrapCommand owner, BootstrapCommand member, Partition p, RoleVersion role) {}
}
