package com.lrj.authz.governance.projection;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真实主库与图验证同Grant范围、双水位及C新快照；不依赖本JVM缓存。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableAuthorizationIT {
    private GovernanceRuntime runtime;
    private GovernanceRuntime second;
    private JdbcTemplate jdbc;
    private SpiceDbProjectionGraph graph;
    private AuthzEngine observer;
    @BeforeAll void open() {
        var db = GovernanceDatabase.from(GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        assertThat(db.jdbcUrl()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        runtime = GovernanceRuntime.open(db, true); second = GovernanceRuntime.open(db, false);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        assertThat(p.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        graph = new SpiceDbProjectionGraph(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(3));
        observer = new SpiceDbAuthzEngine(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
    @AfterAll void close() { if (second != null) second.close(); if (runtime != null) runtime.close(); }
    private String id() { return UUID.randomUUID().toString(); }
    private BootstrapCommand person(String tenant, String code) {
        var c = new BootstrapCommand(id(), "projection-test", tenant, code, id(), "https://projection.example", id(), id(),
                Instant.parse("2020-01-01T00:00:00Z"), null, "fixture", id(), id());
        runtime.identity().bootstrapEmployee(c); return c;
    }
    private Fixture fixture() {
        String tenant = id(), code = "t-" + id(), app = "app-" + id();
        var owner = person(tenant, code); var member = person(tenant, code);
        var login = new VerifiedLogin(owner.issuer(), owner.subject());
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        runtime.catalog().publish(login, new Manifest("1", app, 1, List.of(new Capability(app + ".read", "store", Risk.NORMAL),new Capability(app + ".refund", "store", Risk.HIGH)), List.of()), id());
        var p = new Partition(tenant, app, "test");
        runtime.access().bootstrap(p, new Delegation(owner.membershipId(), 1, AccessValues.json(List.of(app + ".read",app+".refund")), 3600), "test", id());
        var role = runtime.access().createRole(login, p, id(), "reader", 1, List.of(app + ".read"));
        runtime.access().enableStrict(login, p, id());
        return new Fixture(p, login, member, role);
    }
    private Grant grant(Fixture f) {
        return runtime.access().grant(f.login, f.p, id(), f.member.membershipId(), 1, f.role.id(), "TENANT_ALL", id(), Instant.now(), Instant.now().plusSeconds(300));
    }
    private String fenceId(Fixture f) { return jdbc.queryForObject("select id from auth_governance.policy_partition where tenant_id=?", String.class, f.p.tenantId()); }
    private String state(Fixture f) { return jdbc.queryForObject("select state from auth_governance.policy_partition where tenant_id=?", String.class, f.p.tenantId()); }
    private boolean graphAllows(Fixture f, Grant grant) {
        return observer.check(SubjectRef.of(ProjectionGraph.MEMBER_TYPE, f.member.membershipId() + "_g1"), "eligible",
                ResourceRef.of(ProjectionGraph.GRANT_TYPE, grant.id()), Consistency.fullyConsistent());
    }
    private AccessContext context(Fixture f){
        var c=runtime.identity().contextForLogin(f.member.issuer(),f.member.subject(),f.p.tenantId(),null);
        return new AccessContext(c.principalId(),c.membershipId(),c.membershipGeneration(),c.membershipVersion(),c.principalVersion(),c.tenantId(),f.p.applicationId(),f.p.environment(),"test","HUMAN",id());
    }
    private void project(Fixture f){
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(Step.READY);
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.DIRECTORY,id())).isEqualTo(Step.READY);
    }
    private Facts store(Fixture f,String id){return new Facts(f.p.tenantId(),"store",id,1,null,null,List.of(),id,null);}
    @Test void realGraphPathsKeepReadAllAndRefundOneStoreSeparate(){
        var f=fixture();grant(f);
        var refund=runtime.access().createRole(f.login,f.p,id(),"refunder",1,List.of(f.p.applicationId()+".refund"));
        var rule=new Rule(1,"store",List.of(new Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S001"),false)));
        runtime.access().grantScoped(f.login,f.p,id(),f.member.membershipId(),1,refund.id(),rule,id(),Instant.now(),Instant.now().plusSeconds(60));
        project(f);var c=context(f);var auth=runtime.reliableAuthorization(graph);
        assertThat(auth.allowed(c,f.p.applicationId()+".read",store(f,"S002"))).isTrue();
        assertThat(auth.allowed(c,f.p.applicationId()+".refund",store(f,"S001"))).isTrue();
        assertThat(auth.allowed(c,f.p.applicationId()+".refund",store(f,"S002"))).isFalse();
        assertThat(auth.allowed(c,f.p.applicationId()+".read",new Facts(id(),"store","S001",1,null,null,List.of(),"S001",null))).isFalse();
        var plan=auth.evaluate(c,f.p.applicationId()+".refund","store");
        assertThat(plan.alternatives()).hasSize(1);assertThat(plan.alternatives().getFirst().clauses().getFirst().values()).containsExactly("S001");
    }
    @Test void persistedWatermarksWorkAcrossRuntimesAndRevokeBlocksOldGraph(){
        var f=fixture();var g=grant(f);project(f);var c=context(f);
        assertThat(second.reliableAuthorization(graph).allowed(c,f.p.applicationId()+".read",store(f,"S001"))).isTrue();
        runtime.access().revoke(f.login,f.p,id(),g.id(),1);
        assertThat(graphAllows(f,g)).isTrue();
        assertThatThrownBy(()->second.reliableAuthorization(graph).evaluate(c,f.p.applicationId()+".read","store")).hasMessage("AUTHZ_STATE_NOT_READY");
        project(f);
        assertThat(second.reliableAuthorization(graph).allowed(c,f.p.applicationId()+".read",store(f,"S001"))).isFalse();
    }
    @Test void bothOpaqueTokensAreRequiredAndConcurrentRevokeFailsFreshSnapshot(){
        var f=fixture();var g=grant(f);project(f);var c=context(f);
        var tokens=new ArrayList<String>();
        StrictGraphReader wrapped=(member,generation,grants,token)->{
            tokens.add(token);var result=graph.checkEligible(member,generation,grants,token);
            if(tokens.size()==2)runtime.access().revoke(f.login,f.p,id(),g.id(),1);
            return result;
        };
        String policy=jdbc.queryForObject("select zed_token from auth_governance.policy_partition where tenant_id=?",String.class,f.p.tenantId());
        String directory=jdbc.queryForObject("select zed_token from auth_governance.directory_fence where tenant_id=?",String.class,f.p.tenantId());
        assertThatThrownBy(()->runtime.reliableAuthorization(wrapped).evaluate(c,f.p.applicationId()+".read","store")).hasMessage("AUTHZ_STATE_NOT_READY");
        assertThat(tokens).containsExactly(policy,directory);
    }
    @Test void expiryDeniesWithoutProjectionCleanupAndRoleExpansionDoesNotExpandOldGrant()throws Exception{
        var f=fixture();Instant end=Instant.now().plusSeconds(3);
        var g=runtime.access().grant(f.login,f.p,id(),f.member.membershipId(),1,f.role.id(),"TENANT_ALL",id(),Instant.now().minusSeconds(1),end);
        runtime.access().createRole(f.login,f.p,id(),"reader",2,List.of(f.p.applicationId()+".read",f.p.applicationId()+".refund"));
        project(f);var c=context(f);var auth=runtime.reliableAuthorization(graph);
        assertThat(auth.allowed(c,f.p.applicationId()+".refund",store(f,"S001"))).isFalse();
        assertThat(auth.allowed(c,f.p.applicationId()+".read",store(f,"S001"))).isTrue();
        Thread.sleep(Math.max(1,Duration.between(Instant.now(),end).toMillis()+100));
        assertThat(auth.allowed(c,f.p.applicationId()+".read",store(f,"S001"))).isFalse();assertThat(graphAllows(f,g)).isTrue();
    }
    @Test void incompleteBulkMapCannotYieldPartialAllow(){
        var f=fixture();grant(f);project(f);var c=context(f);
        StrictGraphReader incomplete=(member,generation,grants,token)->Map.of();
        assertThatThrownBy(()->runtime.reliableAuthorization(incomplete).evaluate(c,f.p.applicationId()+".read","store")).hasMessage("AUTHZ_PROTOCOL_INVALID");
    }
    private record Fixture(Partition p,VerifiedLogin login,BootstrapCommand member,RoleVersion role){}
}
