package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** OA来源Grant在真实PostgreSQL与SpiceDB中的固定范围和双栅栏验证。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestProjectionIT {
    private GovernanceRuntime runtime;private JdbcTemplate jdbc;
    @BeforeAll void open(){
        Properties p=new Properties();String file=System.getenv("GOVERNANCE_TEST_CONFIG");
        if(file!=null)p=GovernanceConfigurationFile.read(file);else{p.setProperty("jdbc.url",System.getenv("GOVERNANCE_TEST_DB_URL"));p.setProperty("jdbc.username",System.getenv("GOVERNANCE_TEST_DB_USER"));p.setProperty("jdbc.password",System.getenv("GOVERNANCE_TEST_DB_PASSWORD"));}
        assertThat(p.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db=GovernanceDatabase.from(p);runtime=GovernanceRuntime.open(db,true);jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
    }
    @AfterAll void close(){if(runtime!=null)runtime.close();}
    private String id(){return UUID.randomUUID().toString();}
    private BootstrapCommand person(String tenant,String code){var c=new BootstrapCommand(id(),"access-test",tenant,code,id(),"https://access.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"fixture",id(),id());runtime.identity().bootstrapEmployee(c);return c;}
    private VerifiedLogin login(BootstrapCommand c){return new VerifiedLogin(c.issuer(),c.subject());}
    private Fixture fixture(){
        String tenant=id(),code="tenant-"+id(),app="app-"+id();var owner=person(tenant,code);var member=person(tenant,code);
        runtime.catalog().register(app,owner.principalId(),"https://example.test","test",id());
        runtime.catalog().publish(login(owner),new Manifest("1",app,1,List.of(new Capability(app+".read","store",Risk.NORMAL),new Capability(app+".refund","store",Risk.HIGH)),List.of()),id());
        Partition p=new Partition(tenant,app,"test");runtime.access().bootstrap(p,new Delegation(owner.membershipId(),1,AccessValues.json(List.of(app+".read",app+".refund")),3600),"test",id());
        var role=runtime.access().createRole(login(owner),p,id(),"reader",1,List.of(app+".read"));runtime.access().enableStrict(login(owner),p,id());return new Fixture(owner,member,p,role);
    }

    private com.lrj.authz.protocol.ScopeDtos.Rule scope(){
        return new com.lrj.authz.protocol.ScopeDtos.Rule(1,"store",List.of(new com.lrj.authz.protocol.ScopeDtos.Clause(
                com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S001"),false)));
    }
    private com.lrj.authz.governance.domain.RequestModels.Policy policy(Fixture f){
        return runtime.requests().registerPolicy(login(f.owner),f.p,id(),f.role.id(),scope(),600,f.owner.membershipId(),1,1);
    }
    private com.lrj.authz.governance.domain.RequestModels.Request submit(Fixture f,String policy,String command,Instant from,Instant to,String reason){
        return runtime.requests().submit(login(f.member),f.p,command,policy,from,to,reason);
    }
    private String approve(Fixture f,com.lrj.authz.governance.domain.RequestModels.Policy policy,com.lrj.authz.governance.domain.RequestModels.Request r) throws Exception {
        ApprovalGateway gateway=new ApprovalGateway(){
            public Optional<com.lrj.authz.protocol.ApprovalDtos.Instance> find(com.lrj.authz.protocol.ApprovalDtos.Lookup q){return Optional.empty();}
            public com.lrj.authz.protocol.ApprovalDtos.Instance start(com.lrj.authz.protocol.ApprovalDtos.Start command){return new com.lrj.authz.protocol.ApprovalDtos.Instance(command.requestId(),1,command.snapshotHash(),"123");}
        };
        runtime.approvalStarts(gateway).step(f.p);
        var event=new com.lrj.authz.protocol.ApprovalDtos.Decision(id(),"ACCESS_REQUEST_DECIDED",1,"oa-platform",f.p.tenantId(),f.p.applicationId(),f.p.environment(),r.id(),1,r.snapshotHash(),"123",policy.id(),1,1,"APPROVED",f.owner.membershipId(),1,Instant.now().toString(),id());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        byte[] body=json.writeValueAsBytes(event);String key="k".repeat(43);
        String signature=com.lrj.authz.protocol.ApprovalSignature.sign(key,"oa-platform","test",ApprovalInbox.PATH,body,Instant.now());
        runtime.approvalInbox().receive(f.p,key,signature,body);return event.eventId();
    }
    @Test void approvedSnapshotRequiresActualProjectionReceiptAndPreservesScope() throws Exception {
        var f=fixture();var policy=policy(f);var r=submit(f,policy.id(),id(),Instant.now(),Instant.now().plusSeconds(300),"real graph");
        approve(f,policy,r);runtime.approvalDecisions().step(f.p);
        var config=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        assertThat(config.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        var graph=new com.lrj.authz.core.SpiceDbProjectionGraph(config.getProperty("graph.http"),config.getProperty("graph.key"),java.time.Duration.ofSeconds(3));
        assertThat(runtime.requests().execution(login(f.member),f.p,r.id()).displayState()).isEqualTo("PENDING_APPLY");
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(com.lrj.authz.governance.domain.ProjectionModels.Step.READY);
        assertThat(runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.DIRECTORY,id())).isEqualTo(com.lrj.authz.governance.domain.ProjectionModels.Step.READY);
        var execution=runtime.requests().execution(login(f.member),f.p,r.id());
        assertThat(execution.displayState()).isEqualTo("ACTIVE");assertThat(execution.operationId()).isNotBlank();
        var c=runtime.identity().contextForLogin(f.member.issuer(),f.member.subject(),f.p.tenantId(),null);
        var context=new com.lrj.authz.protocol.GovernanceDtos.AccessContext(c.principalId(),c.membershipId(),c.membershipGeneration(),c.membershipVersion(),c.principalVersion(),c.tenantId(),f.p.applicationId(),f.p.environment(),"test","HUMAN",id());
        var one=new com.lrj.authz.protocol.ScopeDtos.Facts(f.p.tenantId(),"store","S001",1,null,null,List.of(),"S001",null);
        var other=new com.lrj.authz.protocol.ScopeDtos.Facts(f.p.tenantId(),"store","S002",1,null,null,List.of(),"S002",null);
        assertThat(runtime.reliableAuthorization(graph).allowed(context,f.p.applicationId()+".read",one)).isTrue();
        assertThat(runtime.reliableAuthorization(graph).allowed(context,f.p.applicationId()+".read",other)).isFalse();
        runtime.access().createRole(login(f.owner),f.p,id(),"reader",2,List.of(f.p.applicationId()+".read",f.p.applicationId()+".refund"));
        assertThat(runtime.reliableAuthorization(graph).allowed(context,f.p.applicationId()+".refund",one)).isFalse();
        runtime.requests().cancel(login(f.member),f.p,id(),r.id(),r.stateVersion());
        assertThat(runtime.requests().execution(login(f.member),f.p,r.id()).displayState()).isEqualTo("REVOKING");
        assertThatThrownBy(()->runtime.reliableAuthorization(graph).evaluate(context,f.p.applicationId()+".read","store")).hasMessage("AUTHZ_STATE_NOT_READY");
        runtime.reliableProjector(graph).step(f.p,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id());
        assertThat(runtime.requests().execution(login(f.member),f.p,r.id()).displayState()).isEqualTo("REVOKED");
        assertThat(runtime.reliableAuthorization(graph).allowed(context,f.p.applicationId()+".read",one)).isFalse();
    }
    private record Fixture(BootstrapCommand owner,BootstrapCommand member,Partition p,RoleVersion role){}
}
