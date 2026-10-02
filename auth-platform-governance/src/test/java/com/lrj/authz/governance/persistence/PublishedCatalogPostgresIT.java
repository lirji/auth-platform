package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 真实PG验证完整目录、窄委派和跨语句撤权；不以Mock数据库证明一致性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedCatalogPostgresIT {
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    @BeforeAll void open() {
        var config = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG"));
        assertThat(config.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db = GovernanceDatabase.from(config); runtime = GovernanceRuntime.open(db,true);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
    }
    @AfterAll void close() { if(runtime!=null)runtime.close(); }
    private String id() { return UUID.randomUUID().toString(); }
    private BootstrapCommand person(String tenant,String code) {
        var c=new BootstrapCommand(id(),"catalog-test",tenant,code,id(),"https://catalog.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"catalog",id(),id());
        runtime.identity().bootstrapEmployee(c);return c;
    }
    private VerifiedLogin login(BootstrapCommand c){return new VerifiedLogin(c.issuer(),c.subject());}
    private record Fixture(BootstrapCommand owner,BootstrapCommand narrow,BootstrapCommand member,Partition p,List<Capability> caps){}
    private Fixture fixture(boolean menus) {
        String tenant=id(),code="tenant-"+id(),app="catalog-"+id();var owner=person(tenant,code);var narrow=person(tenant,code);var member=person(tenant,code);
        var caps=List.of(new Capability(app+".store.read","store",Risk.NORMAL),new Capability(app+".merchant.read","merchant",Risk.NORMAL),
                new Capability(app+".member.read","commerce_member",Risk.HIGH),new Capability(app+".unknown.read","unbound",Risk.NORMAL));
        runtime.catalog().register(app,owner.principalId(),"https://business.example","test",id());
        runtime.catalog().publish(login(owner),new Manifest("1",app,1,caps,menus?List.of(new Menu("operations",null,null,List.of()),new Menu("stores","operations","/operations/directory",List.of(app+".store.read",app+".merchant.read"))):List.of()),id());
        var p=new Partition(tenant,app,"test");
        runtime.access().bootstrap(p,new Delegation(owner.membershipId(),1,AccessValues.json(caps.stream().map(Capability::code).toList()),3600),"test",id());
        runtime.access().bootstrap(p,new Delegation(narrow.membershipId(),1,AccessValues.json(List.of(app+".store.read")),600),"test",id());
        return new Fixture(owner,narrow,member,p,caps);
    }
    /** 只用代理建立确定的A/C屏障；其读取和状态变更都提交到真实专属PG。 */
    private PortalManagement changedDuringRead(Fixture f,Runnable mutation) throws Exception {
        var base=runtime.portalManagement();var field=PortalManagement.class.getDeclaredField("mapper");field.setAccessible(true);
        var real=(PortalMapper)field.get(base);var first=new AtomicBoolean(true);
        var proxy=(PortalMapper)Proxy.newProxyInstance(PortalMapper.class.getClassLoader(),new Class<?>[]{PortalMapper.class},(ignored,method,args)->{
            var value=method.invoke(real,args);
            if(method.getName().equals("publishedCatalogBasis")&&first.getAndSet(false))mutation.run();
            return value;
        });
        return new PortalManagement(runtime.access(),runtime.identity(),null,null,proxy);
    }
    @Test void completeMetadataHasDifferentGrantableFlagsForNarrowDelegationAndExactScopeBindings() {
        var f=fixture(true);var owner=runtime.portalManagement().publishedCatalog(login(f.owner),f.p);var narrow=runtime.portalManagement().publishedCatalog(login(f.narrow),f.p);
        assertThat(owner.menus()).isEqualTo(narrow.menus());assertThat(owner.capabilities()).hasSize(4);assertThat(narrow.capabilities()).hasSize(4);
        assertThat(narrow.capabilities().stream().filter(c->c.grantable()).map(c->c.code())).containsExactly(f.p.applicationId()+".store.read");
        assertThat(owner.contentHash()).isEqualTo(narrow.contentHash());assertThat(owner.viewHash()).isNotEqualTo(narrow.viewHash());
        assertThat(owner.resourceTypes()).anySatisfy(r->{assertThat(r.code()).isEqualTo("commerce_member");assertThat(r.allowedScopeKinds()).containsExactly(Kind.TENANT_ALL);});
        assertThat(owner.resourceTypes()).anySatisfy(r->{assertThat(r.code()).isEqualTo("merchant");assertThat(r.allowedScopeKinds()).containsExactly(Kind.SPECIFIED_RESOURCES,Kind.TENANT_ALL);});
        assertThat(owner.resourceTypes()).anySatisfy(r->{assertThat(r.code()).isEqualTo("store");assertThat(r.allowedScopeKinds()).containsExactly(Kind.SPECIFIED_RESOURCES,Kind.SPECIFIED_STORES,Kind.TENANT_ALL);});
        assertThat(owner.resourceTypes()).anySatisfy(r->{assertThat(r.code()).isEqualTo("unbound");assertThat(r.scopeSupported()).isFalse();assertThat(r.allowedScopeKinds()).isEmpty();});
        assertThat(runtime.portalManagement().management(login(f.narrow),f.p).capabilities()).hasSize(1);
    }
    @Test void zeroMenusIsRealAndReadLeavesAllWriteTablesUnchanged() {
        var f=fixture(false);var tables=List.of("role_version","access_grant","grant_scope","grant_projection","command_record","audit_event","catalog_audit","capability_state","capability_command");
        var before=tables.stream().map(t->jdbc.queryForObject("SELECT count(*) FROM auth_governance."+t,Long.class)).toList();
        assertThat(runtime.portalManagement().publishedCatalog(login(f.owner),f.p).menus()).isEmpty();
        assertThat(tables.stream().map(t->jdbc.queryForObject("SELECT count(*) FROM auth_governance."+t,Long.class)).toList()).isEqualTo(before);
    }
    @Test void ordinaryOwnerWithoutDelegationAndForeignPartitionAreDenied() {
        var f=fixture(true);var view=runtime.portalManagement();
        assertThatThrownBy(()->view.publishedCatalog(login(f.member),f.p)).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->view.publishedCatalog(login(f.owner),new Partition(f.p.tenantId(),f.p.applicationId(),"prod"))).hasMessage("ACCESS_DENIED");
        var foreign=fixture(true);assertThatThrownBy(()->view.publishedCatalog(login(f.owner),foreign.p)).hasMessage("MEMBERSHIP_UNAVAILABLE");
        jdbc.update("UPDATE auth_governance.access_delegation SET enabled=false WHERE membership_id=?",f.owner.membershipId());
        assertThatThrownBy(()->view.publishedCatalog(login(f.owner),f.p)).hasMessage("ACCESS_DENIED");
    }
    @Test void memberVersionAndCeilingChangesRejectLateMetadata() throws Exception {
        var f=fixture(true);var member=changedDuringRead(f,()->jdbc.update("UPDATE auth_governance.membership SET version=version+1 WHERE id=?",f.owner.membershipId()));
        assertThatThrownBy(()->member.publishedCatalog(login(f.owner),f.p)).hasMessage("VERSION_CONFLICT");
        var g=fixture(true);var ceiling=changedDuringRead(g,()->jdbc.update("UPDATE auth_governance.access_delegation SET max_duration_seconds=500 WHERE membership_id=?",g.owner.membershipId()));
        assertThatThrownBy(()->ceiling.publishedCatalog(login(g.owner),g.p)).hasMessage("VERSION_CONFLICT");
    }
    @Test void concurrentRevocationAndGenerationCannotReturnOldSelection() throws Exception {
        var f=fixture(true);var view=changedDuringRead(f,()->jdbc.update("UPDATE auth_governance.access_delegation SET enabled=false WHERE membership_id=?",f.owner.membershipId()));
        assertThatThrownBy(()->view.publishedCatalog(login(f.owner),f.p)).hasMessage("ACCESS_DENIED");
        var g=fixture(true);var generation=changedDuringRead(g,()->jdbc.update("UPDATE auth_governance.membership SET generation=generation+1,version=version+1 WHERE id=?",g.owner.membershipId()));
        assertThatThrownBy(()->generation.publishedCatalog(login(g.owner),g.p)).hasMessage("ACCESS_DENIED");
    }
    @Test void actualOwnerPublicationAndEmergencyToggleAbaInvalidateView() throws Exception {
        var f=fixture(true);var old=runtime.portalManagement().publishedCatalog(login(f.owner),f.p);
        var published=changedDuringRead(f,()->runtime.catalog().publish(login(f.owner),new Manifest("1",f.p.applicationId(),2,f.caps,List.of()),id()));
        assertThatThrownBy(()->published.publishedCatalog(login(f.owner),f.p)).hasMessage("VERSION_CONFLICT");
        var next=runtime.portalManagement().publishedCatalog(login(f.owner),f.p);assertThat(next.manifestVersion()).isEqualTo(2);assertThat(next.viewHash()).isNotEqualTo(old.viewHash());
        String cap=f.p.applicationId()+".store.read";
        var toggled=changedDuringRead(f,()->{runtime.catalog().changeCapability(login(f.owner),f.p.applicationId(),cap,true,0,"test",id());runtime.catalog().changeCapability(login(f.owner),f.p.applicationId(),cap,false,1,"test",id());});
        assertThatThrownBy(()->toggled.publishedCatalog(login(f.owner),f.p)).hasMessage("VERSION_CONFLICT");
        assertThat(runtime.portalManagement().publishedCatalog(login(f.owner),f.p).viewHash()).isNotEqualTo(next.viewHash());
    }
    @Test void disabledSnapshotAndMixedRoleStorageRemainCompatibleButScopedGrantAndPolicyAreIndependent() {
        var f=fixture(true);String store=f.p.applicationId()+".store.read",merchant=f.p.applicationId()+".merchant.read";
        var mixed=runtime.access().createRole(login(f.owner),f.p,id(),"mixed",1,List.of(store,merchant));
        var rule=new Rule(1,"store",List.of(new Clause(Kind.TENANT_ALL,List.of(),false)));
        assertThatThrownBy(()->runtime.access().grantScoped(login(f.owner),f.p,id(),f.member.membershipId(),1,mixed.id(),rule,"mixed",Instant.now(),Instant.now().plusSeconds(60))).hasMessage("SCOPE_UNSUPPORTED");
        runtime.catalog().changeCapability(login(f.owner),f.p.applicationId(),store,true,0,"test",id());
        var disabled=runtime.access().createRole(login(f.owner),f.p,id(),"disabled",1,List.of(store));
        assertThat(runtime.access().grantScoped(login(f.owner),f.p,id(),f.member.membershipId(),1,disabled.id(),rule,"disabled",Instant.now(),Instant.now().plusSeconds(60)).state()).isEqualTo(GrantState.PENDING);
        var graph=org.mockito.Mockito.mock(com.lrj.authz.protocol.AuthzEngine.class);
        var context=new com.lrj.authz.protocol.GovernanceDtos.AccessContext(f.member.principalId(),f.member.membershipId(),1,1,1,f.p.tenantId(),f.p.applicationId(),f.p.environment(),"test","HUMAN",id());
        assertThat(runtime.authorization(graph).allowed(context,store,"store")).isFalse();org.mockito.Mockito.verifyNoInteractions(graph);
        runtime.access().enableStrict(login(f.owner),f.p,id());
        assertThatThrownBy(()->runtime.requests().registerPolicy(login(f.owner),f.p,id(),disabled.id(),rule,60,f.owner.membershipId(),1,1)).hasMessage("ACCESS_DENIED");
        assertThat(runtime.portalManagement().publishedCatalog(login(f.owner),f.p).capabilities()).anySatisfy(c->{assertThat(c.code()).isEqualTo(store);assertThat(c.disabled()).isTrue();assertThat(c.grantable()).isFalse();});
    }
}
