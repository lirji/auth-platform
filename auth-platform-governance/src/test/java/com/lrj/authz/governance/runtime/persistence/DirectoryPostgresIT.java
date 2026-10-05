package com.lrj.authz.governance.runtime.persistence;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.directory.application.DirectoryAuthority;
import com.lrj.authz.governance.directory.application.DirectoryGovernance;
import com.lrj.authz.governance.directory.application.DirectoryJson;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.application.LifecycleCommand;
import com.lrj.authz.governance.identity.domain.IdentityModels.*;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.DirectoryEvents;
import com.lrj.authz.protocol.DirectoryEvents.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实治理 PostgreSQL 验证目录授权范围、提交顺序、回滚与冲突隔离；不替代后续真实 OA 联调。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectoryPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    private Properties databaseProperties;

    @BeforeAll
    void openOwnedDatabase() {
        Properties p = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) {
            p = GovernanceConfigurationFile.read(file);
        } else {
            p.setProperty(
                    "jdbc.url", Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_URL")));
            p.setProperty(
                    "jdbc.username",
                    Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_USER")));
            p.setProperty(
                    "jdbc.password",
                    Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_PASSWORD")));
        }
        assertThat(p.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var config = GovernanceDatabase.from(p);
        runtime = GovernanceRuntime.open(config, true);
        databaseProperties = p;
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                config.jdbcUrl(), config.username(), config.password()));
    }

    @AfterAll
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void provisionsExactIdentityAndKeepsRegistrationImmutable() {
        var a = authority();
        String user = id();
        Event event = employee(a, 1, 1, "1", user, "PROBATION", null);
        assertThat(runtime.directory().accept(a, event).lastSequence()).isEqualTo(1);
        Membership member = member(a, "1");
        assertThat(member.memberKind()).isEqualTo(MemberKind.EMPLOYEE);
        assertThat(
                        runtime.identity()
                                .contextForLogin(a.issuer(), user, a.tenantId(), 1L)
                                .membershipId())
                .isEqualTo(member.id());
        assertThat(runtime.directory().accept(a, event).replay()).isTrue();
        assertThat(audits(event)).isEqualTo(1);
        assertThat(runtime.directory().register(a, "directory-test").lastSequence()).isEqualTo(1);
        var changed =
                new DirectoryAuthority(
                        a.id(),
                        a.source(),
                        a.environment(),
                        a.sourceTenantRef(),
                        a.tenantId(),
                        "https://other.example");
        assertCode(() -> runtime.directory().register(changed, "directory-test"), BINDING_CONFLICT);
        assertThat(quarantined(a)).isFalse();
    }

    @Test
    void olderArrivalCannotRestoreLeftAndCheckpointNeverSkipsGap() {
        var a = authority();
        String user = id();
        Event newer = employee(a, 2, 2, "1", user, "LEFT", null);
        assertThat(runtime.directory().accept(a, newer).lastSequence()).isZero();
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.LEFT);
        assertThat(
                        runtime.directory()
                                .accept(a, employee(a, 1, 1, "1", user, "ACTIVE", null))
                                .lastSequence())
                .isEqualTo(2);
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.LEFT);
        assertCode(
                () -> runtime.identity().contextForLogin(a.issuer(), user, a.tenantId(), 1L),
                MEMBERSHIP_UNAVAILABLE);
        runtime.directory().accept(a, employee(a, 3, 3, "1", user, "ACTIVE", null));
        Membership joined = member(a, "1");
        assertThat(joined.generation()).isEqualTo(2);
        assertThat(joined.version()).isEqualTo(2);
        assertCode(
                () -> runtime.identity().contextForLogin(a.issuer(), user, a.tenantId(), 1L),
                GENERATION_MISMATCH);
        runtime.lifecycle()
                .suspend(
                        new LifecycleCommand(
                                id(),
                                "security",
                                a.tenantId(),
                                LifecycleCommand.Operation.SUSPEND_MEMBER,
                                joined.id(),
                                2,
                                "手工暂停"));
        runtime.directory().accept(a, employee(a, 4, 4, "1", user, "LEFT", null));
        runtime.directory().accept(a, employee(a, 5, 5, "1", user, "LEAVING", null));
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(member(a, "1").generation()).isEqualTo(2);
        assertThat(member(a, "1").version()).isEqualTo(3);
    }

    @Test
    void absentLoginCanBeBoundOnceButNeverGuessedOrReplaced() {
        var a = authority();
        runtime.directory().accept(a, employee(a, 1, 1, "1", null, "ACTIVE", null));
        Membership original = member(a, "1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.login_identity WHERE principal_id=?",
                                Integer.class,
                                original.principalId()))
                .isZero();
        String user = id();
        runtime.directory().accept(a, employee(a, 2, 2, "1", user, "ACTIVE", null));
        assertThat(member(a, "1").principalId()).isEqualTo(original.principalId());
        assertThat(runtime.identity().principalForLogin(a.issuer(), user).id())
                .isEqualTo(original.principalId());
        String replacement = id();
        Event rejected = employee(a, 3, 3, "1", replacement, "ACTIVE", null);
        assertCode(() -> runtime.directory().accept(a, rejected), BINDING_CONFLICT);
        assertThat(runtime.mapper().loginIdentity(a.issuer(), replacement)).isNull();
        assertThat(quarantined(a)).isTrue();
        assertThat(conflicts(a)).isEqualTo(1);
        assertThat(inbox(a, rejected)).isZero();
    }

    @Test
    void sourceScopeMismatchCannotQuarantineOrProvision() {
        var a = authority();
        var other = authority();
        Event foreign = employee(other, 1, 1, "1", id(), "ACTIVE", null);
        assertCode(() -> runtime.directory().accept(a, foreign), INVALID_ARGUMENT);
        assertThat(quarantined(a)).isFalse();
        assertThat(conflicts(a)).isZero();
        assertThat(runtime.directory().checkpoint(a).lastSequence()).isZero();
    }

    @Test
    void sameEventOrSequenceOrOldVersionWithChangedContentIsQuarantined() {
        for (String kind : List.of("event", "sequence", "version")) {
            var a = authority();
            String user = id();
            Event first = employee(a, 1, 1, "1", user, "ACTIVE", null);
            runtime.directory().accept(a, first);
            Event candidate =
                    employee(
                            a,
                            kind.equals("sequence") ? 1 : 2,
                            kind.equals("version") ? 1 : 2,
                            "1",
                            user,
                            "LEFT",
                            null);
            if (kind.equals("event")) {
                candidate =
                        new Event(
                                candidate.schemaVersion(),
                                first.eventId(),
                                candidate.source(),
                                candidate.environment(),
                                candidate.sourceTenantRef(),
                                candidate.partitionSequence(),
                                candidate.aggregateType(),
                                candidate.aggregateId(),
                                candidate.aggregateVersion(),
                                candidate.occurredAt(),
                                null,
                                candidate.payload(),
                                candidate.payloadHash());
            }
            Event rejected = candidate;
            assertCode(() -> runtime.directory().accept(a, rejected), BINDING_CONFLICT);
            assertThat(quarantined(a)).isTrue();
            assertThat(conflicts(a)).isEqualTo(1);
            assertThat(member(a, "1").status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(audits(rejected)).isEqualTo(kind.equals("event") ? 1 : 0);
        }
    }

    @Test
    void existingPrincipalAndMemberAreReusedButSecondEmployeeCannotTakeThem() {
        var a = authority();
        String user = id();
        String principal = id();
        runtime.mapper()
                .insertPrincipal(
                        new Principal(principal, PrincipalKind.HUMAN, GlobalStatus.ACTIVE, 1));
        runtime.mapper().insertLoginIdentity(new LoginIdentity(a.issuer(), user, principal));
        runtime.directory().accept(a, employee(a, 1, 1, "1", user, "ACTIVE", null));
        assertThat(member(a, "1").principalId()).isEqualTo(principal);
        assertCode(
                () -> runtime.directory().accept(a, employee(a, 2, 1, "2", user, "ACTIVE", null)),
                BINDING_CONFLICT);
        assertThat(runtime.mapper().legacyBinding(a.legacySystem(), a.sourceTenantRef(), "2"))
                .isNull();
    }

    @Test
    void suspendedGlobalPrincipalCannotBeRestored() {
        var a = authority();
        String user = id();
        String principal = id();
        runtime.mapper()
                .insertPrincipal(
                        new Principal(principal, PrincipalKind.HUMAN, GlobalStatus.SUSPENDED, 2));
        runtime.mapper().insertLoginIdentity(new LoginIdentity(a.issuer(), user, principal));
        assertCode(
                () -> runtime.directory().accept(a, employee(a, 1, 1, "1", user, "ACTIVE", null)),
                BINDING_CONFLICT);
        assertThat(runtime.mapper().principal(principal).status())
                .isEqualTo(GlobalStatus.SUSPENDED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_entry WHERE source_id=?",
                                Integer.class,
                                a.id()))
                .isZero();
    }

    @Test
    void knownGlobalSuspensionDoesNotBlockLeaveOrGetRestored() {
        var a = authority();
        String user = id();
        runtime.directory().accept(a, employee(a, 1, 1, "1", user, "ACTIVE", null));
        Membership member = member(a, "1");
        runtime.lifecycle()
                .suspend(
                        new LifecycleCommand(
                                id(),
                                "security",
                                LifecycleCommand.GLOBAL_SCOPE,
                                LifecycleCommand.Operation.SUSPEND_PRINCIPAL,
                                member.principalId(),
                                1,
                                "全局暂停"));
        runtime.directory().accept(a, employee(a, 2, 2, "1", user, "LEFT", null));
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.LEFT);
        runtime.directory().accept(a, employee(a, 3, 3, "1", user, "ACTIVE", null));
        assertThat(runtime.mapper().principal(member.principalId()).status())
                .isEqualTo(GlobalStatus.SUSPENDED);
        assertCode(
                () -> runtime.identity().contextForLogin(a.issuer(), user, a.tenantId(), 2L),
                MEMBERSHIP_UNAVAILABLE);
        assertThat(quarantined(a)).isFalse();
    }

    @Test
    void concurrentIdenticalEventHasOneBusinessCommit() throws Exception {
        var a = authority();
        Event event = employee(a, 1, 1, "1", id(), "ACTIVE", null);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<DirectoryGovernance.Receipt>> calls =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    n ->
                                            (Callable<DirectoryGovernance.Receipt>)
                                                    () -> runtime.directory().accept(a, event))
                            .toList();
            for (var future : pool.invokeAll(calls)) {
                assertThat(future.get().lastSequence()).isEqualTo(1);
            }
        }
        assertThat(audits(event)).isEqualTo(1);
        assertThat(inbox(a, event)).isEqualTo(1);
    }

    @Test
    void concurrentFirstSeenIdentityAcrossTenantsReusesOnePrincipalWithoutOrphans()
            throws Exception {
        List<DirectoryAuthority> authorities =
                List.of(authority(), authority(), authority(), authority());
        String user = id();
        String trigger = "directory_race_" + id().replace("-", "");
        int before =
                jdbc.queryForObject(
                        "SELECT count(*) FROM auth_governance.principal", Integer.class);
        jdbc.execute(
                "CREATE FUNCTION auth_governance."
                        + trigger
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.subject='"
                        + user
                        + "' THEN PERFORM pg_sleep(0.2); END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON auth_governance.login_identity FOR EACH ROW EXECUTE FUNCTION auth_governance."
                        + trigger
                        + "()");
        try (var pool = Executors.newFixedThreadPool(4)) {
            CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
            List<Future<DirectoryGovernance.Receipt>> futures = new ArrayList<>();
            for (DirectoryAuthority a : authorities) {
                Event event = employee(a, 1, 1, "1", user, "ACTIVE", null);
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return runtime.directory().accept(a, event);
                                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).lastSequence()).isEqualTo(1);
            }
            assertThat(
                            authorities.stream()
                                    .map(a -> member(a, "1").principalId())
                                    .distinct()
                                    .count())
                    .isEqualTo(1);
            assertThat(authorities.stream().map(a -> member(a, "1").id()).distinct().count())
                    .isEqualTo(4);
            for (DirectoryAuthority a : authorities) {
                assertThat(quarantined(a)).isFalse();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_governance.principal",
                                    Integer.class))
                    .isEqualTo(before + 1);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON auth_governance.login_identity");
            jdbc.execute("DROP FUNCTION auth_governance." + trigger + "()");
        }
    }

    @Test
    void oldKnownVersionWithDifferentContentStillConflictsAfterNewVersion() {
        var a = authority();
        String user = id();
        runtime.directory().accept(a, employee(a, 1, 1, "1", user, "ACTIVE", null));
        runtime.directory().accept(a, employee(a, 2, 2, "1", user, "LEFT", null));
        assertCode(
                () -> runtime.directory().accept(a, employee(a, 3, 1, "1", user, "LEAVING", null)),
                BINDING_CONFLICT);
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.LEFT);
        assertThat(quarantined(a)).isTrue();
    }

    @Test
    void quarantinedSourceCanBeInspectedButCannotResumeOrChangeAuthority() {
        var a = authority();
        var original = employee(a, 1, 1, "1", id(), "ACTIVE", null);
        runtime.directory().accept(a, original);
        assertCode(
                () -> runtime.directory().accept(a, employee(a, 1, 2, "1", id(), "LEFT", null)),
                BINDING_CONFLICT);
        assertThat(runtime.directory().inspect(a))
                .isEqualTo(new DirectoryGovernance.Inspection(1, true));
        assertCode(() -> runtime.directory().checkpoint(a), BINDING_CONFLICT);
        var wrong =
                new DirectoryAuthority(
                        a.id(), a.source(), a.environment(), a.sourceTenantRef(), id(), a.issuer());
        assertCode(() -> runtime.directory().inspect(wrong), BINDING_CONFLICT);
        assertThat(runtime.directory().inspect(a).quarantined()).isTrue();
    }

    @Test
    void auditFailureRollsBackIdentityProjectionVersionAndCheckpoint() {
        var a = authority();
        String user = id();
        Event event = employee(a, 1, 1, "1", user, "ACTIVE", null);
        String trigger = "directory_test_" + id().replace("-", "");
        jdbc.execute(
                "CREATE FUNCTION auth_governance."
                        + trigger
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.command_id='"
                        + event.eventId()
                        + "' THEN RAISE EXCEPTION 'owned audit fault'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON auth_governance.audit_event FOR EACH ROW EXECUTE FUNCTION auth_governance."
                        + trigger
                        + "()");
        try {
            assertThatThrownBy(() -> runtime.directory().accept(a, event))
                    .isInstanceOf(RuntimeException.class);
            assertThat(runtime.mapper().loginIdentity(a.issuer(), user)).isNull();
            assertThat(inbox(a, event)).isZero();
            assertThat(runtime.directory().checkpoint(a).lastSequence()).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_governance.directory_version_receipt WHERE source_id=?",
                                    Integer.class,
                                    a.id()))
                    .isZero();
            assertThat(quarantined(a)).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON auth_governance.audit_event");
            jdbc.execute("DROP FUNCTION auth_governance." + trigger + "()");
        }
        assertThat(runtime.directory().accept(a, event).lastSequence()).isEqualTo(1);
    }

    @Test
    void snapshotOnlyCompletesAfterBothBoundariesAndAllActualFingerprints() throws Exception {
        var a = authority();
        String batch = id();
        Event item = employee(a, 2, 1, "1", id(), "ACTIVE", batch);
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                HexFormat.of()
                                                        .parseHex(
                                                                DirectoryEvents.eventFingerprint(
                                                                        item))));
        Event begin = control(a, batch, DirectoryAggregateType.SNAPSHOT_BEGIN, digest);
        Event end = control(a, batch, DirectoryAggregateType.SNAPSHOT_END, digest);
        assertThat(runtime.directory().accept(a, end).lastSequence()).isZero();
        assertThat(complete(a, batch)).isFalse();
        assertThat(runtime.directory().accept(a, begin).lastSequence()).isEqualTo(1);
        assertThat(complete(a, batch)).isFalse();
        assertThat(runtime.directory().accept(a, item).lastSequence()).isEqualTo(3);
        assertThat(complete(a, batch)).isTrue();
        assertThat(runtime.directory().accept(a, end).lastSequence()).isEqualTo(3);
        assertThat(
                        runtime.mapper()
                                .activeMemberships(
                                        runtime.mapper()
                                                .membership(seedMembers.get(a.id()))
                                                .principalId()))
                .hasSize(1);
    }

    @Test
    void incompleteOrCorruptSnapshotDoesNotDeleteExistingMembers() {
        var a = authority();
        String batch = id();
        Event begin = control(a, batch, DirectoryAggregateType.SNAPSHOT_BEGIN, "0".repeat(64));
        runtime.directory().accept(a, begin);
        assertThat(complete(a, batch)).isFalse();
        Event item = employee(a, 2, 1, "1", id(), "ACTIVE", batch);
        runtime.directory().accept(a, item);
        assertCode(
                () ->
                        runtime.directory()
                                .accept(
                                        a,
                                        control(
                                                a,
                                                batch,
                                                DirectoryAggregateType.SNAPSHOT_END,
                                                "0".repeat(64))),
                BINDING_CONFLICT);
        assertThat(complete(a, batch)).isFalse();
        assertThat(member(a, "1").status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(runtime.mapper().membership(seedMembers.get(a.id())).status())
                .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void multiNodeOrgCycleIsQuarantinedWithoutOverwritingTree() {
        var a = authority();
        runtime.directory().accept(a, org(a, 1, "1", "2"));
        assertCode(() -> runtime.directory().accept(a, org(a, 2, "2", "1")), BINDING_CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.directory_entry WHERE source_id=? AND aggregate_type='ORG'",
                                Integer.class,
                                a.id()))
                .isEqualTo(1);
        assertThat(quarantined(a)).isTrue();
    }

    @Test
    void realHttpCliResumesCommittedPageAfterLostAckThenProcessesLeave() throws Exception {
        var a = authority();
        String user = id();
        var events =
                List.of(
                        employee(a, 1, 1, "1", user, "ACTIVE", null),
                        employee(a, 2, 2, "1", user, "LEFT", null));
        var acked = new java.util.concurrent.atomic.AtomicLong();
        var loseAck = new java.util.concurrent.atomic.AtomicBoolean(true);
        String secret = id().replace("-", "") + id().replace("-", "");
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext(
                "/internal/directory/v1/",
                exchange -> {
                    if (!("Bearer " + secret)
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        exchange.close();
                        return;
                    }
                    int code = 200;
                    String body;
                    if (exchange.getRequestURI().getPath().endsWith("/events")) {
                        String[] params = exchange.getRequestURI().getQuery().split("&");
                        long after =
                                Long.parseLong(params[0].substring("after_sequence=".length()));
                        int limit = Integer.parseInt(params[1].substring("limit=".length()));
                        body =
                                "{\"events\":["
                                        + events.stream()
                                                .filter(e -> e.partitionSequence() > after)
                                                .limit(limit)
                                                .map(DirectoryJson::event)
                                                .collect(java.util.stream.Collectors.joining(","))
                                        + "]}";
                    } else {
                        if (exchange.getRequestURI().getPath().endsWith("/ack")) {
                            var value = json.readTree(exchange.getRequestBody());
                            long sequence = value.get("sequence").longValue();
                            if (runtime.directory().checkpoint(a).lastSequence() < sequence
                                    || !DirectoryEvents.eventFingerprint(
                                                    events.get((int) sequence - 1))
                                            .equals(value.get("fingerprint").textValue())) {
                                code = 409;
                            } else {
                                acked.accumulateAndGet(sequence, Math::max);
                            }
                            if (loseAck.getAndSet(false)) {
                                code = 503;
                            }
                        }
                        var status = new LinkedHashMap<String, Object>();
                        status.put("source", a.source());
                        status.put("environment", a.environment());
                        status.put("source_tenant_ref", a.sourceTenantRef());
                        status.put("last_sequence", 2);
                        status.put("acked_sequence", acked.get());
                        status.put(
                                "acked_fingerprint",
                                acked.get() == 0
                                        ? null
                                        : DirectoryEvents.eventFingerprint(
                                                events.get((int) acked.get() - 1)));
                        status.put("backlog", 2 - acked.get());
                        body = json.writeValueAsString(status);
                    }
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        var file =
                java.nio.file.Files.createTempFile(
                        "directory-import-it-",
                        ".properties",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------")));
        try {
            Properties config = new Properties();
            config.putAll(databaseProperties);
            config.setProperty("directory.id", a.id());
            config.setProperty("directory.source", a.source());
            config.setProperty("directory.environment", a.environment());
            config.setProperty("directory.source-tenant-ref", a.sourceTenantRef());
            config.setProperty("directory.tenant-id", a.tenantId());
            config.setProperty("directory.issuer", a.issuer());
            config.setProperty(
                    "directory.endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/directory/v1");
            config.setProperty("directory.credential", secret);
            config.setProperty("directory.operator-ref", "directory-it");
            config.setProperty("directory.max-events", "1");
            try (var out = java.nio.file.Files.newBufferedWriter(file)) {
                config.store(out, "isolated directory fixture");
            }
            var output = new java.io.StringWriter();
            var error = new java.io.StringWriter();
            java.util.function.Function<String, Integer> run =
                    command ->
                            com.lrj.authz.governance.cli.DirectoryImportCli.run(
                                    new String[] {command, file.toString()},
                                    new java.io.PrintWriter(output),
                                    new java.io.PrintWriter(error));
            assertThat(run.apply("register")).isZero();
            assertThat(run.apply("pull")).isNotZero();
            assertThat(runtime.directory().checkpoint(a).lastSequence()).isEqualTo(1);
            assertThat(member(a, "1").status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(runtime.identity().contextForLogin(a.issuer(), user, a.tenantId(), 1L))
                    .isNotNull();
            assertThat(run.apply("pull")).isZero();
            assertThat(member(a, "1").status()).isEqualTo(MemberStatus.LEFT);
            assertThat(acked.get()).isEqualTo(2);
            assertCode(
                    () -> runtime.identity().contextForLogin(a.issuer(), user, a.tenantId(), 1L),
                    MEMBERSHIP_UNAVAILABLE);
            assertThat(run.apply("pull")).isZero();
            assertThat(audits(events.getFirst())).isEqualTo(1);
            assertThat(audits(events.getLast())).isEqualTo(1);
            assertThat(output.toString() + error)
                    .doesNotContain(secret)
                    .doesNotContain(databaseProperties.getProperty("jdbc.password"));
        } finally {
            server.stop(0);
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private final Map<String, String> seedMembers = new HashMap<>();

    private DirectoryAuthority authority() {
        String tenant = id(), source = id(), seed = id();
        runtime.identity()
                .bootstrapEmployee(
                        new BootstrapCommand(
                                id(),
                                "directory-test",
                                tenant,
                                "dir-" + tenant,
                                id(),
                                "https://directory.example",
                                id(),
                                seed,
                                Instant.parse("2020-01-01T00:00:00Z"),
                                null,
                                "directory-fixture",
                                tenant,
                                "seed"));
        var a =
                new DirectoryAuthority(
                        source,
                        "oa-" + source,
                        "isolated",
                        "1",
                        tenant,
                        "https://directory.example");
        runtime.directory().register(a, "directory-test");
        seedMembers.put(source, seed);
        return a;
    }

    private Event employee(
            DirectoryAuthority a,
            long sequence,
            long version,
            String employee,
            String user,
            String status,
            String snapshot) {
        Payload payload =
                new Payload(new Employee(employee, user, status, List.of(), List.of()), null, null);
        return event(
                a, sequence, version, DirectoryAggregateType.EMPLOYEE, employee, snapshot, payload);
    }

    private Event org(DirectoryAuthority a, long sequence, String org, String parent) {
        return event(
                a,
                sequence,
                1,
                DirectoryAggregateType.ORG,
                org,
                null,
                new Payload(null, new Organization(org, parent, "ACTIVE"), null));
    }

    private Event control(
            DirectoryAuthority a, String batch, DirectoryAggregateType type, String digest) {
        return event(
                a,
                type == DirectoryAggregateType.SNAPSHOT_BEGIN ? 1 : 3,
                1,
                type,
                batch,
                batch,
                new Payload(null, null, new Snapshot(1, 3, 1, digest)));
    }

    private Event event(
            DirectoryAuthority a,
            long sequence,
            long version,
            DirectoryAggregateType type,
            String aggregate,
            String snapshot,
            Payload payload) {
        return new Event(
                1,
                id(),
                a.source(),
                a.environment(),
                a.sourceTenantRef(),
                sequence,
                type,
                aggregate,
                version,
                "2026-09-28T00:00:00Z",
                snapshot,
                payload,
                DirectoryEvents.payloadHash(type, payload));
    }

    private Membership member(DirectoryAuthority a, String employee) {
        return runtime.mapper()
                .membership(
                        runtime.mapper()
                                .legacyBinding(a.legacySystem(), a.sourceTenantRef(), employee)
                                .membershipId());
    }

    private int inbox(DirectoryAuthority a, Event event) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_governance.directory_inbox WHERE source_id=? AND event_id=?",
                Integer.class,
                a.id(),
                event.eventId());
    }

    private int audits(Event event) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_governance.audit_event WHERE command_id=? AND operation='DIRECTORY_CONSUME'",
                Integer.class,
                event.eventId());
    }

    private boolean quarantined(DirectoryAuthority a) {
        return jdbc.queryForObject(
                "SELECT quarantined FROM auth_governance.directory_source WHERE id=?",
                Boolean.class,
                a.id());
    }

    private int conflicts(DirectoryAuthority a) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_governance.directory_conflict WHERE source_id=?",
                Integer.class,
                a.id());
    }

    private boolean complete(DirectoryAuthority a, String batch) {
        return jdbc.queryForObject(
                "SELECT complete FROM auth_governance.directory_snapshot WHERE source_id=? AND snapshot_id=?",
                Boolean.class,
                a.id(),
                batch);
    }

    private static void assertCode(Runnable call, GovernanceException.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        GovernanceException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
