package com.lrj.authz.governance.shared.persistence;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.access.application.PortalDiagnosticAuthority;
import com.lrj.authz.governance.access.application.PortalPermissions;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.application.CatalogImpact;
import com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Report;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.catalog.persistence.CatalogImpactMapper;
import com.lrj.authz.governance.directory.application.DirectoryAuthority;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.portal.persistence.PortalMapper;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.ScopeDtos.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.*;

/** 真实PG验证潜在来源资格、人数去重、分页和诊断边界；人工ACTIVE夹具不冒充图ALLOW。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogImpactPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;

    @BeforeAll
    void open() {
        Properties properties = new Properties();
        String file = System.getenv("GOVERNANCE_TEST_CONFIG");
        if (file != null) properties = GovernanceConfigurationFile.read(file);
        else {
            properties.setProperty("jdbc.url", System.getenv("GOVERNANCE_TEST_DB_URL"));
            properties.setProperty("jdbc.username", System.getenv("GOVERNANCE_TEST_DB_USER"));
            properties.setProperty("jdbc.password", System.getenv("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        assertThat(properties.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(properties);
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
        var command =
                new BootstrapCommand(
                        id(),
                        "impact-test",
                        tenant,
                        code,
                        id(),
                        "https://impact.example",
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "impact-test",
                        id(),
                        id());
        runtime.identity().bootstrapEmployee(command);
        return command;
    }

    private VerifiedLogin login(BootstrapCommand person) {
        return new VerifiedLogin(person.issuer(), person.subject());
    }

    private record Fixture(
            BootstrapCommand owner, Partition partition, Manifest current, RoleVersion role) {}

    private Fixture fixture() {
        var owner = person(id(), "impact-" + id());
        String app = "impact-" + id();
        runtime.catalog()
                .register(app, owner.principalId(), "https://business.test", "impact-test", id());
        var current =
                new Manifest(
                        "1",
                        app,
                        1,
                        List.of(
                                new Capability(app + ".read", "store", Risk.NORMAL),
                                new Capability(app + ".write", "store", Risk.HIGH)),
                        List.of(
                                new Menu(
                                        "items",
                                        null,
                                        "/items",
                                        List.of(app + ".read"),
                                        "商品列表",
                                        0)));
        runtime.catalog().publish(login(owner), current, id());
        var p = new Partition(owner.tenantId(), app, "test");
        runtime.access()
                .bootstrap(
                        p,
                        new Delegation(
                                owner.membershipId(),
                                1,
                                AccessValues.json(List.of(app + ".read", app + ".write")),
                                3600),
                        "impact-test",
                        id());
        var role =
                runtime.access()
                        .createRole(login(owner), p, id(), "reader", 1, List.of(app + ".read"));
        return new Fixture(owner, p, current, role);
    }

    private Manifest candidate(Fixture f) {
        return new Manifest(
                "1",
                f.partition.applicationId(),
                2,
                f.current.capabilities(),
                List.of(
                        new Menu(
                                "items",
                                null,
                                "/item-records",
                                List.of(f.partition.applicationId() + ".read"),
                                "商品档案",
                                1)));
    }

    private PortalPermissions diagnostics(Fixture f) {
        return runtime.portalPermissions(
                List.of(new PortalDiagnosticAuthority(f.partition, f.owner.membershipId(), 1)));
    }

    private Report report(Fixture f) {
        return diagnostics(f).catalogImpact(login(f.owner), f.partition, candidate(f), null);
    }

    private Grant grant(Fixture f, BootstrapCommand member, String role, Instant from) {
        return runtime.access()
                .grant(
                        login(f.owner),
                        f.partition,
                        id(),
                        member.membershipId(),
                        1,
                        role,
                        "TENANT_ALL",
                        id(),
                        from,
                        Instant.now().plusSeconds(500));
    }

    private void active(Grant grant) {
        // 只构造SQL候选状态；没有图回执或READY保证，统计不得据此宣称实际业务访问。
        jdbc.update(
                "update auth_governance.access_grant set state='ACTIVE',zed_token='impact-fixture',updated_at=clock_timestamp() where id=?",
                grant.id());
    }

    @Test
    void repeatedSourcesAndRolesDeduplicatePeopleAndKeepPendingSeparate() {
        var f = fixture();
        var a = person(f.owner.tenantId(), f.owner.tenantCode());
        var b = person(f.owner.tenantId(), f.owner.tenantCode());
        var other =
                runtime.access()
                        .createRole(
                                login(f.owner),
                                f.partition,
                                id(),
                                "second_reader",
                                1,
                                List.of(f.partition.applicationId() + ".read"));
        active(grant(f, a, f.role.id(), Instant.now().minusSeconds(5)));
        active(grant(f, a, other.id(), Instant.now().minusSeconds(5)));
        grant(f, b, f.role.id(), Instant.now().minusSeconds(5));
        var result = report(f);
        assertThat(result.stats().roleCount()).isEqualTo(2);
        assertThat(result.stats().activeGrantCount()).isEqualTo(2);
        assertThat(result.stats().pendingGrantCount()).isEqualTo(1);
        assertThat(result.stats().activePeopleCount()).isEqualTo(1);
        assertThat(result.stats().pendingPeopleCount()).isEqualTo(1);
        assertThat(result.stats().peopleCount()).isEqualTo(2);
        assertThat(result.sources()).hasSize(3);
        assertThat(result.basisHash()).matches("[0-9a-f]{64}");
        assertThat(result.fences().policyState()).isEqualTo("LEGACY");
    }

    @Test
    void revokedExpiredFutureSuspendedAndOldGenerationSourcesAreExcluded() {
        var f = fixture();
        var valid = person(f.owner.tenantId(), f.owner.tenantCode());
        active(grant(f, valid, f.role.id(), Instant.now().minusSeconds(5)));
        var revoked = grant(f, valid, f.role.id(), Instant.now().minusSeconds(5));
        runtime.access().revoke(login(f.owner), f.partition, id(), revoked.id(), 1);
        var expired = grant(f, valid, f.role.id(), Instant.now().minusSeconds(5));
        jdbc.update(
                "update auth_governance.access_grant set valid_to=clock_timestamp()-interval '1 second' where id=?",
                expired.id());
        grant(f, valid, f.role.id(), Instant.now().plusSeconds(100));
        var suspended = person(f.owner.tenantId(), f.owner.tenantCode());
        active(grant(f, suspended, f.role.id(), Instant.now().minusSeconds(5)));
        jdbc.update(
                "update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",
                suspended.principalId());
        var old = person(f.owner.tenantId(), f.owner.tenantCode());
        active(grant(f, old, f.role.id(), Instant.now().minusSeconds(5)));
        jdbc.update(
                "update auth_governance.membership set generation=generation+1,version=version+1 where id=?",
                old.membershipId());
        var result = report(f);
        assertThat(result.stats().activeGrantCount()).isEqualTo(1);
        assertThat(result.stats().pendingGrantCount()).isZero();
        assertThat(result.stats().peopleCount()).isEqualTo(1);
        assertThat(result.sources())
                .singleElement()
                .extracting(
                        com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Source
                                ::memberId)
                .isEqualTo(valid.membershipId());
    }

    @Test
    void ownerAndManagerStillNeedExplicitCurrentDiagnosticQualification() {
        var f = fixture();
        var other = person(f.owner.tenantId(), f.owner.tenantCode());
        assertThatThrownBy(
                        () ->
                                runtime.portalPermissions(List.of())
                                        .catalogImpact(
                                                login(f.owner), f.partition, candidate(f), null))
                .hasMessage("ACCESS_DENIED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_governance.portal_diagnostic_audit where tenant_id=? and operation='READ_CATALOG_IMPACT' and outcome='DENIED'",
                                Integer.class,
                                f.owner.tenantId()))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                diagnostics(f)
                                        .catalogImpact(
                                                login(other), f.partition, candidate(f), null))
                .hasMessage("ACCESS_DENIED");
        var stale =
                runtime.portalPermissions(
                        List.of(
                                new PortalDiagnosticAuthority(
                                        f.partition, f.owner.membershipId(), 2)));
        assertThatThrownBy(
                        () -> stale.catalogImpact(login(f.owner), f.partition, candidate(f), null))
                .hasMessage("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                diagnostics(f)
                                        .catalogImpact(
                                                login(f.owner),
                                                new Partition(
                                                        f.partition.tenantId(),
                                                        f.partition.applicationId(),
                                                        "production"),
                                                candidate(f),
                                                null))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void cursorTraversesMoreThanOneHundredSourcesWhileStatsRemainComplete() {
        var f = fixture();
        var a = person(f.owner.tenantId(), f.owner.tenantCode());
        var b = person(f.owner.tenantId(), f.owner.tenantCode());
        for (int i = 0; i < 60; i++) {
            grant(f, a, f.role.id(), Instant.now().minusSeconds(5));
            grant(f, b, f.role.id(), Instant.now().minusSeconds(5));
        }
        var first = report(f);
        assertThat(first.stats().pendingGrantCount()).isEqualTo(120);
        assertThat(first.sources()).hasSize(100);
        assertThat(first.nextCursor()).isNotNull();
        var second =
                diagnostics(f)
                        .catalogImpact(
                                login(f.owner), f.partition, candidate(f), first.nextCursor());
        assertThat(second.sources()).hasSize(20);
        assertThat(second.roles()).isEmpty();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(second.basisHash()).isEqualTo(first.basisHash());
        var ids =
                new HashSet<>(
                        first.sources().stream()
                                .map(
                                        com.lrj.authz.governance.catalog.domain.CatalogImpactModels
                                                        .Source
                                                ::grantId)
                                .toList());
        assertThat(second.sources()).noneMatch(s -> ids.contains(s.grantId()));
        runtime.access()
                .revoke(login(f.owner), f.partition, id(), first.sources().getFirst().grantId(), 1);
        assertThatThrownBy(
                        () ->
                                diagnostics(f)
                                        .catalogImpact(
                                                login(f.owner),
                                                f.partition,
                                                candidate(f),
                                                first.nextCursor()))
                .hasMessage("VERSION_CONFLICT");
    }

    @Test
    void groupAndDirectOverlapUseCurrentAssignmentDatesAndDoNotCountExpiredMembers() {
        var f = fixture();
        var a = person(f.owner.tenantId(), f.owner.tenantCode());
        var b = person(f.owner.tenantId(), f.owner.tenantCode());
        var past = person(f.owner.tenantId(), f.owner.tenantCode());
        active(grant(f, a, f.role.id(), Instant.now().minusSeconds(5)));
        var authority =
                new DirectoryAuthority(
                        id(), "oa-" + id(), "test", "1", f.owner.tenantId(), f.owner.issuer());
        runtime.directory().register(authority, "impact-test");
        jdbc.update(
                "update auth_governance.directory_source set business_zone='UTC' where id=?",
                authority.id());
        String group = id();
        jdbc.update(
                "insert into auth_governance.directory_group(id,source_id,tenant_id,org_ref,active) values(?,?,?,?,true)",
                group,
                authority.id(),
                f.owner.tenantId(),
                id());
        for (var member : List.of(a, b))
            groupMember(group, f, member, "[{\"valid_from\":\"2000-01-01\",\"valid_to\":null}]");
        groupMember(
                group, f, past, "[{\"valid_from\":\"2000-01-01\",\"valid_to\":\"2001-01-01\"}]");
        runtime.access().enableStrict(login(f.owner), f.partition, id());
        var rule =
                new Rule(
                        1,
                        "store",
                        List.of(new Clause(Kind.SPECIFIED_STORES, List.of("S1"), false)));
        var grouped =
                runtime.access()
                        .grantGroup(
                                login(f.owner),
                                f.partition,
                                id(),
                                group,
                                f.role.id(),
                                rule,
                                id(),
                                Instant.now().minusSeconds(5),
                                Instant.now().plusSeconds(400));
        active(grouped);
        var result = report(f);
        assertThat(result.stats().groupGrantCount()).isEqualTo(1);
        assertThat(result.stats().activeGrantCount()).isEqualTo(2);
        assertThat(result.stats().activePeopleCount()).isEqualTo(2);
        assertThat(result.sources()).hasSize(3);
        assertThat(result.sources()).noneMatch(s -> past.membershipId().equals(s.memberId()));
        jdbc.update(
                "update auth_governance.directory_source set business_zone=null where id=?",
                authority.id());
        var missingZone = report(f);
        assertThat(missingZone.stats().groupGrantCount()).isZero();
        assertThat(missingZone.stats().peopleCount()).isEqualTo(1);
    }

    @Test
    void newCapabilityWithoutRoleReportsZeroDependenciesWithoutInventingGrant() {
        var f = fixture();
        var caps = new ArrayList<>(f.current.capabilities());
        caps.add(new Capability(f.partition.applicationId() + ".new", "store", Risk.NORMAL));
        var next =
                new Manifest(
                        "1",
                        f.partition.applicationId(),
                        2,
                        caps,
                        List.of(
                                f.current.menus().getFirst(),
                                new Menu(
                                        "new",
                                        null,
                                        "/new",
                                        List.of(f.partition.applicationId() + ".new"),
                                        "新页面",
                                        1)));
        var result = diagnostics(f).catalogImpact(login(f.owner), f.partition, next, null);
        assertThat(result.stats().roleCount()).isZero();
        assertThat(result.stats().peopleCount()).isZero();
        assertThat(result.sources()).isEmpty();
        assertThat(result.completeness())
                .isEqualTo(
                        com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Completeness
                                .COMPLETE);
    }

    @Test
    void revokedManagementAndInactiveTargetDoNotReturnEmptyStatistics() {
        var f = fixture();
        jdbc.update(
                "update auth_governance.access_delegation set enabled=false where tenant_id=? and application_id=?",
                f.partition.tenantId(),
                f.partition.applicationId());
        assertThatThrownBy(() -> report(f)).hasMessage("ACCESS_DENIED");
        var app = fixture();
        jdbc.update(
                "update auth_governance.tenant_application set enabled=false where tenant_id=? and application_id=?",
                app.partition.tenantId(),
                app.partition.applicationId());
        assertThatThrownBy(() -> report(app)).hasMessage("ACCESS_DENIED");
    }

    @Test
    void emptyGroupRemainsASourceAndWrongEnvironmentOrQuarantineExcludesIt() {
        var f = fixture();
        var authority =
                new DirectoryAuthority(
                        id(), "oa-" + id(), "test", "1", f.owner.tenantId(), f.owner.issuer());
        runtime.directory().register(authority, "impact-test");
        jdbc.update(
                "update auth_governance.directory_source set business_zone='UTC' where id=?",
                authority.id());
        String group = id();
        jdbc.update(
                "insert into auth_governance.directory_group(id,source_id,tenant_id,org_ref,active) values(?,?,?,?,true)",
                group,
                authority.id(),
                f.owner.tenantId(),
                id());
        runtime.access().enableStrict(login(f.owner), f.partition, id());
        var grouped =
                runtime.access()
                        .grantGroup(
                                login(f.owner),
                                f.partition,
                                id(),
                                group,
                                f.role.id(),
                                new Rule(
                                        1,
                                        "store",
                                        List.of(
                                                new Clause(
                                                        Kind.SPECIFIED_STORES,
                                                        List.of("S1"),
                                                        false))),
                                id(),
                                Instant.now().minusSeconds(5),
                                Instant.now().plusSeconds(400));
        active(grouped);
        var result = report(f);
        assertThat(result.stats().groupGrantCount()).isEqualTo(1);
        assertThat(result.stats().peopleCount()).isZero();
        assertThat(result.sources())
                .singleElement()
                .satisfies(
                        source -> {
                            assertThat(source.memberId()).isNull();
                            assertThat(source.scopeRule().resourceType()).isEqualTo("store");
                        });
        jdbc.update(
                "update auth_governance.directory_source set environment='wrong' where id=?",
                authority.id());
        assertThat(report(f).stats().groupGrantCount()).isZero();
        jdbc.update(
                "update auth_governance.directory_source set environment='test',quarantined=true where id=?",
                authority.id());
        var quarantined = report(f);
        assertThat(quarantined.stats().groupGrantCount()).isZero();
        assertThat(quarantined.fences().sourceQuarantined()).isTrue();
    }

    @Test
    void roleAndDisabledPolicyListsPageIndependentlyOfFullCounts() {
        var f = fixture();
        jdbc.update(
                "insert into auth_governance.role_version select gen_random_uuid()::text,tenant_id,application_id,environment,'bulk_'||n,version,capabilities_json,content_hash,created_by,created_at from auth_governance.role_version cross join generate_series(1,120) n where id=?",
                f.role.id());
        runtime.access().enableStrict(login(f.owner), f.partition, id());
        var policy =
                runtime.requests()
                        .registerPolicy(
                                login(f.owner),
                                f.partition,
                                id(),
                                f.role.id(),
                                new Rule(
                                        1,
                                        "store",
                                        List.of(
                                                new Clause(
                                                        Kind.SPECIFIED_STORES,
                                                        List.of("S1"),
                                                        false))),
                                60,
                                f.owner.membershipId(),
                                1,
                                1);
        jdbc.update(
                "insert into auth_governance.request_policy select gen_random_uuid()::text,tenant_id,application_id,environment,role_id,scope_json,max_duration_seconds,approver_membership_id,approver_generation,delegation_hash,policy_version,content_hash,false,created_by,created_at from auth_governance.request_policy cross join generate_series(1,120) where id=?",
                policy.id());
        var first = report(f);
        assertThat(first.stats().roleCount()).isEqualTo(121);
        assertThat(first.stats().requestPolicyCount()).isEqualTo(121);
        assertThat(first.roles()).hasSize(100);
        assertThat(first.policies()).hasSize(100);
        assertThat(first.sources()).isEmpty();
        var second =
                diagnostics(f)
                        .catalogImpact(
                                login(f.owner), f.partition, candidate(f), first.nextCursor());
        assertThat(second.roles()).hasSize(21);
        assertThat(second.policies()).hasSize(21);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(
                        java.util.stream.Stream.concat(
                                        first.policies().stream(), second.policies().stream())
                                .filter(
                                        com.lrj.authz.governance.catalog.domain.CatalogImpactModels
                                                        .Policy
                                                ::enabled)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void oversizedBasisStillReturnsFullCountsButNoConfirmationOrCursor() {
        var f = fixture();
        // 隔离PG生成真实行，验证服务端边界，不用Mock的count证明全量语义。
        jdbc.update(
                "insert into auth_governance.role_version select gen_random_uuid()::text,tenant_id,application_id,environment,'bulk_'||n,version,capabilities_json,content_hash,created_by,created_at from auth_governance.role_version cross join generate_series(1,10001) n where id=?",
                f.role.id());
        var result = report(f);
        assertThat(result.stats().roleCount()).isEqualTo(10002);
        assertThat(result.roles()).hasSize(100);
        assertThat(result.completeness())
                .isEqualTo(
                        com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Completeness
                                .BASIS_LIMIT_EXCEEDED);
        assertThat(result.basisHash()).isNull();
        assertThat(result.nextCursor()).isNull();
        explain(f);
    }

    @Test
    void freshSecondSqlRejectsConcurrentRevocationAndLostManager() {
        var f = fixture();
        var target = person(f.owner.tenantId(), f.owner.tenantCode());
        var grant = grant(f, target, f.role.id(), Instant.now().minusSeconds(5));
        var changed =
                analyzerAfterFirstSql(
                        () ->
                                runtime.access()
                                        .revoke(login(f.owner), f.partition, id(), grant.id(), 1));
        assertThatThrownBy(() -> changed.analyze(login(f.owner), f.partition, candidate(f), null))
                .hasMessage("VERSION_CONFLICT");
        var lost = fixture();
        var manager =
                analyzerAfterFirstSql(
                        () ->
                                jdbc.update(
                                        "update auth_governance.access_delegation set enabled=false where tenant_id=? and application_id=?",
                                        lost.partition.tenantId(),
                                        lost.partition.applicationId()));
        assertThatThrownBy(
                        () ->
                                manager.analyze(
                                        login(lost.owner), lost.partition, candidate(lost), null))
                .hasMessage("ACCESS_DENIED");
    }

    /** 测试装饰实际Runtime Mapper，在两个真实SQL快照之间提交竞争事务，不扩展生产访问器。 */
    private CatalogImpact analyzerAfterFirstSql(Runnable concurrentCommit) {
        try {
            var impactField = GovernanceRuntime.class.getDeclaredField("catalogImpactMapper");
            impactField.setAccessible(true);
            var portalField = GovernanceRuntime.class.getDeclaredField("portalMapper");
            portalField.setAccessible(true);
            var real = (CatalogImpactMapper) impactField.get(runtime);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            CatalogImpactMapper decorated =
                    (p, issuer, subject, caps, cursor, limit, basisLimit) -> {
                        var row = real.report(p, issuer, subject, caps, cursor, limit, basisLimit);
                        if (calls.incrementAndGet() == 1) concurrentCommit.run();
                        return row;
                    };
            return new CatalogImpact(decorated, (PortalMapper) portalField.get(runtime));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 使用同一XML的BoundSql和绑定参数测真实计划，不在生产应用服务拼接SQL。 */
    private void explain(Fixture f) {
        var configuration = new org.apache.ibatis.session.Configuration();
        for (String file : List.of("PortalMapper.xml", "CatalogImpactMapper.xml")) {
            try (var stream =
                    getClass().getClassLoader().getResourceAsStream("mappers/governance/" + file)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                                stream, configuration, file, configuration.getSqlFragments())
                        .parse();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
        var params = new HashMap<String, Object>();
        params.put("p", f.partition);
        params.put("issuer", f.owner.issuer());
        params.put("subject", f.owner.subject());
        params.put("caps", List.of(f.partition.applicationId() + ".read"));
        params.put(
                "cursor",
                new com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Cursor(
                        null, "", "", "", ""));
        params.put("limit", 101);
        params.put("basisLimit", 10001);
        var sql =
                configuration
                        .getMappedStatement(
                                "com.lrj.authz.governance.catalog.persistence.CatalogImpactMapper.report")
                        .getBoundSql(params);
        var values =
                sql.getParameterMappings().stream()
                        .map(
                                mapping ->
                                        sql.hasAdditionalParameter(mapping.getProperty())
                                                ? sql.getAdditionalParameter(mapping.getProperty())
                                                : configuration
                                                        .newMetaObject(params)
                                                        .getValue(mapping.getProperty()))
                        .toArray();
        String plan =
                jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, FORMAT JSON) " + sql.getSql(), String.class, values);
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan);
            double elapsed = tree.get(0).get("Execution Time").asDouble();
            assertThat(elapsed).isLessThan(4000);
            var path =
                    java.nio.file.Path.of(
                            "..", ".local", "menu-role-governance", "mg03-query-plan.private.json");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, plan);
            java.nio.file.Files.setPosixFilePermissions(
                    path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            System.out.println("MG03 isolated 10002-role query execution ms=" + elapsed);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void groupMember(String group, Fixture f, BootstrapCommand member, String periods) {
        jdbc.update(
                "insert into auth_governance.directory_group_member(group_id,tenant_id,membership_id,generation,current,periods) values(?,?,?,1,true,?::jsonb)",
                group,
                f.owner.tenantId(),
                member.membershipId(),
                periods);
    }
}
