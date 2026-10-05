package com.lrj.authz.governance.projection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.access.domain.AccessModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.context.application.CallerService;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.migration.application.MigrationImport;
import com.lrj.authz.governance.projection.domain.ProjectionModels.*;
import com.lrj.authz.governance.runtime.configuration.GovernanceConfigurationFile;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.protocol.CentralAccessDtos;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeAccessDtos;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.ScopeResourceBindings;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;

/** 真实PG和SpiceDB验证后台引用跨进程重读、撤权、重授及代际，不用Mock证明事务或权限。 */
class ExecutionAuthorizationIT {
    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void durableExecutionCannotGrowOrSurviveRevocationAndIdentityChanges() throws Exception {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        assertThat(db.jdbcUrl()).contains("/auth_gov_p1_test_");
        var props = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph =
                new SpiceDbProjectionGraph(
                        props.getProperty("graph.http"),
                        props.getProperty("graph.key"),
                        Duration.ofSeconds(3));
        try (var runtime = GovernanceRuntime.open(db, true);
                var second = GovernanceRuntime.open(db, false)) {
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.jdbcUrl(), db.username(), db.password()));
            String tenant = id(),
                    code = "p6-" + id(),
                    app = "commerce-" + id(),
                    capability = app + ".catalog.operate";
            var owner = person(runtime, tenant, code);
            var member = person(runtime, tenant, code);
            var login = new VerifiedLogin(owner.issuer(), owner.subject());
            runtime.catalog()
                    .register(app, owner.principalId(), "https://commerce.example", "test", id());
            runtime.catalog()
                    .publish(
                            login,
                            new Manifest(
                                    "1",
                                    app,
                                    1,
                                    List.of(new Capability(capability, "store", Risk.HIGH)),
                                    List.of()),
                            id());
            var partition = new Partition(tenant, app, "test");
            runtime.access()
                    .bootstrap(
                            partition,
                            new Delegation(
                                    owner.membershipId(),
                                    1,
                                    AccessValues.json(List.of(capability)),
                                    3600),
                            "test",
                            id());
            var role =
                    runtime.access()
                            .createRole(
                                    login,
                                    partition,
                                    id(),
                                    "catalog-operator",
                                    1,
                                    List.of(capability));
            runtime.access().enableStrict(login, partition, id());
            var rule =
                    new Rule(
                            1,
                            "store",
                            List.of(
                                    new Clause(
                                            ScopeDtos.Kind.SPECIFIED_STORES,
                                            List.of("S001"),
                                            false)));
            var grant =
                    runtime.access()
                            .grantScoped(
                                    login,
                                    partition,
                                    id(),
                                    member.membershipId(),
                                    1,
                                    role.id(),
                                    rule,
                                    id(),
                                    Instant.now(),
                                    Instant.now().plusSeconds(600));
            project(runtime, graph, partition);
            var c =
                    runtime.identity()
                            .contextForLogin(member.issuer(), member.subject(), tenant, 1L);
            var context =
                    new AccessContext(
                            c.principalId(),
                            c.membershipId(),
                            c.membershipGeneration(),
                            c.membershipVersion(),
                            c.principalVersion(),
                            tenant,
                            app,
                            "test",
                            "commerce-p6",
                            "HUMAN",
                            id());
            var request = new CentralAccessDtos.Check(tenant, 1L, id(), capability, "store");
            var issue =
                    new Issue(
                            request,
                            Instant.now()
                                    .plusSeconds(300)
                                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                    .toString());
            var executions = runtime.executions(graph);
            var reference = executions.issue(context, issue);
            assertThat(executions.issue(context, issue).executionId())
                    .isEqualTo(reference.executionId());
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.execution_reference where member=?",
                                    Integer.class,
                                    member.membershipId()))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    executions.issue(
                                            context,
                                            new Issue(
                                                    request,
                                                    Instant.now().plusSeconds(200).toString())))
                    .hasMessage("INVALID_ARGUMENT");
            var caller = mock(CallerService.class);
            when(caller.callerServiceId()).thenReturn("commerce-p6");
            when(caller.applicationId()).thenReturn(app);
            when(caller.environment()).thenReturn("test");
            var allowed = check(reference, tenant, "S001");
            assertThat(second.executions(graph).check(caller, allowed).decision())
                    .isEqualTo("ALLOW");
            assertThat(executions.check(caller, check(reference, tenant, "S002")).decision())
                    .isEqualTo("DENY");
            assertThatThrownBy(() -> executions.check(caller, check(reference, id(), "S001")))
                    .hasMessage("ACCESS_DENIED");
            when(caller.environment()).thenReturn("other");
            assertThatThrownBy(() -> executions.check(caller, allowed)).hasMessage("ACCESS_DENIED");
            when(caller.environment()).thenReturn("test");
            // 新增其他门店的合法授权不能扩大已经排队任务的签发范围。
            var otherRule =
                    new Rule(
                            1,
                            "store",
                            List.of(
                                    new Clause(
                                            ScopeDtos.Kind.SPECIFIED_STORES,
                                            List.of("S002"),
                                            false)));
            runtime.access()
                    .grantScoped(
                            login,
                            partition,
                            id(),
                            member.membershipId(),
                            1,
                            role.id(),
                            otherRule,
                            id(),
                            Instant.now(),
                            Instant.now().plusSeconds(600));
            project(runtime, graph, partition);
            assertThat(executions.check(caller, check(reference, tenant, "S002")).decision())
                    .isEqualTo("DENY");
            runtime.access().revoke(login, partition, id(), grant.id(), 1);
            assertThatThrownBy(() -> second.executions(graph).check(caller, allowed))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
            project(runtime, graph, partition);
            assertThat(executions.check(caller, allowed).decision()).isEqualTo("DENY");
            runtime.access()
                    .grantScoped(
                            login,
                            partition,
                            id(),
                            member.membershipId(),
                            1,
                            role.id(),
                            rule,
                            id(),
                            Instant.now(),
                            Instant.now().plusSeconds(600));
            project(runtime, graph, partition);
            assertThat(executions.check(caller, allowed).decision()).isEqualTo("DENY");
            // 旧版本引用不会被悄悄升级为新的身份版本。
            jdbc.update(
                    "update auth_governance.membership set version=version+1 where id=?",
                    member.membershipId());
            assertThatThrownBy(() -> executions.check(caller, allowed))
                    .hasMessage("AUTHZ_STATE_NOT_READY");
            jdbc.update(
                    "update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",
                    reference.executionId());
            assertThatThrownBy(() -> executions.check(caller, allowed)).hasMessage("ACCESS_DENIED");
            var importer = runtime.migrationImport();
            String unit = "p6-" + id();
            var item =
                    new MigrationImport.Item(
                            "source-1",
                            1,
                            member.membershipId(),
                            1,
                            role.id(),
                            rule,
                            Instant.now().minusSeconds(1).toString(),
                            Instant.now().plusSeconds(500).toString(),
                            false,
                            "explicit finite fixture");
            var batch =
                    new MigrationImport.Batch(
                            unit, 1, "a".repeat(64), "b".repeat(64), partition, List.of(item));
            com.lrj.authz.governance.migration.persistence.MigrationMapper.Row imported;
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate = new java.util.concurrent.CountDownLatch(1);
                var first =
                        workers.submit(
                                () -> {
                                    gate.await();
                                    return importer.apply(login, batch).getFirst();
                                });
                var other =
                        workers.submit(
                                () -> {
                                    gate.await();
                                    return second.migrationImport().apply(login, batch).getFirst();
                                });
                gate.countDown();
                imported = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(other.get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(imported);
            }
            assertThat(second.migrationImport().apply(login, batch).getFirst()).isEqualTo(imported);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from auth_governance.migration_record where unit_id=?",
                                    Integer.class,
                                    unit))
                    .isEqualTo(1);
            var invalid =
                    new MigrationImport.Item(
                            "source-1",
                            2,
                            member.membershipId(),
                            1,
                            role.id(),
                            rule,
                            item.validFrom(),
                            null,
                            false,
                            "no implicit permanent grant");
            assertThatThrownBy(
                            () ->
                                    importer.apply(
                                            login,
                                            new MigrationImport.Batch(
                                                    unit,
                                                    2,
                                                    "c".repeat(64),
                                                    "b".repeat(64),
                                                    partition,
                                                    List.of(invalid))))
                    .hasMessage("INVALID_ARGUMENT");
            assertThat(
                            jdbc.queryForObject(
                                    "select state from auth_governance.access_grant where id=?",
                                    String.class,
                                    imported.grantId()))
                    .isEqualTo("PENDING");
            var denial =
                    new MigrationImport.Item(
                            "source-1",
                            2,
                            member.membershipId(),
                            1,
                            role.id(),
                            rule,
                            null,
                            null,
                            true,
                            "source revoked");
            var delta =
                    new MigrationImport.Batch(
                            unit, 2, "c".repeat(64), "b".repeat(64), partition, List.of(denial));
            assertThat(importer.apply(login, delta).getFirst().tombstone()).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "select state from auth_governance.access_grant where id=?",
                                    String.class,
                                    imported.grantId()))
                    .isEqualTo("REVOKED");
            assertThatThrownBy(() -> importer.apply(login, batch)).hasMessage("VERSION_CONFLICT");
            assertThatThrownBy(
                            () ->
                                    importer.apply(
                                            login,
                                            new MigrationImport.Batch(
                                                    unit,
                                                    3,
                                                    "d".repeat(64),
                                                    "b".repeat(64),
                                                    partition,
                                                    List.of(
                                                            new MigrationImport.Item(
                                                                    "source-1",
                                                                    3,
                                                                    member.membershipId(),
                                                                    1,
                                                                    role.id(),
                                                                    rule,
                                                                    item.validFrom(),
                                                                    item.validTo(),
                                                                    false,
                                                                    "stale allow")))))
                    .hasMessage("ACCESS_DENIED");
            var latest =
                    runtime.identity()
                            .contextForLogin(member.issuer(), member.subject(), tenant, 1L);
            var newContext =
                    new AccessContext(
                            latest.principalId(),
                            latest.membershipId(),
                            latest.membershipGeneration(),
                            latest.membershipVersion(),
                            latest.principalVersion(),
                            tenant,
                            app,
                            "test",
                            "commerce-p6",
                            "HUMAN",
                            id());
            project(runtime, graph, partition);
            var fresh =
                    executions.issue(
                            newContext,
                            new Issue(
                                    new CentralAccessDtos.Check(
                                            tenant, 1L, id(), capability, "store"),
                                    Instant.now()
                                            .plusSeconds(60)
                                            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                                            .toString()));
            assertThat(executions.check(caller, check(fresh, tenant, "S002")).decision())
                    .isEqualTo("ALLOW");
            person(runtime, tenant, code);
            project(runtime, graph, partition);
            assertThatThrownBy(() -> executions.check(caller, check(fresh, tenant, "S002")))
                    .hasMessage("ACCESS_DENIED");
            assertThat(
                            jdbc.queryForObject(
                                    "select context_json || paths_json from auth_governance.execution_reference where id=?",
                                    String.class,
                                    reference.executionId()))
                    .doesNotContain("access_token", "user-token", "credential");
        }
    }

    @Test
    void inventoryReferencesAreShortLivedAndCannotSwapReadForReceive() {
        storeReferences("inventory.read", "inventory.receive");
    }

    /** 订单链路各能力独立且仅接受真实原门店路径，不能借新授扩展旧引用。 */
    @Test
    void orderOperationsReferencesUseActualStoreAndIndependentCapabilities() {
        for (String suffix :
                List.of(
                        "order.read",
                        "order.expire",
                        "order.expiry.retry",
                        "payment.read",
                        "payment.reconcile",
                        "fulfillment.read",
                        "fulfillment.ship",
                        "fulfillment.deliver",
                        "aftersale.read",
                        "aftersale.approve",
                        "aftersale.reject",
                        "aftersale.receive_return",
                        "refund.read",
                        "refund.reconcile")) storeReferences(suffix, "inventory.receive");
    }

    private void storeReferences(String readSuffix, String otherSuffix) {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph =
                new SpiceDbProjectionGraph(
                        props.getProperty("graph.http"),
                        props.getProperty("graph.key"),
                        Duration.ofSeconds(3));
        try (var runtime = GovernanceRuntime.open(db, true)) {
            String tenant = id(),
                    code = "inventory-" + id(),
                    app = "commerce-inventory-" + id(),
                    read = app + "." + readSuffix,
                    receive = app + "." + otherSuffix;
            var owner = person(runtime, tenant, code);
            var member = person(runtime, tenant, code);
            var login = new VerifiedLogin(owner.issuer(), owner.subject());
            runtime.catalog()
                    .register(app, owner.principalId(), "https://inventory.example", "test", id());
            runtime.catalog()
                    .publish(
                            login,
                            new Manifest(
                                    "1",
                                    app,
                                    1,
                                    List.of(
                                            new Capability(read, "store", Risk.NORMAL),
                                            new Capability(receive, "store", Risk.HIGH)),
                                    List.of()),
                            id());
            var partition = new Partition(tenant, app, "test");
            runtime.access()
                    .bootstrap(
                            partition,
                            new Delegation(
                                    owner.membershipId(),
                                    1,
                                    AccessValues.json(List.of(read, receive)),
                                    3600),
                            "test",
                            id());
            var role =
                    runtime.access()
                            .createRole(
                                    login, partition, id(), "inventory-reader", 1, List.of(read));
            runtime.access().enableStrict(login, partition, id());
            var rule =
                    new Rule(
                            1,
                            "store",
                            List.of(
                                    new Clause(
                                            ScopeDtos.Kind.SPECIFIED_STORES,
                                            List.of("S1"),
                                            false)));
            var grant =
                    runtime.access()
                            .grantScoped(
                                    login,
                                    partition,
                                    id(),
                                    member.membershipId(),
                                    1,
                                    role.id(),
                                    rule,
                                    id(),
                                    Instant.now(),
                                    Instant.now().plusSeconds(300));
            project(runtime, graph, partition);
            var c =
                    runtime.identity()
                            .contextForLogin(member.issuer(), member.subject(), tenant, 1L);
            var context =
                    new AccessContext(
                            c.principalId(),
                            c.membershipId(),
                            1,
                            c.membershipVersion(),
                            c.principalVersion(),
                            tenant,
                            app,
                            "test",
                            "commerce-inventory",
                            "HUMAN",
                            id());
            var executions = runtime.executions(graph);
            var request = new CentralAccessDtos.Check(tenant, 1L, id(), read, "store");
            String deadline =
                    Instant.now()
                            .plusSeconds(45)
                            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                            .toString();
            var ref = executions.issue(context, new Issue(request, deadline));
            assertThatThrownBy(
                            () ->
                                    executions.issue(
                                            context,
                                            new Issue(
                                                    new CentralAccessDtos.Check(
                                                            tenant, 1L, id(), read, "store"),
                                                    Instant.now()
                                                            .plusSeconds(300)
                                                            .truncatedTo(
                                                                    java.time.temporal.ChronoUnit
                                                                            .MILLIS)
                                                            .toString())))
                    .hasMessage("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    executions.issue(
                                            context,
                                            new Issue(
                                                    new CentralAccessDtos.Check(
                                                            tenant, 1L, id(), receive, "store"),
                                                    deadline)))
                    .hasMessage("ACCESS_DENIED");
            var caller = mock(CallerService.class);
            when(caller.callerServiceId()).thenReturn("commerce-inventory");
            when(caller.applicationId()).thenReturn(app);
            when(caller.environment()).thenReturn("test");
            assertThat(executions.check(caller, check(ref, tenant, "S1")).decision())
                    .isEqualTo("ALLOW");
            assertThat(executions.check(caller, check(ref, tenant, "S2")).decision())
                    .isEqualTo("DENY");
            var swapped =
                    new Check(
                            ref.executionId(),
                            new ScopeAccessDtos.ResourceCheck(
                                    new CentralAccessDtos.Check(tenant, 1L, id(), receive, "store"),
                                    new Facts(
                                            tenant, "store", "S1", 0, null, null, List.of(), "S1",
                                            null)));
            assertThatThrownBy(() -> executions.check(caller, swapped)).hasMessage("ACCESS_DENIED");
            runtime.access().revoke(login, partition, id(), grant.id(), 1);
            project(runtime, graph, partition);
            assertThat(executions.check(caller, check(ref, tenant, "S1")).decision())
                    .isEqualTo("DENY");
        }
    }

    @Test
    void directoryScopeIsBoundToOriginalGrantAndCreationRequiresWholeTenant() {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph =
                new SpiceDbProjectionGraph(
                        props.getProperty("graph.http"),
                        props.getProperty("graph.key"),
                        Duration.ofSeconds(3));
        try (var runtime = GovernanceRuntime.open(db, true)) {
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.jdbcUrl(), db.username(), db.password()));
            for (String suffix :
                    List.of(
                            "merchant.read",
                            "merchant.create",
                            "store.directory.read",
                            "store.create")) {
                boolean merchant = suffix.startsWith("merchant."),
                        creation = suffix.endsWith(".create");
                String type = merchant ? "merchant" : "store",
                        tenant = id(),
                        code = "directory-" + id(),
                        app = "commerce-directory-" + id(),
                        cap = app + "." + suffix;
                var owner = person(runtime, tenant, code);
                var member = person(runtime, tenant, code);
                var limited = person(runtime, tenant, code);
                var login = new VerifiedLogin(owner.issuer(), owner.subject());
                runtime.catalog()
                        .register(
                                app,
                                owner.principalId(),
                                "https://directory.example",
                                "test",
                                id());
                runtime.catalog()
                        .publish(
                                login,
                                new Manifest(
                                        "1",
                                        app,
                                        1,
                                        List.of(
                                                new Capability(
                                                        cap,
                                                        type,
                                                        creation ? Risk.HIGH : Risk.NORMAL)),
                                        List.of()),
                                id());
                var partition = new Partition(tenant, app, "test");
                runtime.access()
                        .bootstrap(
                                partition,
                                new Delegation(
                                        owner.membershipId(),
                                        1,
                                        AccessValues.json(List.of(cap)),
                                        3600),
                                "test",
                                id());
                var role =
                        runtime.access()
                                .createRole(login, partition, id(), "directory", 1, List.of(cap));
                runtime.access().enableStrict(login, partition, id());
                var partial =
                        new Rule(
                                1,
                                type,
                                List.of(
                                        new Clause(
                                                merchant
                                                        ? ScopeDtos.Kind.SPECIFIED_RESOURCES
                                                        : ScopeDtos.Kind.SPECIFIED_STORES,
                                                List.of("ONE"),
                                                false)));
                var allowed =
                        creation
                                ? new Rule(
                                        1,
                                        type,
                                        List.of(
                                                new Clause(
                                                        ScopeDtos.Kind.TENANT_ALL,
                                                        List.of(),
                                                        false)))
                                : partial;
                var grant =
                        runtime.access()
                                .grantScoped(
                                        login,
                                        partition,
                                        id(),
                                        member.membershipId(),
                                        1,
                                        role.id(),
                                        allowed,
                                        id(),
                                        Instant.now(),
                                        Instant.now().plusSeconds(300));
                runtime.access()
                        .grantScoped(
                                login,
                                partition,
                                id(),
                                limited.membershipId(),
                                1,
                                role.id(),
                                partial,
                                id(),
                                Instant.now(),
                                Instant.now().plusSeconds(300));
                project(runtime, graph, partition);
                var context = directoryContext(runtime, member, tenant, app);
                var executions = runtime.executions(graph);
                String until =
                        Instant.now()
                                .plusSeconds(45)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString();
                var request = new CentralAccessDtos.Check(tenant, 1L, id(), cap, type);
                var reference = executions.issue(context, new Issue(request, until));
                var caller = mock(CallerService.class);
                when(caller.callerServiceId()).thenReturn("commerce-directory");
                when(caller.applicationId()).thenReturn(app);
                when(caller.environment()).thenReturn("test");
                var query =
                        new ScopeCheck(
                                reference.executionId(),
                                new CentralAccessDtos.Check(tenant, 1L, id(), cap, type));
                var plan = executions.scope(caller, query);
                assertThat(plan.decision()).isEqualTo("ALLOW");
                assertThat(plan.alternatives()).hasSize(1);
                assertThat(plan.alternatives().getFirst().clauses()).isEqualTo(allowed.clauses());
                assertThat(Instant.parse(plan.validUntil()))
                        .isBeforeOrEqualTo(Instant.parse(until));
                if (creation)
                    assertThatThrownBy(
                                    () ->
                                            executions.issue(
                                                    directoryContext(runtime, limited, tenant, app),
                                                    new Issue(
                                                            new CentralAccessDtos.Check(
                                                                    tenant, 1L, id(), cap, type),
                                                            until)))
                            .hasMessage("ACCESS_DENIED");
                assertThatThrownBy(
                                () ->
                                        executions.issue(
                                                context,
                                                new Issue(
                                                        new CentralAccessDtos.Check(
                                                                tenant,
                                                                1L,
                                                                id(),
                                                                cap,
                                                                merchant ? "store" : "merchant"),
                                                        until)))
                        .hasMessage("ACCESS_DENIED");
                assertThatThrownBy(
                                () ->
                                        executions.scope(
                                                caller,
                                                new ScopeCheck(
                                                        reference.executionId(),
                                                        new CentralAccessDtos.Check(
                                                                id(), 1L, id(), cap, type))))
                        .hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("foreign");
                assertThatThrownBy(() -> executions.scope(caller, query))
                        .hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("test");
                runtime.access().revoke(login, partition, id(), grant.id(), 1);
                project(runtime, graph, partition);
                assertThat(executions.scope(caller, query).decision()).isEqualTo("DENY");
                runtime.access()
                        .grantScoped(
                                login,
                                partition,
                                id(),
                                member.membershipId(),
                                1,
                                role.id(),
                                allowed,
                                id(),
                                Instant.now(),
                                Instant.now().plusSeconds(300));
                project(runtime, graph, partition);
                assertThat(executions.scope(caller, query).alternatives()).isEmpty();
                jdbc.update(
                        "update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",
                        reference.executionId());
                assertThatThrownBy(() -> executions.scope(caller, query))
                        .hasMessage("ACCESS_DENIED");
            }
        }
    }

    /** 会员的租户范围不借门店语义，创建许可与实际目标事实分别验证。 */
    @Test
    void memberCoreReferencesBindFiniteCapabilitiesAndActualFactShape() {
        tenantScopedReferences(
                List.of(
                        "member.read",
                        "member.create",
                        "member.profile.update",
                        "member.status.update"));
    }

    /** 成长政策无虚构对象；已有会员的成长动作仍核验真实会员事实和独立能力。 */
    @Test
    void growthReferencesBindPolicyAndMemberCapabilitiesIndependently() {
        tenantScopedReferences(
                List.of(
                        "growth.policy.read",
                        "growth.policy.publish",
                        "growth.read",
                        "growth.adjust",
                        "growth.recalculate"));
    }

    /** 标签定义只取集合许可，分配和会员标签读取可使用真实会员事实；三能力相互独立。 */
    @Test
    void tagReferencesRejectFakeMemberForDefinitionAndKeepAssignmentsScoped() {
        tenantScopedReferences(
                List.of("member_tag.read", "member_tag.define", "member_tag.assign"));
    }

    /** 行为重建只获得租户集合许可，不能以伪会员事实扩大为对象执行权限。 */
    @Test
    void behaviorReferencesKeepRebuildSeparateFromProfileAndEvents() {
        tenantScopedReferences(
                List.of(
                        "member_behavior.read",
                        "member_behavior.update",
                        "member_behavior.rebuild"));
    }

    /** 周期政策和礼包定义仅集合许可，读取/考核/补发绑定真实会员，不附赠其他能力。 */
    @Test
    void cycleReferencesSeparatePolicyCollectionsFromActualMemberOperations() {
        tenantScopedReferences(
                List.of(
                        "member_cycle.policy.read",
                        "member_cycle.policy.publish",
                        "member_cycle.read",
                        "member_cycle.evaluate",
                        "cycle_benefit.read",
                        "cycle_benefit.define",
                        "cycle_benefit.grant"));
    }

    /** 积分政策集合与真实会员的调整/到期独立，不能借已有成长或周期引用替代。 */
    @Test
    void pointsReferencesSeparatePolicyFromActualWalletCommands() {
        tenantScopedReferences(
                List.of(
                        "points.policy.read",
                        "points.policy.publish",
                        "points.read",
                        "points.adjust",
                        "points.expire"));
    }

    /** 商品定义只集合，目录与停启仅接受真实商品类型，积分或门店引用不能混用。 */
    @Test
    void pointOfferReferencesSeparateDefinitionFromExistingOffers() {
        tenantScopedReferences(
                List.of("point_offer.read", "point_offer.define", "point_offer.status.update"));
    }

    /** 券定义创建和目录只取集合许可，既有商品/会员引用不能借用。 */
    @Test
    void couponDefinitionReferencesOnlyPermitVersionCollections() {
        tenantScopedReferences(List.of("coupon_definition.read", "coupon_definition.create"));
    }

    /** 权益定义只集合，实例读取/处理绑定实际grant；四能力互不替代。 */
    @Test
    void entitlementReferencesSeparateVersionCollectionsFromActualGrants() {
        tenantScopedReferences(
                List.of(
                        "entitlement_definition.read",
                        "entitlement_definition.create",
                        "entitlement.read",
                        "entitlement.resolve"));
    }

    /** 规则创建只集合，读取/发布绑定实际不可变资产版本；三能力不得互换。 */
    @Test
    void marketingRuleReferencesBindActualVersionsAndIndependentCapabilities() {
        tenantScopedReferences(List.of("rule.read", "rule.create", "rule.publish"));
    }

    /** 人群创建与摘要读取均只集合，两HIGH能力不能互相借用或构造成员事实。 */
    @Test
    void audienceReferencesOnlyPermitIndependentVersionCollections() {
        tenantScopedReferences(List.of("audience.read", "audience.create"));
    }

    /** 活动六动作绑定真实内容版本；目录/创建/预算只集合，审批和其他能力不可替代。 */
    @Test
    void campaignReferencesSeparateVersionActionsFromCollectionsAndBudget() {
        tenantScopedReferences(
                List.of(
                        "campaign.read",
                        "campaign.create",
                        "campaign.preview",
                        "campaign.submit",
                        "campaign.approve",
                        "campaign.reject",
                        "campaign.publish",
                        "campaign.pause",
                        "budget.read"));
    }

    /** 人群刷新持久引用不扩大原Grant；其他五能力仍同步且不能相互借用。 */
    @Test
    void segmentReferencesBindDefinitionsAndBoundDurableRefresh() {
        tenantScopedReferences(
                List.of(
                        "segment.read",
                        "segment.create",
                        "segment.schedule",
                        "segment.refresh",
                        "segment.control",
                        "segment.pump"));
    }

    /** 发放和撤回的长引用各自有限；read/pump同步，四能力与券定义不得互换。 */
    @Test
    void couponDeliveryReferencesSeparateDurableDirectionsAndSynchronousCapabilities() {
        tenantScopedReferences(
                List.of(
                        "coupon_delivery.create",
                        "coupon_delivery.read",
                        "coupon_delivery.control",
                        "coupon_delivery.pump"));
    }

    /** 十五旅程能力与三报表能力独立；长入组来源不能借短控制或推进能力替换。 */
    @Test
    void journeyAndReportReferencesKeepContentAndDurableSourceSeparate() {
        tenantScopedReferences(
                List.of(
                        "journey.create",
                        "journey.validate",
                        "journey.preview",
                        "journey.read",
                        "journey.submit",
                        "journey.approve",
                        "journey.reject",
                        "journey.publish",
                        "journey.pause",
                        "journey.pump",
                        "journey_instance.create",
                        "journey_instance.read",
                        "journey_instance.control",
                        "journey_scan.read",
                        "journey_scan.retry",
                        "marketing_effect.read",
                        "marketing_effect.rebuild",
                        "marketing_execution.read"));
    }

    /** 页面内容版本与运行时来源分开，后台引用不借原Grant续期。 */
    @Test
    void operationsAndRuntimeReferencesAreClosedAndFinite() {
        tenantScopedReferences(
                List.of(
                        "ops_page.read",
                        "ops_page.create",
                        "ops_page.preview",
                        "ops_page.submit",
                        "ops_page.approve",
                        "ops_page.reject",
                        "ops_page.publish",
                        "ops_page.pause",
                        "ops_page.rollback",
                        "ops_page.execute",
                        "event.read",
                        "event.retry",
                        "event.pump",
                        "runtime.read",
                        "runtime.recover",
                        "runtime.replay.preview",
                        "runtime.replay.create",
                        "runtime.replay.control",
                        "dashboard.read"));
    }

    private void tenantScopedReferences(List<String> suffixes) {
        var db =
                GovernanceDatabase.from(
                        GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph =
                new SpiceDbProjectionGraph(
                        props.getProperty("graph.http"),
                        props.getProperty("graph.key"),
                        Duration.ofSeconds(3));
        try (var runtime = GovernanceRuntime.open(db, true)) {
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.jdbcUrl(), db.username(), db.password()));
            for (String suffix : suffixes) {
                boolean policy =
                        suffix.startsWith("growth.policy.")
                                || suffix.startsWith("member_cycle.policy.")
                                || suffix.startsWith("points.policy.")
                                || suffix.equals("cycle_benefit.read")
                                || suffix.equals("cycle_benefit.define");
                boolean offer = suffix.startsWith("point_offer.");
                boolean couponDefinition = suffix.startsWith("coupon_definition.");
                boolean entitlementDefinition = suffix.startsWith("entitlement_definition.");
                boolean entitlement = suffix.startsWith("entitlement.");
                boolean ruleAsset = suffix.startsWith("rule.");
                boolean audience = suffix.startsWith("audience.");
                boolean campaign = suffix.startsWith("campaign.") || suffix.equals("budget.read");
                boolean segment = suffix.startsWith("segment.");
                boolean couponDelivery = suffix.startsWith("coupon_delivery.");
                boolean journey = suffix.startsWith("journey.");
                boolean journeyInstance = suffix.startsWith("journey_instance.");
                boolean journeyScan = suffix.startsWith("journey_scan.");
                boolean dashboard = suffix.equals("dashboard.read");
                boolean report =
                        suffix.startsWith("marketing_effect.")
                                || suffix.startsWith("marketing_execution.");
                boolean opsPage = suffix.startsWith("ops_page.");
                boolean runtimeFamily =
                        suffix.startsWith("event.") || suffix.startsWith("runtime.");
                boolean journeyFamily =
                        journey
                                || journeyInstance
                                || journeyScan
                                || report
                                || dashboard
                                || opsPage
                                || runtimeFamily;
                boolean collectionOnly =
                        dashboard
                                || suffix.equals("ops_page.create")
                                || (runtimeFamily
                                        && List.of(
                                                        "event.pump",
                                                        "runtime.replay.preview",
                                                        "runtime.replay.create")
                                                .contains(suffix))
                                || report
                                || (journey
                                        && List.of(
                                                        "journey.create",
                                                        "journey.validate",
                                                        "journey.read",
                                                        "journey.pump")
                                                .contains(suffix))
                                || suffix.equals("journey_instance.create")
                                || suffix.equals("journey_scan.read")
                                || (couponDelivery
                                        && List.of("coupon_delivery.create", "coupon_delivery.pump")
                                                .contains(suffix))
                                || (segment
                                        && List.of("segment.create", "segment.pump")
                                                .contains(suffix))
                                || (campaign
                                        && List.of(
                                                        "campaign.read",
                                                        "campaign.create",
                                                        "budget.read")
                                                .contains(suffix))
                                || audience
                                || suffix.equals("rule.create")
                                || entitlementDefinition
                                || couponDefinition
                                || policy
                                || suffix.equals("point_offer.define")
                                || suffix.equals("member.create")
                                || suffix.equals("member_tag.define")
                                || suffix.equals("member_behavior.rebuild");
                String type;
                if (dashboard) type = ScopeDtos.COMMERCE_TENANT_RESOURCE_TYPE;
                else if (opsPage) type = ScopeDtos.OPS_PAGE_RESOURCE_TYPE;
                else if (runtimeFamily) type = ScopeDtos.COMMERCE_RUNTIME_RESOURCE_TYPE;
                else if (journey) type = ScopeDtos.JOURNEY_RESOURCE_TYPE;
                else if (journeyInstance) type = ScopeDtos.JOURNEY_INSTANCE_RESOURCE_TYPE;
                else if (journeyScan) type = ScopeDtos.JOURNEY_SCAN_RESOURCE_TYPE;
                else if (report) type = ScopeDtos.MARKETING_REPORT_RESOURCE_TYPE;
                else if (couponDelivery) type = ScopeDtos.COUPON_DELIVERY_RESOURCE_TYPE;
                else if (segment) type = ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE;
                else if (campaign) type = ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE;
                else if (audience) type = ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE;
                else if (ruleAsset) type = ScopeDtos.MARKETING_RULE_RESOURCE_TYPE;
                else if (entitlementDefinition)
                    type = ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE;
                else if (entitlement) type = ScopeDtos.ENTITLEMENT_RESOURCE_TYPE;
                else if (couponDefinition) type = ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE;
                else if (offer) type = ScopeDtos.POINT_OFFER_RESOURCE_TYPE;
                else if (policy) type = ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE;
                else type = ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE;
                String tenant = id(),
                        code = "member-" + id(),
                        app = "commerce-member-" + id(),
                        cap = app + "." + suffix;
                var owner = person(runtime, tenant, code);
                var member = person(runtime, tenant, code);
                var login = new VerifiedLogin(owner.issuer(), owner.subject());
                runtime.catalog()
                        .register(app, owner.principalId(), "https://member.example", "test", id());
                runtime.catalog()
                        .publish(
                                login,
                                new Manifest(
                                        "1",
                                        app,
                                        1,
                                        List.of(new Capability(cap, type, Risk.HIGH)),
                                        List.of()),
                                id());
                var partition = new Partition(tenant, app, "test");
                runtime.access()
                        .bootstrap(
                                partition,
                                new Delegation(
                                        owner.membershipId(),
                                        1,
                                        AccessValues.json(List.of(cap)),
                                        3600),
                                "test",
                                id());
                var role =
                        runtime.access()
                                .createRole(login, partition, id(), "member-core", 1, List.of(cap));
                runtime.access().enableStrict(login, partition, id());
                var all =
                        new Rule(
                                1,
                                type,
                                List.of(new Clause(ScopeDtos.Kind.TENANT_ALL, List.of(), false)));
                var partial =
                        new Rule(
                                1,
                                type,
                                List.of(
                                        new Clause(
                                                ScopeDtos.Kind.SPECIFIED_STORES,
                                                List.of("S1"),
                                                false)));
                assertThatThrownBy(
                                () ->
                                        runtime.access()
                                                .grantScoped(
                                                        login,
                                                        partition,
                                                        id(),
                                                        member.membershipId(),
                                                        1,
                                                        role.id(),
                                                        partial,
                                                        id(),
                                                        Instant.now(),
                                                        Instant.now().plusSeconds(300)))
                        .hasMessage("SCOPE_UNSUPPORTED");
                var grant =
                        runtime.access()
                                .grantScoped(
                                        login,
                                        partition,
                                        id(),
                                        member.membershipId(),
                                        1,
                                        role.id(),
                                        all,
                                        id(),
                                        Instant.now(),
                                        Instant.now().plusSeconds(300));
                project(runtime, graph, partition);
                var context = directoryContext(runtime, member, tenant, app);
                var executions = runtime.executions(graph);
                String until =
                        Instant.now()
                                .plusSeconds(45)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString();
                var request = new CentralAccessDtos.Check(tenant, 1L, id(), cap, type);
                var ref = executions.issue(context, new Issue(request, until));
                var caller = mock(CallerService.class);
                when(caller.callerServiceId()).thenReturn("commerce-directory");
                when(caller.applicationId()).thenReturn(app);
                when(caller.environment()).thenReturn("test");
                var query = new ScopeCheck(ref.executionId(), request);
                assertThat(executions.scope(caller, query).alternatives().getFirst().clauses())
                        .isEqualTo(all.clauses());
                assertThatThrownBy(
                                () ->
                                        executions.issue(
                                                context,
                                                new Issue(
                                                        new CentralAccessDtos.Check(
                                                                tenant, 1L, id(), cap, "store"),
                                                        until)))
                        .hasMessage("ACCESS_DENIED");
                assertThatThrownBy(
                                () ->
                                        executions.issue(
                                                context,
                                                new Issue(
                                                        new CentralAccessDtos.Check(
                                                                tenant,
                                                                1L,
                                                                id(),
                                                                app + ".member.unknown",
                                                                type),
                                                        until)))
                        .hasMessage("ACCESS_DENIED");
                if (couponDefinition) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE,
                                    ScopeDtos.POINT_OFFER_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    String otherCapability =
                            app
                                    + "."
                                    + (suffix.equals("coupon_definition.read")
                                            ? "coupon_definition.create"
                                            : "coupon_definition.read");
                    assertThatThrownBy(
                                    () ->
                                            executions.scope(
                                                    caller,
                                                    new ScopeCheck(
                                                            ref.executionId(),
                                                            new CentralAccessDtos.Check(
                                                                    tenant,
                                                                    1L,
                                                                    id(),
                                                                    otherCapability,
                                                                    type))))
                            .hasMessage("ACCESS_DENIED");
                }
                if (entitlementDefinition || entitlement) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE,
                                    ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE,
                                    entitlementDefinition
                                            ? ScopeDtos.ENTITLEMENT_RESOURCE_TYPE
                                            : ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    for (String other :
                            List.of(
                                    "entitlement_definition.read",
                                    "entitlement_definition.create",
                                    "entitlement.read",
                                    "entitlement.resolve"))
                        if (!suffix.equals(other))
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(),
                                                                    new CentralAccessDtos.Check(
                                                                            tenant,
                                                                            1L,
                                                                            id(),
                                                                            app + "." + other,
                                                                            type))))
                                    .hasMessage("ACCESS_DENIED");
                }
                boolean durableRefresh = segment && suffix.equals("segment.refresh");
                boolean durableDelivery =
                        couponDelivery
                                && List.of("coupon_delivery.create", "coupon_delivery.control")
                                        .contains(suffix);
                boolean durableJourney = suffix.equals("journey_instance.create");
                boolean durableReplay = suffix.equals("runtime.replay.create");
                long rejectedSeconds =
                        durableReplay
                                ? 86520
                                : durableJourney
                                        ? 2592120
                                        : durableDelivery ? 604920 : durableRefresh ? 86520 : 120;
                assertThatThrownBy(
                                () ->
                                        executions.issue(
                                                context,
                                                new Issue(
                                                        new CentralAccessDtos.Check(
                                                                tenant, 1L, id(), cap, type),
                                                        Instant.now()
                                                                .plusSeconds(rejectedSeconds)
                                                                .truncatedTo(
                                                                        java.time.temporal
                                                                                .ChronoUnit.MILLIS)
                                                                .toString())))
                        .hasMessage("INVALID_ARGUMENT");
                long acceptedSeconds =
                        durableReplay
                                ? 86459
                                : durableJourney ? 2592059 : durableDelivery ? 604859 : 86400;
                var durableTask =
                        (durableRefresh || durableDelivery || durableJourney || durableReplay)
                                ? executions.issue(
                                        context,
                                        new Issue(
                                                new CentralAccessDtos.Check(
                                                        tenant, 1L, id(), cap, type),
                                                Instant.now()
                                                        .plusSeconds(acceptedSeconds)
                                                        .truncatedTo(
                                                                java.time.temporal.ChronoUnit
                                                                        .MILLIS)
                                                        .toString()))
                                : null;
                if (durableTask != null) {
                    assertThat(Instant.parse(durableTask.expiresAt()))
                            .isAfter(Instant.now().plusSeconds(acceptedSeconds - 100));
                    var lasting =
                            executions.scope(
                                    caller, new ScopeCheck(durableTask.executionId(), request));
                    assertThat(lasting.decision()).isEqualTo("ALLOW");
                    // 持久引用不会延长原300秒Grant，实时许可仍保留更短截止。
                    assertThat(Instant.parse(lasting.validUntil()))
                            .isBefore(Instant.parse(durableTask.expiresAt()));
                }
                if (audience) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.MARKETING_RULE_RESOURCE_TYPE,
                                    ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    String other =
                            app
                                    + "."
                                    + (suffix.equals("audience.read")
                                            ? "audience.create"
                                            : "audience.read");
                    assertThatThrownBy(
                                    () ->
                                            executions.scope(
                                                    caller,
                                                    new ScopeCheck(
                                                            ref.executionId(),
                                                            new CentralAccessDtos.Check(
                                                                    tenant, 1L, id(), other,
                                                                    type))))
                            .hasMessage("ACCESS_DENIED");
                }
                if (ruleAsset) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.ENTITLEMENT_RESOURCE_TYPE,
                                    ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    for (String other : List.of("rule.read", "rule.create", "rule.publish"))
                        if (!suffix.equals(other))
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(),
                                                                    new CentralAccessDtos.Check(
                                                                            tenant,
                                                                            1L,
                                                                            id(),
                                                                            app + "." + other,
                                                                            type))))
                                    .hasMessage("ACCESS_DENIED");
                }
                if (campaign) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.MARKETING_RULE_RESOURCE_TYPE,
                                    ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE,
                                    ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    for (String other :
                            List.of(
                                    "campaign.read",
                                    "campaign.create",
                                    "campaign.preview",
                                    "campaign.submit",
                                    "campaign.approve",
                                    "campaign.reject",
                                    "campaign.publish",
                                    "campaign.pause",
                                    "budget.read"))
                        if (!suffix.equals(other)) {
                            var swap =
                                    new CentralAccessDtos.Check(
                                            tenant, 1L, id(), app + "." + other, type);
                            assertThatThrownBy(
                                            () -> executions.issue(context, new Issue(swap, until)))
                                    .hasMessage("ACCESS_DENIED");
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(), swap)))
                                    .hasMessage("ACCESS_DENIED");
                        }
                    var serviceContext =
                            new AccessContext(
                                    context.principalId(),
                                    context.membershipId(),
                                    context.membershipGeneration(),
                                    context.membershipVersion(),
                                    context.principalVersion(),
                                    tenant,
                                    app,
                                    "test",
                                    "commerce-directory",
                                    "SERVICE",
                                    id());
                    assertThatThrownBy(
                                    () ->
                                            executions.issue(
                                                    serviceContext, new Issue(request, until)))
                            .hasMessage("ACCESS_DENIED");
                }
                if (segment) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE,
                                    ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE,
                                    ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    for (String other :
                            List.of(
                                    "segment.read",
                                    "segment.create",
                                    "segment.schedule",
                                    "segment.refresh",
                                    "segment.control",
                                    "segment.pump"))
                        if (!suffix.equals(other)) {
                            var swap =
                                    new CentralAccessDtos.Check(
                                            tenant, 1L, id(), app + "." + other, type);
                            assertThatThrownBy(
                                            () -> executions.issue(context, new Issue(swap, until)))
                                    .hasMessage("ACCESS_DENIED");
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(), swap)))
                                    .hasMessage("ACCESS_DENIED");
                        }
                    var serviceContext =
                            new AccessContext(
                                    context.principalId(),
                                    context.membershipId(),
                                    context.membershipGeneration(),
                                    context.membershipVersion(),
                                    context.principalVersion(),
                                    tenant,
                                    app,
                                    "test",
                                    "commerce-directory",
                                    "SERVICE",
                                    id());
                    assertThatThrownBy(
                                    () ->
                                            executions.issue(
                                                    serviceContext, new Issue(request, until)))
                            .hasMessage("ACCESS_DENIED");
                }
                if (couponDelivery) {
                    for (String wrongType :
                            List.of(
                                    ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE,
                                    ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE,
                                    ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE))
                        assertThatThrownBy(
                                        () ->
                                                executions.issue(
                                                        context,
                                                        new Issue(
                                                                new CentralAccessDtos.Check(
                                                                        tenant, 1L, id(), cap,
                                                                        wrongType),
                                                                until)))
                                .hasMessage("ACCESS_DENIED");
                    for (String other :
                            List.of(
                                    "coupon_delivery.create",
                                    "coupon_delivery.read",
                                    "coupon_delivery.control",
                                    "coupon_delivery.pump",
                                    "coupon_definition.create",
                                    "segment.pump",
                                    "campaign.create"))
                        if (!suffix.equals(other)) {
                            var swap =
                                    new CentralAccessDtos.Check(
                                            tenant, 1L, id(), app + "." + other, type);
                            assertThatThrownBy(
                                            () -> executions.issue(context, new Issue(swap, until)))
                                    .hasMessage("ACCESS_DENIED");
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(), swap)))
                                    .hasMessage("ACCESS_DENIED");
                        }
                    var serviceContext =
                            new AccessContext(
                                    context.principalId(),
                                    context.membershipId(),
                                    context.membershipGeneration(),
                                    context.membershipVersion(),
                                    context.principalVersion(),
                                    tenant,
                                    app,
                                    "test",
                                    "commerce-directory",
                                    "SERVICE",
                                    id());
                    assertThatThrownBy(
                                    () ->
                                            executions.issue(
                                                    serviceContext, new Issue(request, until)))
                            .hasMessage("ACCESS_DENIED");
                    when(caller.applicationId()).thenReturn("other");
                    assertThatThrownBy(() -> executions.scope(caller, query))
                            .hasMessage("ACCESS_DENIED");
                    when(caller.applicationId()).thenReturn(app);
                    when(caller.callerServiceId()).thenReturn("other");
                    assertThatThrownBy(() -> executions.scope(caller, query))
                            .hasMessage("ACCESS_DENIED");
                    when(caller.callerServiceId()).thenReturn("commerce-directory");
                }
                if (journeyFamily) {
                    var serviceContext =
                            new AccessContext(
                                    context.principalId(),
                                    context.membershipId(),
                                    context.membershipGeneration(),
                                    context.membershipVersion(),
                                    context.principalVersion(),
                                    tenant,
                                    app,
                                    "test",
                                    "commerce-directory",
                                    "SERVICE",
                                    id());
                    assertThatThrownBy(
                                    () ->
                                            executions.issue(
                                                    serviceContext, new Issue(request, until)))
                            .hasMessage("ACCESS_DENIED");
                    for (String other :
                            List.of(
                                    "journey.create",
                                    "journey.validate",
                                    "journey.preview",
                                    "journey.read",
                                    "journey.submit",
                                    "journey.approve",
                                    "journey.reject",
                                    "journey.publish",
                                    "journey.pause",
                                    "journey.pump",
                                    "journey_instance.create",
                                    "journey_instance.read",
                                    "journey_instance.control",
                                    "journey_scan.read",
                                    "journey_scan.retry",
                                    "marketing_effect.read",
                                    "marketing_effect.rebuild",
                                    "marketing_execution.read"))
                        if (!suffix.equals(other))
                            assertThatThrownBy(
                                            () ->
                                                    executions.scope(
                                                            caller,
                                                            new ScopeCheck(
                                                                    ref.executionId(),
                                                                    new CentralAccessDtos.Check(
                                                                            tenant,
                                                                            1L,
                                                                            id(),
                                                                            app + "." + other,
                                                                            type))))
                                    .hasMessage("ACCESS_DENIED");
                }
                String actualId =
                        couponDelivery
                                ? "BATCH-1"
                                : segment
                                        ? "SEGMENT-1"
                                        : campaign
                                                ? "CAMPAIGN-1"
                                                : audience
                                                        ? "AUDIENCE-1"
                                                        : ruleAsset
                                                                ? "RULE-1"
                                                                : entitlement
                                                                        ? "GRANT-1"
                                                                        : "MEMBER-1";
                var facts = new Facts(tenant, type, actualId, 7, null, null, List.of(), null, null);
                var resource =
                        new Check(
                                ref.executionId(),
                                new ScopeAccessDtos.ResourceCheck(request, facts));
                if (collectionOnly)
                    assertThatThrownBy(() -> executions.check(caller, resource))
                            .hasMessage("ACCESS_DENIED");
                else {
                    var decision = executions.check(caller, resource);
                    assertThat(decision.decision()).isEqualTo("ALLOW");
                    assertThat(decision.resourceId()).isEqualTo(actualId);
                    assertThat(decision.resourceVersion()).isEqualTo(7);
                }
                if (durableTask != null)
                    try (var second = GovernanceRuntime.open(db, false)) {
                        assertThat(
                                        second.executions(graph)
                                                .scope(
                                                        caller,
                                                        new ScopeCheck(
                                                                durableTask.executionId(), request))
                                                .decision())
                                .isEqualTo("ALLOW");
                        if (!collectionOnly)
                            assertThat(
                                            second.executions(graph)
                                                    .check(
                                                            caller,
                                                            new Check(
                                                                    durableTask.executionId(),
                                                                    new ScopeAccessDtos
                                                                            .ResourceCheck(
                                                                            request, facts)))
                                                    .decision())
                                    .isEqualTo("ALLOW");
                    }
                if (campaign
                        || segment
                        || couponDelivery
                        || journey
                        || journeyInstance
                        || journeyScan
                        || opsPage)
                    for (long wrongVersion : List.of(0L, -1L)) {
                        var badVersion =
                                new Facts(
                                        tenant,
                                        type,
                                        actualId,
                                        wrongVersion,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null);
                        assertThat(ScopeResourceBindings.validFacts(badVersion)).isFalse();
                        assertThatThrownBy(
                                        () ->
                                                executions.check(
                                                        caller,
                                                        new Check(
                                                                ref.executionId(),
                                                                new ScopeAccessDtos.ResourceCheck(
                                                                        request, badVersion))))
                                .hasMessage("INVALID_ARGUMENT");
                    }
                for (var bad :
                        List.of(
                                new Facts(
                                        tenant,
                                        type,
                                        "MEMBER-1",
                                        7,
                                        null,
                                        null,
                                        List.of(),
                                        "S1",
                                        null),
                                new Facts(
                                        tenant, "store", "S1", 7, null, null, List.of(), "S1",
                                        null),
                                new Facts(
                                        id(),
                                        type,
                                        "MEMBER-1",
                                        7,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null)))
                    assertThatThrownBy(
                                    () ->
                                            executions.check(
                                                    caller,
                                                    new Check(
                                                            ref.executionId(),
                                                            new ScopeAccessDtos.ResourceCheck(
                                                                    request, bad))))
                            .hasMessage("INVALID_ARGUMENT");
                assertThatThrownBy(
                                () ->
                                        executions.scope(
                                                caller,
                                                new ScopeCheck(
                                                        ref.executionId(),
                                                        new CentralAccessDtos.Check(
                                                                tenant, 2L, id(), cap, type))))
                        .hasMessage("ACCESS_DENIED");
                assertThatThrownBy(
                                () ->
                                        executions.scope(
                                                caller,
                                                new ScopeCheck(
                                                        ref.executionId(),
                                                        new CentralAccessDtos.Check(
                                                                tenant,
                                                                1L,
                                                                id(),
                                                                app + ".member.other",
                                                                type))))
                        .hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("other");
                assertThatThrownBy(() -> executions.scope(caller, query))
                        .hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("test");
                runtime.access().revoke(login, partition, id(), grant.id(), 1);
                project(runtime, graph, partition);
                assertThat(executions.scope(caller, query).decision()).isEqualTo("DENY");
                if (durableTask != null)
                    assertThat(
                                    executions
                                            .scope(
                                                    caller,
                                                    new ScopeCheck(
                                                            durableTask.executionId(), request))
                                            .decision())
                            .isEqualTo("DENY");
                if (!collectionOnly)
                    assertThat(executions.check(caller, resource).decision()).isEqualTo("DENY");
                runtime.access()
                        .grantScoped(
                                login,
                                partition,
                                id(),
                                member.membershipId(),
                                1,
                                role.id(),
                                all,
                                id(),
                                Instant.now(),
                                Instant.now().plusSeconds(300));
                project(runtime, graph, partition);
                assertThat(executions.scope(caller, query).alternatives()).isEmpty();
                if (durableTask != null)
                    assertThat(
                                    executions
                                            .scope(
                                                    caller,
                                                    new ScopeCheck(
                                                            durableTask.executionId(), request))
                                            .alternatives())
                            .isEmpty();
                jdbc.update(
                        "update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",
                        ref.executionId());
                assertThatThrownBy(() -> executions.scope(caller, query))
                        .hasMessage("ACCESS_DENIED");
                if (durableTask != null) {
                    // 仅测试库将准确原引用置为到期，证明持久重读会拒绝，不冒充等待七天。
                    jdbc.update(
                            "update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",
                            durableTask.executionId());
                    assertThatThrownBy(
                                    () ->
                                            executions.scope(
                                                    caller,
                                                    new ScopeCheck(
                                                            durableTask.executionId(), request)))
                            .hasMessage("ACCESS_DENIED");
                }
            }
        }
    }

    private static AccessContext directoryContext(
            GovernanceRuntime runtime, BootstrapCommand member, String tenant, String app) {
        var c = runtime.identity().contextForLogin(member.issuer(), member.subject(), tenant, 1L);
        return new AccessContext(
                c.principalId(),
                c.membershipId(),
                1,
                c.membershipVersion(),
                c.principalVersion(),
                tenant,
                app,
                "test",
                "commerce-directory",
                "HUMAN",
                id());
    }

    private static Check check(Reference ref, String tenant, String store) {
        return new Check(
                ref.executionId(),
                new ScopeAccessDtos.ResourceCheck(
                        new CentralAccessDtos.Check(tenant, 1L, id(), ref.capability(), "store"),
                        new Facts(tenant, "store", store, 1, null, null, List.of(), store, null)));
    }

    private static BootstrapCommand person(GovernanceRuntime r, String tenant, String code) {
        var c =
                new BootstrapCommand(
                        id(),
                        "execution-test",
                        tenant,
                        code,
                        id(),
                        "https://execution.example",
                        id(),
                        id(),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "fixture",
                        id(),
                        id());
        r.identity().bootstrapEmployee(c);
        return c;
    }

    private static void project(
            GovernanceRuntime r, SpiceDbProjectionGraph graph, Partition partition) {
        assertThat(
                        r.reliableProjector(graph)
                                .step(
                                        partition,
                                        com.lrj.authz.governance.projection.domain.ProjectionModels
                                                .Kind.POLICY,
                                        id()))
                .isEqualTo(Step.READY);
        assertThat(
                        r.reliableProjector(graph)
                                .step(
                                        partition,
                                        com.lrj.authz.governance.projection.domain.ProjectionModels
                                                .Kind.DIRECTORY,
                                        id()))
                .isEqualTo(Step.READY);
    }
}
