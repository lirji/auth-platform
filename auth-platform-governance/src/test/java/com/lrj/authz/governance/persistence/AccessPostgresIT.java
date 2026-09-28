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

/** 授权的真实事务、固定版本、来源隔离与管理越权回归。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessPostgresIT {
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
        var role=runtime.access().createRole(login(owner),p,id(),"reader",1,List.of(app+".read"));return new Fixture(owner,member,p,role);
    }
    private Grant grant(Fixture f,String command,String source){return runtime.access().grant(login(f.owner),f.p,command,f.member.membershipId(),1,f.role.id(),"TENANT_ALL",source,Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),Instant.now().plusSeconds(1200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));}
    @Test void fixedVersionAndSourcePathsStayIndependent(){
        var f=fixture();var first=grant(f,id(),"one");var second=grant(f,id(),"two");
        var v2=runtime.access().createRole(login(f.owner),f.p,id(),"reader",2,List.of(f.p.applicationId()+".read",f.p.applicationId()+".refund"));
        assertThat(v2.id()).isNotEqualTo(first.roleId());assertThat(first.roleId()).isEqualTo(f.role.id());
        assertThat(first.state()).isEqualTo(GrantState.PENDING);assertThat(first.zedToken()).isNull();
        var revoked=runtime.access().revoke(login(f.owner),f.p,id(),first.id(),1);
        assertThat(revoked.state()).isEqualTo(GrantState.REVOKED);
        assertThat(runtime.access().state(login(f.owner),f.p,null,null).grants()).anyMatch(g->g.id().equals(second.id())&&g.state()==GrantState.PENDING);
        assertThatThrownBy(()->jdbc.update("update auth_governance.role_version set capabilities_json='[]' where id=?",f.role.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from auth_governance.grant_projection where grant_id=?",Integer.class,first.id())).isEqualTo(2);
    }
    @Test void managementDoesNotFollowBusinessAndSelfGrantIsDenied(){
        var f=fixture();
        assertThatThrownBy(()->runtime.access().createRole(login(f.member),f.p,id(),"admin",1,List.of(f.p.applicationId()+".refund"))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),f.owner.membershipId(),1,f.role.id(),"TENANT_ALL","self",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),f.member.membershipId(),1,f.role.id(),"STORE","scope",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),f.member.membershipId(),1,f.role.id(),"TENANT_ALL","long",Instant.now(),Instant.now().plusSeconds(3601))).hasMessage("ACCESS_DENIED");
    }
    @Test void partitionAndGenerationCannotBeForged(){
        var f=fixture();var other=fixture();
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),other.member.membershipId(),1,f.role.id(),"TENANT_ALL","foreign",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),f.member.membershipId(),2,f.role.id(),"TENANT_ALL","old",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThatThrownBy(()->runtime.access().createRole(login(f.owner),new Partition(f.p.tenantId(),f.p.applicationId(),"prod"),id(),"reader",1,List.of(f.p.applicationId()+".read"))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,id(),f.member.membershipId(),1,other.role.id(),"TENANT_ALL","wrongrole",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("ACCESS_DENIED");
    }
    @Test void ceilingAndChangedRoleVersionCannotEscalate(){
        var f=fixture();String cap=f.p.applicationId()+".read";
        runtime.access().bootstrap(f.p,new Delegation(f.member.membershipId(),1,AccessValues.json(List.of(cap)),60),"test",id());
        assertThatThrownBy(()->runtime.access().createRole(login(f.member),f.p,id(),"higher",1,List.of(f.p.applicationId()+".refund"))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.access().createRole(login(f.owner),f.p,id(),"reader",1,List.of(f.p.applicationId()+".refund"))).hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(()->runtime.access().bootstrap(f.p,new Delegation(f.member.membershipId(),1,AccessValues.json(List.of(cap)),100),"test",id())).hasMessage("BINDING_CONFLICT");
    }
    @Test void concurrentSameCommandHasOneGrantIntentAndAudit() throws Exception {
        var f=fixture();String command=id();Instant from=Instant.now(),to=from.plusSeconds(60);
        try(var pool=Executors.newFixedThreadPool(3)){
            Callable<Grant> call=()->runtime.access().grant(login(f.owner),f.p,command,f.member.membershipId(),1,f.role.id(),"TENANT_ALL","same",from,to);
            var results=pool.invokeAll(List.of(call,call,call));String grant=results.get(0).get().id();for(var result:results)assertThat(result.get().id()).isEqualTo(grant);
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.grant_projection where grant_id=?",Integer.class,grant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.audit_event where target_id=? and operation='CREATE_GRANT'",Integer.class,grant)).isEqualTo(1);
            assertThatThrownBy(()->runtime.access().grant(login(f.owner),f.p,command,f.member.membershipId(),1,f.role.id(),"TENANT_ALL","changed",from,to)).hasMessage("COMMAND_CONFLICT");
        }
    }
    @Test void failedAuditRollsBackGrantAndIntent(){
        var f=fixture();String constraint="test_access_"+id().replace("-","");
        jdbc.execute("alter table auth_governance.audit_event add constraint "+constraint+" check (tenant_id <> '"+f.p.tenantId()+"' or operation <> 'CREATE_GRANT')");
        try{assertThatThrownBy(()->grant(f,id(),"fail-audit")).isInstanceOf(RuntimeException.class);assertThat(runtime.access().state(login(f.owner),f.p,null,null).grants()).isEmpty();}
        finally{jdbc.execute("alter table auth_governance.audit_event drop constraint "+constraint);}
    }
    @Test void disabledManagerCannotReadOrWrite(){
        var f=fixture();jdbc.update("update auth_governance.membership set status='SUSPENDED',version=version+1 where id=?",f.owner.membershipId());
        assertThatThrownBy(()->runtime.access().state(login(f.owner),f.p,null,null)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThatThrownBy(()->grant(f,id(),"suspended")).hasMessage("MEMBERSHIP_UNAVAILABLE");
    }
    private record Fixture(BootstrapCommand owner,BootstrapCommand member,Partition p,RoleVersion role){}
}
