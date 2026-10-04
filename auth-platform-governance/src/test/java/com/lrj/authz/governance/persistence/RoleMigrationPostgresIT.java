package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.protocol.RoleMigrationDtos.*;
import com.lrj.authz.protocol.RoleMigrationDtos.Preview;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.time.Instant;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 真实PG验证只读资格、范围和并发；ACTIVE夹具只代表SQL记录，不证明真实图ALLOW。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMigrationPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    @BeforeAll void open() {
        Properties p=new Properties();String file=System.getenv("GOVERNANCE_TEST_CONFIG");
        if(file!=null)p=GovernanceConfigurationFile.read(file);
        else {p.setProperty("jdbc.url",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_URL")));p.setProperty("jdbc.username",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_USER")));p.setProperty("jdbc.password",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_PASSWORD")));}
        assertThat(p.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db=GovernanceDatabase.from(p);runtime=GovernanceRuntime.open(db,true);jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
    }
    @AfterAll void close(){if(runtime!=null)runtime.close();}
    private String id(){return UUID.randomUUID().toString();}
    private VerifiedLogin login(BootstrapCommand c){return new VerifiedLogin(c.issuer(),c.subject());}
    private BootstrapCommand person(String tenant,String code){var c=new BootstrapCommand(id(),"mg12",tenant,code,id(),"https://mg12.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"mg12",id(),id());runtime.identity().bootstrapEmployee(c);return c;}
    private record F(BootstrapCommand owner,BootstrapCommand member,BootstrapCommand narrow,Partition p,RoleVersion old,RoleVersion next,List<String> caps){}
    private F fixture(){return fixture(true);}
    private F fixture(boolean strict){
        String tenant=id(),code="mg12-"+id(),app="mg12-"+id();var owner=person(tenant,code);var member=person(tenant,code);var narrow=person(tenant,code);
        var caps=List.of(app+".read",app+".write",app+".merchant");
        runtime.catalog().register(app,owner.principalId(),"https://app.example","mg12",id());
        runtime.catalog().publish(login(owner),new Manifest("1",app,1,List.of(new Capability(caps.get(0),"store",Risk.NORMAL),new Capability(caps.get(1),"store",Risk.HIGH),new Capability(caps.get(2),"merchant",Risk.NORMAL)),List.of()),id());
        var p=new Partition(tenant,app,"test");runtime.access().bootstrap(p,new Delegation(owner.membershipId(),1,AccessValues.json(caps),3600),"mg12",id());runtime.access().bootstrap(p,new Delegation(narrow.membershipId(),1,AccessValues.json(List.of(caps.get(0))),100),"mg12",id());
        var old=runtime.access().createRole(login(owner),p,id(),"reader",1,caps.subList(0,2));var next=runtime.access().createRole(login(owner),p,id(),"reader",2,List.of(caps.get(0)));if(strict)runtime.access().enableStrict(login(owner),p,id());
        return new F(owner,member,narrow,p,old,next,caps);
    }
    private Grant grant(F f,boolean scoped){
        var from=Instant.now().minusSeconds(2);var to=Instant.now().plusSeconds(600);
        var g=scoped?runtime.access().grantScoped(login(f.owner),f.p,id(),f.member.membershipId(),1,f.old.id(),new Rule(1,"store",List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S1"),false))),id(),from,to):runtime.access().grant(login(f.owner),f.p,id(),f.member.membershipId(),1,f.old.id(),"TENANT_ALL",id(),from,to);
        jdbc.update("UPDATE auth_governance.access_grant SET state='ACTIVE',zed_token='mg12-sql-fixture' WHERE id=?",g.id());return g;
    }
    private PreviewRequest input(F f,RoleVersion next,String...ids){return new PreviewRequest(f.p.tenantId(),f.p.applicationId(),f.p.environment(),f.old.id(),next.id(),List.of(ids));}
    private Preview report(F f,Grant g){return runtime.roleMigrationPreview().preview(login(f.owner),input(f,f.next,g.id()));}
    private String facts(F f){return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(g) ORDER BY g.id)::text,'[]') FROM auth_governance.access_grant g WHERE tenant_id=?",String.class,f.p.tenantId());}

    @Test void shrinkingDirectHasExactScopeSourceDeadlineAndNoWriteSideEffects(){
        var f=fixture();var g=grant(f,true);String before=facts(f);long audits=jdbc.queryForObject("SELECT count(*) FROM auth_governance.audit_event",Long.class);long commands=jdbc.queryForObject("SELECT count(*) FROM auth_governance.command_record",Long.class);
        var r=report(f,g);assertThat(r.eligibleCount()).isEqualTo(1);assertThat(r.removed()).containsExactly(f.caps.get(1));assertThat(r.added()).isEmpty();assertThat(r.projectionStatus()).isEqualTo("UNKNOWN");assertThat(r.continuity()).isEqualTo("REVOKE_CONFIRM_THEN_GRANT");
        var item=r.items().getFirst();assertThat(item.scopeRule().clauses().getFirst().values()).containsExactly("S1");assertThat(item.grant().validTo()).isEqualTo(g.validTo().toString());assertThat(item.proposedSourceId()).isEqualTo("role-migration:"+g.id()+":"+f.next.id());assertThat(item.remainingSeconds()).isBetween(1L,600L);assertThat(facts(f)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_governance.audit_event",Long.class)).isEqualTo(audits);assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_governance.command_record",Long.class)).isEqualTo(commands);
    }
    @Test void explicitCollectionRejectsDuplicateTooManyAndForeignGrantWithoutPartialResult(){
        var f=fixture();var g=grant(f,false);var view=runtime.roleMigrationPreview();
        assertThatThrownBy(()->view.preview(login(f.owner),input(f,f.next,g.id(),g.id()))).hasMessage("INVALID_ARGUMENT");
        var tooMany=new PreviewRequest(f.p.tenantId(),f.p.applicationId(),f.p.environment(),f.old.id(),f.next.id(),java.util.stream.IntStream.range(0,51).mapToObj(i->id()).toList());assertThatThrownBy(()->view.preview(login(f.owner),tooMany)).hasMessage("INVALID_ARGUMENT");
        var foreign=fixture();var other=grant(foreign,false);assertThatThrownBy(()->view.preview(login(f.owner),input(f,f.next,g.id(),other.id()))).hasMessage("ACCESS_DENIED");
    }
    @Test void sameCodeHigherVersionAndCurrentManagerAreRequired(){
        var f=fixture();var g=grant(f,false);var view=runtime.roleMigrationPreview();
        assertThatThrownBy(()->view.preview(login(f.owner),input(f,f.old,g.id()))).hasMessage("INVALID_ARGUMENT");
        var different=runtime.access().createRole(login(f.owner),f.p,id(),"other",3,List.of(f.caps.get(0)));assertThatThrownBy(()->view.preview(login(f.owner),input(f,different,g.id()))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(()->view.preview(login(f.member),input(f,f.next,g.id()))).hasMessage("ACCESS_DENIED");
        var wrong=new PreviewRequest(f.p.tenantId(),f.p.applicationId(),"production",f.old.id(),f.next.id(),List.of(g.id()));assertThatThrownBy(()->view.preview(login(f.owner),wrong)).hasMessage("ACCESS_DENIED");
    }
    @Test void narrowCeilingAndRemainingDurationAreExcluded(){
        var f=fixture();var g=grant(f,false);var r=runtime.roleMigrationPreview().preview(login(f.narrow),input(f,f.next,g.id()));assertThat(r.items().getFirst().reasons()).contains(Exclusion.MANAGEMENT_CEILING,Exclusion.DURATION_EXCEEDS_CEILING);assertThat(r.eligibleCount()).isZero();
    }
    @Test void mixedNewResourceAndAddedCapabilityCannotWidenOriginalScope(){
        var f=fixture();var g=grant(f,true);var mixed=runtime.access().createRole(login(f.owner),f.p,id(),"reader",3,List.of(f.caps.get(0),f.caps.get(2)));var r=runtime.roleMigrationPreview().preview(login(f.owner),input(f,mixed,g.id()));assertThat(r.items().getFirst().reasons()).contains(Exclusion.SCOPE_UNSUPPORTED,Exclusion.REQUIRES_SEPARATE_AUTHORIZATION);assertThat(r.eligibleCount()).isZero();
    }
    @Test void expiredRevokedPendingAndOldGenerationAreNotEligible(){
        var f=fixture();var expired=grant(f,false);jdbc.update("UPDATE auth_governance.access_grant SET valid_from=clock_timestamp()-interval '10 minutes',valid_to=clock_timestamp()-interval '1 second' WHERE id=?",expired.id());assertThat(report(f,expired).items().getFirst().reasons()).contains(Exclusion.GRANT_EXPIRED);
        var revoked=grant(f,false);runtime.access().revoke(login(f.owner),f.p,id(),revoked.id(),1);assertThat(report(f,revoked).items().getFirst().reasons()).contains(Exclusion.GRANT_NOT_ACTIVE);
        var pending=grant(f,false);jdbc.update("UPDATE auth_governance.access_grant SET state='PENDING' WHERE id=?",pending.id());assertThat(report(f,pending).items().getFirst().reasons()).contains(Exclusion.GRANT_NOT_ACTIVE);
        var stale=grant(f,false);jdbc.update("UPDATE auth_governance.membership SET generation=generation+1,version=version+1 WHERE id=?",f.member.membershipId());assertThat(report(f,stale).items().getFirst().reasons()).contains(Exclusion.MEMBERSHIP_UNAVAILABLE);
    }
    @Test void approvalSourceIsReportedAndNeverConvertedToDirect(){
        var f=fixture();var g=grant(f,false);jdbc.update("UPDATE auth_governance.access_grant SET source_type='OA_REQUEST' WHERE id=?",g.id());String before=facts(f);var r=report(f,g);assertThat(r.items().getFirst().grant().sourceType()).isEqualTo("OA_REQUEST");assertThat(r.items().getFirst().reasons()).contains(Exclusion.OA_APPROVAL_REQUIRED);assertThat(facts(f)).isEqualTo(before);
    }
    @Test void groupSourceKeepsItsRealGroupAndFixedScope(){
        var f=fixture();var authority=new DirectoryAuthority(id(),"mg12-"+id(),"test","1",f.p.tenantId(),f.owner.issuer());runtime.directory().register(authority,"mg12");
        jdbc.update("UPDATE auth_governance.directory_source SET business_zone='UTC' WHERE id=?",authority.id());
        String group=id();jdbc.update("INSERT INTO auth_governance.directory_group(id,source_id,tenant_id,org_ref,active) VALUES(?,?,?,?,true)",group,authority.id(),f.p.tenantId(),id());
        var rule=new Rule(1,"store",List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S1"),false)));
        var g=runtime.access().grantGroup(login(f.owner),f.p,id(),group,f.old.id(),rule,id(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(300));String before=facts(f);
        var item=report(f,g).items().getFirst();assertThat(item.grant().memberId()).isNull();assertThat(item.grant().groupId()).isEqualTo(group);assertThat(item.scopeRule()).isEqualTo(rule);assertThat(item.reasons()).doesNotContain(Exclusion.UNSUPPORTED_SOURCE).contains(Exclusion.GRANT_NOT_ACTIVE);assertThat(item.eligible()).isFalse();assertThat(facts(f)).isEqualTo(before);
    }
    @Test void capabilityStopLegacyPartitionFutureAndWrongOldRoleAreExcluded(){
        var f=fixture(false);var g=grant(f,false);assertThat(report(f,g).items().getFirst().reasons()).contains(Exclusion.STRICT_PARTITION_REQUIRED);
        runtime.access().enableStrict(login(f.owner),f.p,id());jdbc.update("UPDATE auth_governance.access_grant SET valid_from=clock_timestamp()+interval '30 seconds' WHERE id=?",g.id());assertThat(report(f,g).items().getFirst().reasons()).contains(Exclusion.GRANT_NOT_CURRENT);
        runtime.catalog().changeCapability(login(f.owner),f.p.applicationId(),f.caps.get(0),true,0,"隔离停用验收",id());assertThat(report(f,g).items().getFirst().reasons()).contains(Exclusion.CAPABILITY_UNAVAILABLE);
        var other=fixture();var nextSource=runtime.access().grant(login(other.owner),other.p,id(),other.member.membershipId(),1,other.next.id(),"TENANT_ALL",id(),Instant.now(),Instant.now().plusSeconds(200));assertThat(report(other,nextSource).items().getFirst().reasons()).contains(Exclusion.GRANT_ROLE_MISMATCH);
    }
    @Test void damagedScopeHashNeverFallsBackToAllResources() throws Exception {
        var f=fixture();var roleField=GovernanceRuntime.class.getDeclaredField("accessMapper");roleField.setAccessible(true);var roles=(AccessMapper)roleField.get(runtime);var txField=GovernanceRuntime.class.getDeclaredField("transaction");txField.setAccessible(true);var tx=(TransactionTemplate)txField.get(runtime);
        var g=new Grant(id(),f.p.tenantId(),f.p.applicationId(),f.p.environment(),f.member.membershipId(),1,f.old.id(),"SCOPED","DIRECT",id(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(200),GrantState.ACTIVE,1,null,null);
        // 仅在新隔离夹具中插入损坏历史记录，不放宽不可变范围触发器。
        tx.execute(status->{assertThat(roles.insertGrant(g,f.owner.membershipId())).isEqualTo(1);assertThat(roles.insertScope(g,"store",ScopeRules.encode(new Rule(1,"store",List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S1"),false)))),"a".repeat(64))).isEqualTo(1);return null;});
        assertThatThrownBy(()->report(f,g)).hasMessage("DEPENDENCY_UNAVAILABLE");
    }
    @Test void selfMigrationIsDeniedEvenWhenAnotherManagerOriginallyGrantedIt(){
        var f=fixture();var manager=person(f.p.tenantId(),f.owner.tenantCode());runtime.access().bootstrap(f.p,new Delegation(manager.membershipId(),1,AccessValues.json(f.caps),3600),"mg12",id());var g=runtime.access().grant(login(manager),f.p,id(),f.owner.membershipId(),1,f.old.id(),"TENANT_ALL",id(),Instant.now(),Instant.now().plusSeconds(120));jdbc.update("UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?",g.id());assertThat(report(f,g).items().getFirst().reasons()).contains(Exclusion.SELF_GRANT_DENIED);
    }
    @Test void realReferencePaginationHasTwentyRowsAndExactNextCursor(){
        var f=fixture();for(int i=0;i<23;i++)grant(f,false);var view=runtime.roleMigrationPreview();var first=view.candidates(login(f.owner),f.p,f.old.id(),null);assertThat(first.items()).hasSize(20);assertThat(first.nextCursor()).isEqualTo(first.items().getLast().id());var next=view.candidates(login(f.owner),f.p,f.old.id(),first.nextCursor());assertThat(next.items()).hasSize(3);assertThat(next.nextCursor()).isNull();assertThat(first.items().stream().map(g->g.id()).toList()).doesNotContainAnyElementsOf(next.items().stream().map(g->g.id()).toList());
    }
    @Test void concurrentGrantMutationCannotReturnStaleQualification() throws Exception {
        var f=fixture();var g=grant(f,true);var first=new AtomicBoolean(true);var field=GovernanceRuntime.class.getDeclaredField("roleMigrationMapper");field.setAccessible(true);var real=(RoleMigrationMapper)field.get(runtime);
        var proxy=(RoleMigrationMapper)Proxy.newProxyInstance(RoleMigrationMapper.class.getClassLoader(),new Class<?>[]{RoleMigrationMapper.class},(ignored,method,args)->{var result=method.invoke(real,args);if(method.getName().equals("selected")&&first.getAndSet(false))jdbc.update("UPDATE auth_governance.access_grant SET state='REVOKED',version=version+1 WHERE id=?",g.id());return result;});
        var roles=GovernanceRuntime.class.getDeclaredField("accessMapper");roles.setAccessible(true);var tx=GovernanceRuntime.class.getDeclaredField("transaction");tx.setAccessible(true);var view=new RoleMigrationPreview(runtime.access(),runtime.portalManagement(),(AccessMapper)roles.get(runtime),proxy,(TransactionTemplate)tx.get(runtime),runtime.requests());assertThatThrownBy(()->view.preview(login(f.owner),input(f,f.next,g.id()))).hasMessage("VERSION_CONFLICT");
    }
}
