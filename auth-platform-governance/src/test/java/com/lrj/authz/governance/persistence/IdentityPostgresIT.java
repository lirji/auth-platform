package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.GovernanceException;
import com.lrj.authz.governance.cli.GovernanceCli;
import com.lrj.authz.governance.domain.IdentityModels.*;
import org.junit.jupiter.api.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** 仅在显式隔离 PostgreSQL 上执行，不用 H2/Mock 证明事务、约束和并发。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    private GovernanceDatabase database;

    @BeforeAll void connectOnlyToDedicatedTestDatabase() throws Exception {
        Properties props = new Properties();
        String config = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (config != null) {
            Path file = Path.of(config);
            assertThat(Files.isSymbolicLink(file)).isFalse();
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            try (var reader = Files.newBufferedReader(file)) { props.load(reader); }
        } else {
            props.setProperty("jdbc.url", required("GOVERNANCE_TEST_DB_URL"));
            props.setProperty("jdbc.username", required("GOVERNANCE_TEST_DB_USER"));
            props.setProperty("jdbc.password", required("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        // 精确数据库路径，无 query trick、共享 authz/OA 库或生产目标。
        assertThat(props.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        database = GovernanceDatabase.from(props);
        runtime = GovernanceRuntime.open(database, true);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        assertThat(jdbc.queryForObject("SELECT current_setting('server_version_num')::integer", Integer.class)).isBetween(160000, 169999);
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
    }

    @AfterAll void closePoolWithoutDeletingFixtures() { if (runtime != null) { runtime.close(); } }

    @Test void persistsAndReplaysWithoutDuplicatingAuditOrLegacyMapping() {
        BootstrapCommand command = fixture();
        Membership first = runtime.identity().bootstrapEmployee(command);
        assertThat(runtime.identity().bootstrapEmployee(command)).isEqualTo(first);
        assertThat(runtime.identity().membershipsForLogin(command.issuer(), command.subject())).containsExactly(first);
        assertThat(runtime.mapper().legacyBinding(command.sourceSystem(), command.sourceTenantRef(), command.sourceSubjectRef()).membershipId())
                .isEqualTo(first.id());
        assertThat(auditCount(command)).isEqualTo(1);
        assertThat(commandCount(command)).isEqualTo(1);
    }

    @Test void sameIssuerSubjectConflictRollsBackAllNewRecords() {
        BootstrapCommand original = fixture();
        runtime.identity().bootstrapEmployee(original);
        BootstrapCommand conflicting = new BootstrapCommand(id(), original.operatorRef(), id(), "new-" + id(), id(),
                original.issuer(), original.subject(), id(), original.validFrom(), null, "fixture", id(), id());
        expectCode(() -> runtime.identity().bootstrapEmployee(conflicting), GovernanceException.Code.BINDING_CONFLICT);
        assertThat(runtime.mapper().principal(conflicting.principalId())).isNull();
        assertThat(runtime.mapper().tenant(conflicting.tenantId())).isNull();
        assertThat(commandCount(conflicting)).isZero();
        assertThat(runtime.mapper().loginIdentity(original.issuer(), original.subject()).principalId()).isEqualTo(original.principalId());
    }

    @Test void commandPayloadConflictCannotExtendMembership() {
        BootstrapCommand command = fixture();
        runtime.identity().bootstrapEmployee(command);
        BootstrapCommand conflict = new BootstrapCommand(command.commandId(), command.operatorRef(), command.tenantId(), command.tenantCode(),
                command.principalId(), command.issuer(), command.subject(), command.membershipId(), command.validFrom(),
                Instant.parse("2099-01-01T00:00:00Z"), command.sourceSystem(), command.sourceTenantRef(), command.sourceSubjectRef());
        expectCode(() -> runtime.identity().bootstrapEmployee(conflict), GovernanceException.Code.COMMAND_CONFLICT);
        assertThat(runtime.mapper().membership(command.membershipId()).validTo()).isNull();
        assertThat(auditCount(command)).isEqualTo(1);
    }

    @Test void oneHumanCanBelongToTwoTenantsWithoutMergingOtherSubjects() {
        BootstrapCommand a = fixture();
        Membership one = runtime.identity().bootstrapEmployee(a);
        BootstrapCommand b = new BootstrapCommand(id(), a.operatorRef(), id(), "other-" + id(), a.principalId(),
                a.issuer(), a.subject(), id(), a.validFrom(), null, "fixture", id(), id());
        Membership two = runtime.identity().bootstrapEmployee(b);
        BootstrapCommand other = fixture();
        runtime.identity().bootstrapEmployee(other);
        assertThat(runtime.identity().membershipsForLogin(a.issuer(), a.subject())).containsExactlyInAnyOrder(one, two);
        assertThat(runtime.identity().membershipsForLogin(other.issuer(), other.subject())).hasSize(1);
        expectCode(() -> runtime.identity().membershipsForLogin("https://unknown.example", a.subject()), GovernanceException.Code.IDENTITY_NOT_BOUND);
    }

    @Test void membershipUniquenessAndLegacyConflictDoNotLeaveOrphans() {
        BootstrapCommand a = fixture();
        runtime.identity().bootstrapEmployee(a);
        BootstrapCommand duplicate = new BootstrapCommand(id(), a.operatorRef(), a.tenantId(), a.tenantCode(), a.principalId(),
                a.issuer(), a.subject(), id(), a.validFrom(), null, "fixture", id(), id());
        expectCode(() -> runtime.identity().bootstrapEmployee(duplicate), GovernanceException.Code.BINDING_CONFLICT);
        assertThat(runtime.mapper().membership(duplicate.membershipId())).isNull();
        BootstrapCommand b = fixture();
        BootstrapCommand legacyConflict = new BootstrapCommand(b.commandId(), b.operatorRef(), b.tenantId(), b.tenantCode(), b.principalId(),
                b.issuer(), b.subject(), b.membershipId(), b.validFrom(), null, a.sourceSystem(), a.sourceTenantRef(), a.sourceSubjectRef());
        expectCode(() -> runtime.identity().bootstrapEmployee(legacyConflict), GovernanceException.Code.BINDING_CONFLICT);
        assertThat(runtime.mapper().principal(b.principalId())).isNull();
        assertThat(commandCount(legacyConflict)).isZero();
    }

    @Test void concurrentCommandCreatesExactlyOneMemberAndAudit() throws Exception {
        BootstrapCommand command = fixture();
        try (var threads = Executors.newFixedThreadPool(4)) {
            Callable<Membership> work = () -> runtime.identity().bootstrapEmployee(command);
            var results = threads.invokeAll(List.of(work, work, work, work));
            for (var result : results) { assertThat(result.get().id()).isEqualTo(command.membershipId()); }
        }
        assertThat(auditCount(command)).isEqualTo(1);
        assertThat(commandCount(command)).isEqualTo(1);
    }

    @Test void currentStatusAndDatabaseCasPreventOldVersionOverwrite() throws Exception {
        BootstrapCommand command = fixture();
        runtime.identity().bootstrapEmployee(command);
        try (var threads = Executors.newFixedThreadPool(2)) {
            Callable<Integer> cas = () -> runtime.mapper().compareAndSetMemberStatus(command.membershipId(), 1, MemberStatus.ACTIVE, MemberStatus.SUSPENDED);
            var results = threads.invokeAll(List.of(cas, cas));
            assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(1);
        }
        assertThat(runtime.mapper().membership(command.membershipId()).version()).isEqualTo(2);
        assertThat(runtime.identity().membershipsForLogin(command.issuer(), command.subject())).isEmpty();
        assertThat(runtime.identity().bootstrapEmployee(command).status()).isEqualTo(MemberStatus.SUSPENDED);
    }

    @Test void auditDatabaseFailureRollsBackIdentityAndCommand() {
        BootstrapCommand command = fixture();
        String suffix = id().replace("-", "");
        String function = "fail_audit_" + suffix;
        // 唯一测试操作人匹配的数据库故障，不影响同库其他夹具；保留隔离测试证据。
        jdbc.execute("CREATE FUNCTION auth_governance." + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'isolated audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON auth_governance.audit_event FOR EACH ROW "
                + "WHEN (NEW.operator_ref = '" + command.operatorRef() + "') EXECUTE FUNCTION auth_governance." + function + "()");
        assertThatThrownBy(() -> runtime.identity().bootstrapEmployee(command)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("isolated audit failure");
        assertThat(runtime.mapper().principal(command.principalId())).isNull();
        assertThat(runtime.mapper().tenant(command.tenantId())).isNull();
        assertThat(runtime.mapper().loginIdentity(command.issuer(), command.subject())).isNull();
        assertThat(commandCount(command)).isZero();
        assertThat(auditCount(command)).isZero();
    }

    @Test void databaseTimeAndTenantPrincipalStatusControlEffectiveMembership() {
        BootstrapCommand a = fixture();
        runtime.identity().bootstrapEmployee(a);
        jdbc.update("UPDATE auth_governance.tenant SET status = 'SUSPENDED', version = version + 1 WHERE id = ?", a.tenantId());
        assertThat(runtime.identity().membershipsForLogin(a.issuer(), a.subject())).isEmpty();
        BootstrapCommand b = fixture();
        runtime.identity().bootstrapEmployee(b);
        jdbc.update("UPDATE auth_governance.principal SET status = 'SUSPENDED', version = version + 1 WHERE id = ?", b.principalId());
        expectCode(() -> runtime.identity().membershipsForLogin(b.issuer(), b.subject()), GovernanceException.Code.MEMBERSHIP_UNAVAILABLE);
        BootstrapCommand c = fixture();
        BootstrapCommand expired = new BootstrapCommand(c.commandId(), c.operatorRef(), c.tenantId(), c.tenantCode(), c.principalId(),
                c.issuer(), c.subject(), c.membershipId(), Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z"),
                c.sourceSystem(), c.sourceTenantRef(), c.sourceSubjectRef());
        runtime.identity().bootstrapEmployee(expired);
        assertThat(runtime.identity().membershipsForLogin(c.issuer(), c.subject())).isEmpty();
    }

    @Test void allApplicationTablesAndColumnsHaveChineseCommentsAndMigrationRevalidates() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='auth_governance' AND c.relkind='r' AND c.relname <> 'flyway_schema_history' "
                + "AND obj_description(c.oid) IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='auth_governance' AND c.relkind='r' "
                + "AND c.relname <> 'flyway_schema_history' AND a.attnum>0 AND NOT a.attisdropped "
                + "AND col_description(c.oid,a.attnum) IS NULL", Integer.class)).isZero();
        try (var reopened = GovernanceRuntime.open(database, false)) {
            assertThat(reopened.identity()).isNotNull();
        }
    }

    @Test void cliRequiresPrivateFilesAndDoesNotEchoCredentials() throws Exception {
        Path config = Files.createTempFile("governance-cli", ".properties");
        Files.writeString(config, "jdbc.url=jdbc:postgresql://127.0.0.1:1/auth_gov_p1_test_dummy\njdbc.username=dummy\njdbc.password=dummy-fixture\n");
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
        StringWriter out = new StringWriter(); StringWriter err = new StringWriter();
        assertThat(GovernanceCli.run(new String[]{"lookup", config.toString(), config.toString()}, new PrintWriter(out), new PrintWriter(err))).isEqualTo(2);
        assertThat(err.toString()).contains("INVALID_ARGUMENT").doesNotContain(database.password(), database.jdbcUrl());
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    }

    private int auditCount(BootstrapCommand c) { return jdbc.queryForObject("SELECT count(*) FROM auth_governance.audit_event WHERE command_id=? AND operator_ref=?", Integer.class, c.commandId(), c.operatorRef()); }
    private int commandCount(BootstrapCommand c) { return jdbc.queryForObject("SELECT count(*) FROM auth_governance.command_record WHERE command_id=? AND operator_ref=?", Integer.class, c.commandId(), c.operatorRef()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static BootstrapCommand fixture() {
        return new BootstrapCommand(id(), "test-operator-" + id(), id(), "tenant-" + id(), id(), "https://identity.example", id(), id(),
                Instant.parse("2020-01-01T00:00:00Z"), null, "fixture", id(), id());
    }
    private static void expectCode(ThrowingCallable action, GovernanceException.Code code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(GovernanceException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) { throw new IllegalStateException("隔离集成测试配置缺失: " + key); }
        return value;
    }
}
