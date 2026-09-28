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
    private String state(Fixture f) { return jdbc.queryForObject("select state from auth_governance.policy_partition where tenant_id=? and application_id=? and environment=?", String.class, f.p.tenantId(),f.p.applicationId(),f.p.environment()); }
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
    @Test void productScopeRequiresBothTrustedStoreAndProductOnSameGrant(){
        var f=fixture();String cap=f.p.applicationId()+".product.read";
        runtime.catalog().publish(f.login,new Manifest("1",f.p.applicationId(),2,List.of(
            new Capability(f.p.applicationId()+".read","store",Risk.NORMAL),new Capability(f.p.applicationId()+".refund","store",Risk.HIGH),new Capability(cap,"product",Risk.NORMAL)),List.of()),id());
        // 独立隔离环境显式授予新增能力上限；不修改原固定委派或RoleVersion。
        var owner=runtime.identity().contextForLogin(f.login.issuer(),f.login.subject(),f.p.tenantId(),null);
        var partition=new Partition(f.p.tenantId(),f.p.applicationId(),"product-test");
        runtime.access().bootstrap(partition,new Delegation(owner.membershipId(),owner.membershipGeneration(),AccessValues.json(List.of(cap)),3600),"test",id());
        var role=runtime.access().createRole(f.login,partition,id(),"product-reader",1,List.of(cap));runtime.access().enableStrict(f.login,partition,id());
        var product=new Fixture(partition,f.login,f.member,role);
        var rule=new Rule(1,"product",List.of(new Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S001"),false),new Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_RESOURCES,List.of("P001"),false)));
        runtime.access().grantScoped(f.login,partition,id(),f.member.membershipId(),1,role.id(),rule,id(),Instant.now(),Instant.now().plusSeconds(60));project(product);
        var auth=runtime.reliableAuthorization(graph);var c=context(product);
        assertThat(auth.allowed(c,cap,new Facts(partition.tenantId(),"product","P001",1,null,null,List.of(),"S001",null))).isTrue();
        assertThat(auth.allowed(c,cap,new Facts(partition.tenantId(),"product","P002",1,null,null,List.of(),"S001",null))).isFalse();
        assertThat(auth.allowed(c,cap,new Facts(partition.tenantId(),"product","P001",1,null,null,List.of(),"S002",null))).isFalse();
    }
    private void awaitAllowed(Fixture f,String store){
        // 独立双水位允许保守短暂拒绝；等待图量化快照追平，不把旧DENY改为ALLOW。
        var auth=second.reliableAuthorization(graph);var c=context(f);long deadline=System.nanoTime()+10_000_000_000L;
        boolean allowed;
        do{allowed=auth.allowed(c,f.p.applicationId()+".read",store(f,store));if(!allowed)java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);}while(!allowed&&System.nanoTime()<deadline);
        assertThat(allowed).isTrue();
    }
    private DirectoryAuthority directory(Fixture f){
        var source=new DirectoryAuthority(id(),"oa-"+id(),f.p.environment(),"1",f.p.tenantId(),f.member.issuer());
        runtime.directory().register(source,"test");
        consume(source,1,1,DirectoryEvents.DirectoryAggregateType.ORG,"1",new DirectoryEvents.Payload(null,new DirectoryEvents.Organization("1",null,"ACTIVE"),null));
        consume(source,2,1,DirectoryEvents.DirectoryAggregateType.ORG,"2",new DirectoryEvents.Payload(null,new DirectoryEvents.Organization("2",null,"ACTIVE"),null));
        return source;
    }
    private void consume(DirectoryAuthority source,long sequence,long version,DirectoryEvents.DirectoryAggregateType type,String aggregate,DirectoryEvents.Payload payload){
        runtime.directory().accept(source,new DirectoryEvents.Event(1,id(),source.source(),source.environment(),source.sourceTenantRef(),sequence,type,aggregate,version,
            Instant.now().toString(),null,payload,DirectoryEvents.payloadHash(type,payload)));
    }
    private void employee(Fixture f,DirectoryAuthority source,long sequence,long version,String status,String org,String from,String to,String type){
        consume(source,sequence,version,DirectoryEvents.DirectoryAggregateType.EMPLOYEE,"1",new DirectoryEvents.Payload(
            new DirectoryEvents.Employee("1",f.member.subject(),status,List.of(new DirectoryEvents.Assignment("1",org,type,false,from,to)),List.of()),null,null));
    }
    private Grant groupGrant(Fixture f,String group){
        return runtime.access().grantGroup(f.login,f.p,id(),group,f.role.id(),new Rule(1,"store",List.of(new Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S001"),false))),id(),Instant.now(),Instant.now().plusSeconds(300));
    }
    private String group(Fixture f,String org){return runtime.access().groups(f.login,f.p,null).stream().filter(g->g.orgRef().equals(org)).findFirst().orElseThrow().id();}
    @Test void directoryMovementRevokesOldGroupBeforeGraphAndPreservesIndependentGrant(){
        var f=fixture();var source=directory(f);
        runtime.directory().configureBusinessZone(source,"UTC","test",id());
        employee(f,source,3,1,"ACTIVE","1","2020-01-01",null,"PRIMARY");
        var group=groupGrant(f,group(f,"1"));project(f);var c=context(f);var auth=second.reliableAuthorization(graph);
        assertThat(graphAllows(f,group)).isTrue();
        awaitAllowed(f,"S001");
        assertThat(auth.allowed(c,f.p.applicationId()+".read",store(f,"S002"))).isFalse();
        employee(f,source,4,2,"ACTIVE","2","2020-01-01",null,"PRIMARY");
        assertThat(graphAllows(f,group)).isTrue();
        assertThatThrownBy(()->auth.evaluate(c,f.p.applicationId()+".read","store")).hasMessage("AUTHZ_STATE_NOT_READY");
        project(f);assertThat(auth.allowed(context(f),f.p.applicationId()+".read",store(f,"S001"))).isFalse();assertThat(graphAllows(f,group)).isFalse();
        grant(f);project(f);awaitAllowed(f,"S001");
        employee(f,source,5,3,"ACTIVE","1","2020-01-01",null,"PRIMARY");project(f);
        runtime.access().revoke(f.login,f.p,id(),group.id(),1);project(f);
        awaitAllowed(f,"S002");
    }
    @Test void leftRejoinChangesGenerationAndDoesNotRestoreRevokedGroupGrant(){
        var f=fixture();var source=directory(f);runtime.directory().configureBusinessZone(source,"UTC","test",id());
        employee(f,source,3,1,"ACTIVE","1","2020-01-01",null,"PRIMARY");var g=groupGrant(f,group(f,"1"));project(f);var old=context(f);
        employee(f,source,4,2,"LEFT","1","2020-01-01",null,"PRIMARY");project(f);
        assertThat(graphAllows(f,g)).isFalse();
        runtime.access().revoke(f.login,f.p,id(),g.id(),1);project(f);
        employee(f,source,5,3,"ACTIVE","1","2020-01-01",null,"PRIMARY");project(f);
        assertThat(context(f).membershipGeneration()).isEqualTo(2);
        assertThatThrownBy(()->runtime.reliableAuthorization(graph).evaluate(old,f.p.applicationId()+".read","store")).isInstanceOf(GovernanceException.class);
        assertThat(runtime.reliableAuthorization(graph).allowed(context(f),f.p.applicationId()+".read",store(f,"S001"))).isFalse();
    }
    @Test void directoryClockRequiresExactOperationalAuthorityAndStableCommand() {
        var f=fixture();var source=directory(f);String command=id();
        runtime.directory().configureBusinessZone(source,"UTC","test",command);
        runtime.directory().configureBusinessZone(source,"UTC","test",command);
        assertThatThrownBy(()->runtime.directory().configureBusinessZone(source,"Asia/Shanghai","test",command)).hasMessage("COMMAND_CONFLICT");
        var wrong=new DirectoryAuthority(source.id(),source.source(),"other",source.sourceTenantRef(),source.tenantId(),source.issuer());
        assertThatThrownBy(()->runtime.directory().configureBusinessZone(wrong,"UTC","test",id())).hasMessage("BINDING_CONFLICT");
        assertThatThrownBy(()->runtime.directory().configureBusinessZone(source,"unknown","test",id())).hasMessage("INVALID_ARGUMENT");
    }
    @Test void sourceClockAndAssignmentDatesAreAuthoritativeEvenWhenGraphHasRelationship(){
        var f=fixture();var source=directory(f);
        employee(f,source,3,1,"ACTIVE","1","2020-01-01",null,"PRIMARY");
        assertThatThrownBy(()->groupGrant(f,group(f,"1"))).hasMessage("ACCESS_DENIED");
        runtime.directory().configureBusinessZone(source,"UTC","test",id());var g=groupGrant(f,group(f,"1"));
        employee(f,source,4,2,"ACTIVE","1","2020-01-01",LocalDate.now(ZoneOffset.UTC).toString(),"PRIMARY");project(f);
        assertThat(graphAllows(f,g)).isTrue();assertThat(runtime.reliableAuthorization(graph).allowed(context(f),f.p.applicationId()+".read",store(f,"S001"))).isFalse();
        employee(f,source,5,3,"ACTIVE","1",LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(),null,"PRIMARY");project(f);
        assertThat(runtime.reliableAuthorization(graph).allowed(context(f),f.p.applicationId()+".read",store(f,"S001"))).isFalse();
        employee(f,source,6,4,"ACTIVE","1","2020-01-01",null,"DOTTED");project(f);
        assertThat(graphAllows(f,g)).isFalse();
    }
    @Test void emergencyCapabilityDisableIsOwnerOnlyAuditedIdempotentAndFencesEveryPartition(){
        var f=fixture();grant(f);project(f);String cap=f.p.applicationId()+".read",command=id();
        var owner=runtime.identity().contextForLogin(f.login.issuer(),f.login.subject(),f.p.tenantId(),null);
        var other=new Partition(f.p.tenantId(),f.p.applicationId(),"second");
        runtime.access().bootstrap(other,new Delegation(owner.membershipId(),owner.membershipGeneration(),AccessValues.json(List.of(cap)),3600),"test",id());
        runtime.access().enableStrict(f.login,other,id());
        assertThat(runtime.reliableProjector(graph).step(other,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(Step.READY);
        assertThatThrownBy(()->runtime.catalog().changeCapability(new VerifiedLogin(f.member.issuer(),f.member.subject()),f.p.applicationId(),cap,true,0,"test",id())).hasMessage("ACCESS_DENIED");
        var result=runtime.catalog().changeCapability(f.login,f.p.applicationId(),cap,true,0,"incident",command);
        assertThat(jdbc.queryForObject("select count(*) from auth_governance.policy_partition where application_id=? and state='UPDATING'",Integer.class,f.p.applicationId())).isEqualTo(2);
        assertThat(result.disabled()).isTrue();assertThat(result.version()).isEqualTo(1);assertThat(state(f)).isEqualTo("UPDATING");
        assertThat(runtime.catalog().changeCapability(f.login,f.p.applicationId(),cap,true,0,"incident",command)).isEqualTo(result);
        assertThatThrownBy(()->runtime.catalog().changeCapability(f.login,f.p.applicationId(),cap,false,0,"incident",command)).hasMessage("COMMAND_CONFLICT");
        project(f);assertThat(second.reliableAuthorization(graph).allowed(context(f),cap,store(f,"S001"))).isFalse();
        runtime.catalog().changeCapability(f.login,f.p.applicationId(),cap,false,1,"resolved",id());project(f);
        assertThat(second.reliableAuthorization(graph).allowed(context(f),cap,store(f,"S001"))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from auth_governance.capability_command where application_id=?",Integer.class,f.p.applicationId())).isEqualTo(2);
    }
    @Test void revokeReceiptNeedsGraphConfirmationAndRetryCannotOverwriteUnknownMarker(){
        var f=fixture();var g=grant(f);project(f);runtime.access().revoke(f.login,f.p,id(),g.id(),1);
        var accepted=runtime.access().revocationReceipt(f.login,f.p,g.id());assertThat(accepted.status()).isEqualTo("PROCESSING");assertThat(accepted.operationId()).isNull();
        project(f);var done=runtime.access().revocationReceipt(f.login,f.p,g.id());assertThat(done.status()).isEqualTo("COMPLETED");assertThat(done.operationId()).isNotBlank();
        String fence=fenceId(f);var marker=graph.readMarker(fence).orElseThrow();graph.compareAndWrite(fence,marker.operationId(),id(),List.of());
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(Step.BLOCKED);
        assertThat(runtime.access().revocationReceipt(f.login,f.p,g.id()).status()).isEqualTo("BLOCKED");
        runtime.access().retryStrict(f.login,f.p,id(),com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY);
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(Step.BLOCKED);
    }
    private record Fixture(Partition p,VerifiedLogin login,BootstrapCommand member,RoleVersion role){}
}
