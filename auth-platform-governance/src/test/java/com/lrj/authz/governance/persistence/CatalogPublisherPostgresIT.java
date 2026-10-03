package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogPublisherModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.Source;
import com.lrj.authz.governance.domain.CatalogGuardModels.Enable;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 真PG验证SERVICE身份、不可变关联、幂等和事务失败；只接受本任务专用测试库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogPublisherPostgresIT {
    private GovernanceRuntime runtime;private JdbcTemplate jdbc;private Target target;
    private record Fixture(BootstrapCommand owner,String app,MachineTokenAuthority slot,CatalogPublisher publisher) {
        VerifiedLogin login() {return new VerifiedLogin(owner.issuer(),owner.subject());}
        VerifiedMachine machine() {return new VerifiedMachine(slot.publisherId(),slot.tokenAuthority().issuer(),slot.subject(),slot.tokenAuthority().clientId(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(200));}
    }
    @BeforeAll void open() {
        String file=System.getenv("GOVERNANCE_TEST_CONFIG");var p=new Properties();
        if(file!=null)p=GovernanceConfigurationFile.read(file);
        else {
            // CI沿用既有受限数据库环境变量，本机则使用0600配置；两者仍验证专用测试命名空间。
            p.setProperty("jdbc.url",System.getenv("GOVERNANCE_TEST_DB_URL"));p.setProperty("jdbc.username",System.getenv("GOVERNANCE_TEST_DB_USER"));p.setProperty("jdbc.password",System.getenv("GOVERNANCE_TEST_DB_PASSWORD"));
        }
        assertThat(p.getProperty("jdbc.url")).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        var db=GovernanceDatabase.from(p);runtime=GovernanceRuntime.open(db,true);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
        var existing=jdbc.query("select target_instance_id,environment from auth_governance.catalog_publisher_target",(r,n)->new Target(r.getString(1),r.getString(2)));
        target=runtime.initializePublisherTarget(existing.isEmpty()?new Target(id(),"test"):existing.getFirst(),"publisher-test","专用隔离数据库");
    }
    @AfterAll void close() {if(runtime!=null)runtime.close();}
    private String id() {return UUID.randomUUID().toString();}
    private BootstrapCommand person() {
        var p=new BootstrapCommand(id(),"publisher-test",id(),"tenant-"+id(),id(),"https://owner.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"fixture",id(),id());
        runtime.identity().bootstrapEmployee(p);return p;
    }
    private MachineTokenAuthority slot(String app) {
        String client="publisher-"+id();
        return new MachineTokenAuthority(id(),id(),app,target.targetInstanceId(),target.environment(),"admin/"+client,
                new TokenAuthority("https://issuer.example",URI.create("https://issuer.example/jwks"),client,client,"fixture-secret","probe","fixture-probe",1000,1000,1));
    }
    private Fixture fixture(boolean first) {
        var owner=person();String app="app-"+id();runtime.catalog().register(app,owner.principalId(),"https://app.example","fixture",id());
        if(first)runtime.catalog().publish(new VerifiedLogin(owner.issuer(),owner.subject()),manifest(app,1,"门店"),id());
        runtime.catalog().enableGuard(new VerifiedLogin(owner.issuer(),owner.subject()),new Enable(app,id(),0,true,"隔离目标已退出旧发布节点"));
        var slot=slot(app);return new Fixture(owner,app,slot,runtime.publisher(new CatalogPublisherSettings(target,List.of(slot))));
    }
    private Manifest manifest(String app,long version,String label) {return new Manifest("1",app,version,List.of(new Capability(app+".read","store",Risk.NORMAL)),List.of(new Menu("stores",null,"/stores",List.of(app+".read"),label,0)));}
    private PreviewInput input(Fixture f,long version,String label) {return new PreviewInput(target.targetInstanceId(),target.environment(),manifest(f.app(),version,label),new Source("a".repeat(40),"b".repeat(64)),"目录展示变更");}
    private Delegation delegate(Fixture f) {return f.publisher().create(f.login(),new Create(f.app(),f.slot().publisherId(),id(),Instant.now().plusSeconds(500).toString(),"CI展示发布"));}
    private Publish publish(Report report,String command) {return new Publish(target.targetInstanceId(),target.environment(),command,report.ticket().previewId());}
    @Test void targetIsImmutableAndWrongConfigurationNeverRebinds() {
        assertThat(runtime.initializePublisherTarget(target,"fixture","相同目标核对")).isEqualTo(target);
        assertThatThrownBy(()->runtime.initializePublisherTarget(new Target(id(),"test"),"fixture","错误目标")).hasMessage("BINDING_CONFLICT");
        assertThatThrownBy(()->jdbc.update("update auth_governance.catalog_publisher_target set environment='production'")).isInstanceOf(RuntimeException.class);
        var f=fixture(true);var wrong=new Target(id(),"test");var s=f.slot();
        var bad=new MachineTokenAuthority(s.publisherId(),s.servicePrincipal(),s.applicationId(),wrong.targetInstanceId(),wrong.environment(),s.subject(),s.tokenAuthority());
        assertThatThrownBy(()->runtime.publisher(new CatalogPublisherSettings(wrong,List.of(bad)))).hasMessage("BINDING_CONFLICT");
    }
    @Test void ownerDelegationIsBoundedImmutableAndDoesNotCreateMembershipOrBusinessGrant() {
        var f=fixture(true);long people=count("membership"),grants=count("access_grant");
        var command=new Create(f.app(),f.slot().publisherId(),id(),Instant.now().plusSeconds(500).toString(),"有限委派");
        var d=f.publisher().create(f.login(),command);assertThat(f.publisher().create(f.login(),command)).isEqualTo(d);
        assertThat(d.servicePrincipal()).isEqualTo(f.slot().servicePrincipal());assertThat(count("membership")).isEqualTo(people);assertThat(count("access_grant")).isEqualTo(grants);
        assertThatThrownBy(()->f.publisher().create(f.login(),new Create(f.app(),command.publisherId(),command.commandId(),command.validUntil(),"不同原因"))).hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(()->f.publisher().create(f.login(),new Create(f.app(),command.publisherId(),id(),Instant.now().plusSeconds(501).toString(),"续期"))).hasMessage("BINDING_CONFLICT");
        var other=person();assertThatThrownBy(()->f.publisher().list(new VerifiedLogin(other.issuer(),other.subject()),f.app())).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->jdbc.update("update auth_governance.catalog_publisher_delegation set valid_until=valid_until+interval '1 second' where id=?",d.delegationId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("update auth_governance.catalog_publisher_event set reason='fake' where application_id=?",f.app())).isInstanceOf(RuntimeException.class);
        var invalid=fixture(true);assertThatThrownBy(()->invalid.publisher().create(invalid.login(),new Create(invalid.app(),invalid.slot().publisherId(),id(),Instant.now().plusSeconds(31*86400).toString(),"超过期限"))).hasMessage("INVALID_ARGUMENT");
    }
    @Test void machinePublishesDisplayOnceAndReturnsOriginalReceiptAfterLaterHumanPublication() {
        var f=fixture(true);var d=delegate(f);businessGrant(f);var facts=businessFacts(f.app());assertThat(facts).hasSize(4);var report=f.publisher().preview(f.machine(),input(f,2,"门店目录"));
        assertThat(report.eligibility()).isEqualTo(Eligibility.AUTOMATION_ALLOWED);assertThat(report.ticket()).isNotNull();String cmd=id();var p=publish(report,cmd);
        var receipt=f.publisher().publish(f.machine(),p);assertThat(receipt.release().publishedBy()).isEqualTo(f.slot().servicePrincipal());
        assertThat(receipt.actor().ownerPrincipal()).isEqualTo(f.owner().principalId());assertThat(receipt.actor().delegationId()).isEqualTo(d.delegationId());
        assertThat(f.publisher().publish(f.machine(),p)).isEqualTo(receipt);
        assertThat(f.publisher().receipt(f.machine(),new ReceiptQuery(target.targetInstanceId(),"test",cmd))).isEqualTo(receipt);
        var newer=runtime.catalog().releasePreview(f.login(),new com.lrj.authz.governance.domain.CatalogGuardModels.PreviewInput(manifest(f.app(),3,"人工更新"),null,null,null,null),null);
        runtime.catalog().releasePublish(f.login(),new com.lrj.authz.governance.domain.CatalogGuardModels.PublishCommand(id(),newer.previewId()),null);
        assertThat(f.publisher().publish(f.machine(),p)).isEqualTo(receipt);assertThat(businessFacts(f.app())).isEqualTo(facts);
        assertThat(jdbc.queryForObject("select operator_ref from auth_governance.catalog_audit where application_id=? and command_id=?",String.class,f.app(),cmd)).isEqualTo(f.slot().servicePrincipal());
    }
    @Test void semanticChangesAndFirstDirectoryReturnReportWithoutMachineTicket() {
        var first=fixture(false);delegate(first);assertThat(first.publisher().preview(first.machine(),input(first,1,"初始")).ticket()).isNull();
        var f=fixture(true);delegate(f);var normal=input(f,2,"新目录");
        var changed=new Manifest("1",f.app(),2,normal.manifest().capabilities(),List.of(new Menu("stores",null,"/different",List.of(f.app()+".read"))));
        var report=f.publisher().preview(f.machine(),new PreviewInput(target.targetInstanceId(),"test",changed,normal.source(),normal.reason()));
        assertThat(report.eligibility()).isEqualTo(Eligibility.REQUIRES_OWNER_REVIEW);assertThat(report.ticket()).isNull();assertThat(report.preview().menuChanges()).isNotEmpty();
        assertThat(runtime.catalog().current(f.login(),f.app()).manifestVersion()).isEqualTo(1);
    }
    @Test void humanAndOtherMachineTicketsCannotBeConsumedAndOriginalCommandCannotChangeTicket() {
        var f=fixture(true);delegate(f);var a=f.publisher().preview(f.machine(),input(f,2,"门店甲"));var b=f.publisher().preview(f.machine(),input(f,2,"门店乙"));
        var human=runtime.catalog().releasePreview(f.login(),new com.lrj.authz.governance.domain.CatalogGuardModels.PreviewInput(manifest(f.app(),2,"人工"),null,null,null,null),null);
        assertThatThrownBy(()->f.publisher().publish(f.machine(),new Publish(target.targetInstanceId(),"test",id(),human.previewId()))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.catalog().releasePublish(f.login(),new com.lrj.authz.governance.domain.CatalogGuardModels.PublishCommand(id(),a.ticket().previewId()),null)).hasMessage("ACCESS_DENIED");
        var secondSlot=slot(f.app());var second=runtime.publisher(new CatalogPublisherSettings(target,List.of(secondSlot)));var other=new Fixture(f.owner(),f.app(),secondSlot,second);delegate(other);
        assertThatThrownBy(()->second.publish(other.machine(),publish(a,id()))).hasMessage("ACCESS_DENIED");
        String cmd=id();f.publisher().publish(f.machine(),publish(a,cmd));
        assertThatThrownBy(()->f.publisher().publish(f.machine(),publish(b,cmd))).hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(()->second.publish(other.machine(),publish(a,cmd))).hasMessage("COMMAND_CONFLICT");
        assertThatThrownBy(()->second.receipt(other.machine(),new ReceiptQuery(target.targetInstanceId(),"test",cmd))).hasMessage("NOT_FOUND");
    }
    @Test void disabledDelegationRejectsOldTokenAndOldSuccessfulCommandAndNewSlotRotates() {
        var f=fixture(true);var d=delegate(f);var r=f.publisher().preview(f.machine(),input(f,2,"新目录"));String cmd=id();f.publisher().publish(f.machine(),publish(r,cmd));
        var disable=new Disable(f.app(),d.delegationId(),1,id(),"撤销CI资格");var stopped=f.publisher().disable(f.login(),disable);
        assertThat(stopped.status()).isEqualTo(Status.DISABLED);assertThat(f.publisher().disable(f.login(),disable)).isEqualTo(stopped);
        assertThatThrownBy(()->f.publisher().publish(f.machine(),publish(r,cmd))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->f.publisher().receipt(f.machine(),new ReceiptQuery(target.targetInstanceId(),"test",cmd))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->jdbc.update("update auth_governance.catalog_publisher_delegation set status='ACTIVE',version=1,disabled_at=null where id=?",d.delegationId())).isInstanceOf(RuntimeException.class);
        var next=slot(f.app());var nextPublisher=runtime.publisher(new CatalogPublisherSettings(target,List.of(next)));var rotated=new Fixture(f.owner(),f.app(),next,nextPublisher);delegate(rotated);
        assertThat(nextPublisher.preview(rotated.machine(),input(rotated,3,"轮换后" )).ticket()).isNotNull();
        var reused=new MachineTokenAuthority(id(),f.slot().servicePrincipal(),f.app(),target.targetInstanceId(),"test",f.slot().subject(),f.slot().tokenAuthority());
        var reusedPublisher=runtime.publisher(new CatalogPublisherSettings(target,List.of(reused)));
        assertThatThrownBy(()->reusedPublisher.create(f.login(),new Create(f.app(),reused.publisherId(),id(),Instant.now().plusSeconds(500).toString(),"复用旧客户端"))).hasMessage("BINDING_CONFLICT");
    }
    @Test void wrongTargetMissingSourceInactiveServiceAndExpiredTokenAreRejected() {
        var f=fixture(true);delegate(f);var request=input(f,2,"新门店");
        assertThatThrownBy(()->f.publisher().preview(f.machine(),new PreviewInput(id(),"test",request.manifest(),request.source(),null))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->f.publisher().preview(f.machine(),new PreviewInput(target.targetInstanceId(),"production",request.manifest(),request.source(),null))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->f.publisher().preview(f.machine(),new PreviewInput(target.targetInstanceId(),"test",request.manifest(),null,null))).hasMessage("INVALID_ARGUMENT");
        var m=f.machine();var expired=new VerifiedMachine(m.publisherId(),m.issuer(),m.subject(),m.clientId(),Instant.now().minusSeconds(100),Instant.now().minusSeconds(1));
        assertThatThrownBy(()->f.publisher().preview(expired,request)).hasMessage("INVALID_CREDENTIAL");
        jdbc.update("update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",f.slot().servicePrincipal());
        assertThatThrownBy(()->f.publisher().preview(f.machine(),request)).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->runtime.identity().principalForLogin(f.slot().tokenAuthority().issuer(),f.slot().subject())).hasMessage("MEMBERSHIP_UNAVAILABLE");
    }
    @Test void competingFixedPreviewsHaveExactlyOneWinner() throws Exception {
        var f=fixture(true);delegate(f);var a=f.publisher().preview(f.machine(),input(f,2,"甲"));var b=f.publisher().preview(f.machine(),input(f,2,"乙"));var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<String>>();for(var report:List.of(a,b))futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{f.publisher().publish(f.machine(),publish(report,id()));return "PUBLISHED";}catch(GovernanceException e){return e.getMessage();}}));
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("PUBLISHED","VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_governance.catalog_publisher_release where application_id=?",Integer.class,f.app())).isEqualTo(1);
    }
    @Test void inactiveHumanOwnerInvalidatesServiceAuthorityAndDoesNotChangeTicket() {
        var f=fixture(true);delegate(f);var r=f.publisher().preview(f.machine(),input(f,2,"待发布"));
        jdbc.update("update auth_governance.principal set status='SUSPENDED',version=version+1 where id=?",f.owner().principalId());
        assertThatThrownBy(()->f.publisher().publish(f.machine(),publish(r,id()))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->f.publisher().list(f.login(),f.app())).hasMessage("MEMBERSHIP_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select manifest_version from auth_governance.application_catalog where application_id=?",Long.class,f.app())).isEqualTo(1);
    }
    @Test void migrationDocumentsEveryNewTableAndColumnAndActorForeignKeysCannotLie() {
        assertThat(jdbc.queryForObject("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='auth_governance' and c.relkind='r' and c.relname like 'catalog_publisher_%' and obj_description(c.oid,'pg_class') is null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='auth_governance' and c.relkind='r' and c.relname like 'catalog_publisher_%' and a.attnum>0 and not a.attisdropped and col_description(c.oid,a.attnum) is null",Integer.class)).isZero();
        var f=fixture(true);var d=delegate(f);var r=f.publisher().preview(f.machine(),input(f,2,"真实机器"));var receipt=f.publisher().publish(f.machine(),publish(r,id()));
        assertThatThrownBy(()->jdbc.update("update auth_governance.catalog_publisher_release set owner_principal=? where application_id=?",id(),f.app())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("insert into auth_governance.catalog_publisher_release(application_id,version,delegation_id,target_instance_id,environment,service_principal,owner_principal) values(?,?,?,?,?,?,?)",f.app(),1,d.delegationId(),target.targetInstanceId(),"test",f.slot().servicePrincipal(),f.owner().principalId())).isInstanceOf(RuntimeException.class);
        assertThat(receipt.actor().kind()).isEqualTo("SERVICE");
    }
    @Test void auditAndActorFailuresRollBackAllPublicationWrites() {
        var f=fixture(true);delegate(f);businessGrant(f);var before=businessFacts(f.app());var r=f.publisher().preview(f.machine(),input(f,2,"不能部分提交"));
        String function="publisher_fail_"+id().replace("-","");
        jdbc.execute("create function auth_governance."+function+"() returns trigger language plpgsql as $$ begin if NEW.application_id='"+f.app()+"' then raise exception 'owned failure'; end if; return NEW; end $$");
        try {
            for(String table:List.of("catalog_audit","catalog_publisher_release")) {
                jdbc.execute("create trigger "+function+" before insert on auth_governance."+table+" for each row execute function auth_governance."+function+"()");
                try {
                    assertThatThrownBy(()->f.publisher().publish(f.machine(),publish(r,id()))).isInstanceOf(RuntimeException.class);
                    assertThat(runtime.catalog().current(f.login(),f.app()).manifestVersion()).isEqualTo(1);
                    assertThat(jdbc.queryForObject("select count(*) from auth_governance.application_manifest where application_id=? and version=2",Integer.class,f.app())).isZero();
                    assertThat(jdbc.queryForObject("select count(*) from auth_governance.catalog_release where application_id=? and version=2",Integer.class,f.app())).isZero();
                    assertThat(jdbc.queryForObject("select count(*) from auth_governance.catalog_publisher_release where application_id=?",Integer.class,f.app())).isZero();
                    assertThat(businessFacts(f.app())).isEqualTo(before);
                } finally {jdbc.execute("drop trigger "+function+" on auth_governance."+table);}
            }
        } finally {jdbc.execute("drop function auth_governance."+function+"()");}
        assertThat(f.publisher().publish(f.machine(),publish(r,id())).release().version()).isEqualTo(2);
    }
    @Test void delegationAuditFailureRollsBackServiceIdentityAndDelegation() {
        var f=fixture(true);String function="publisher_event_fail_"+id().replace("-","");
        jdbc.execute("create function auth_governance."+function+"() returns trigger language plpgsql as $$ begin if NEW.application_id='"+f.app()+"' then raise exception 'owned failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger "+function+" before insert on auth_governance.catalog_publisher_event for each row execute function auth_governance."+function+"()");
        try {
            assertThatThrownBy(()->delegate(f)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.principal where id=?",Integer.class,f.slot().servicePrincipal())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.catalog_publisher_delegation where application_id=?",Integer.class,f.app())).isZero();
        } finally {jdbc.execute("drop trigger "+function+" on auth_governance.catalog_publisher_event");jdbc.execute("drop function auth_governance."+function+"()");}
    }
    @Test void tokenExpiryWhileActorInsertWaitsUsesCurrentPgClockAndRollsBack() throws Exception {
        var f=fixture(true);delegate(f);var r=f.publisher().preview(f.machine(),input(f,2,"等待后已过期"));
        Instant expiry=Instant.now().plusSeconds(1);var m=f.machine();var shortToken=new VerifiedMachine(m.publisherId(),m.issuer(),m.subject(),m.clientId(),expiry.minusSeconds(100),expiry);
        try(var gate=gate(f.app());var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->{try{f.publisher().publish(shortToken,publish(r,id()));return "PUBLISHED";}catch(GovernanceException e){return e.getMessage();}});
            gate.awaitWaiting();while(Instant.now().isBefore(expiry.plusMillis(30)))Thread.sleep(10);gate.release();
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIAL");
        }
        assertThat(runtime.catalog().current(f.login(),f.app()).manifestVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_governance.catalog_publisher_release where application_id=?",Integer.class,f.app())).isZero();
    }
    @Test void delegationExpiryWhileActorInsertWaitsRollsBackDespiteTransactionStartValidity() throws Exception {
        var f=fixture(true);Instant expiry=Instant.now().plusSeconds(1);
        f.publisher().create(f.login(),new Create(f.app(),f.slot().publisherId(),id(),expiry.toString(),"短期委派竞态"));
        var r=f.publisher().preview(f.machine(),input(f,2,"委派到期"));
        try(var gate=gate(f.app());var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->{try{f.publisher().publish(f.machine(),publish(r,id()));return "PUBLISHED";}catch(GovernanceException e){return e.getMessage();}});
            gate.awaitWaiting();while(Instant.now().isBefore(expiry.plusMillis(30)))Thread.sleep(10);gate.release();
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(runtime.catalog().current(f.login(),f.app()).manifestVersion()).isEqualTo(1);
    }
    @Test void concurrentDisableWaitsForPublicationThenBlocksEverySubsequentRequest() throws Exception {
        var f=fixture(true);var d=delegate(f);var r=f.publisher().preview(f.machine(),input(f,2,"先完成发布"));
        try(var gate=gate(f.app());var pool=Executors.newFixedThreadPool(2)) {
            var publication=pool.submit(()->f.publisher().publish(f.machine(),publish(r,id())));gate.awaitWaiting();
            var disabling=pool.submit(()->f.publisher().disable(f.login(),new Disable(f.app(),d.delegationId(),1,id(),"竞争撤销")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Integer.class)<2 && System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(disabling.isDone()).isFalse();gate.release();
            assertThat(publication.get(5,TimeUnit.SECONDS).release().version()).isEqualTo(2);assertThat(disabling.get(5,TimeUnit.SECONDS).status()).isEqualTo(Status.DISABLED);
        }
        assertThatThrownBy(()->f.publisher().preview(f.machine(),input(f,3,"已撤销"))).hasMessage("ACCESS_DENIED");
    }
    /** 隔离数据库专用门闩：在真实actor INSERT处阻塞，而非Mock事务或修改不可变业务行。 */
    private Gate gate(String app)throws Exception {return new Gate(app);}
    private final class Gate implements AutoCloseable {
        private final String function="publisher_gate_"+id().replace("-","");
        private final int key=ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE);
        private final java.sql.Connection control;private boolean released;
        Gate(String app)throws Exception {
            control=jdbc.getDataSource().getConnection();try(var s=control.createStatement()){s.execute("select pg_advisory_lock("+key+")");}
            jdbc.execute("create function auth_governance."+function+"() returns trigger language plpgsql as $$ begin if NEW.application_id='"+app+"' then perform pg_advisory_xact_lock("+key+"); end if; return NEW; end $$");
            jdbc.execute("create trigger "+function+" before insert on auth_governance.catalog_publisher_release for each row execute function auth_governance."+function+"()");
        }
        void awaitWaiting()throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(System.nanoTime()<deadline){if(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and objid=? and not granted",Integer.class,key)==1)return;Thread.sleep(10);}
            fail("真实actor未到达PG门闩");
        }
        void release()throws Exception {if(!released){try(var s=control.createStatement()){s.execute("select pg_advisory_unlock("+key+")");}released=true;}}
        @Override public void close()throws Exception {release();control.close();jdbc.execute("drop trigger "+function+" on auth_governance.catalog_publisher_release");jdbc.execute("drop function auth_governance."+function+"()");}
    }
    private long count(String table) {return jdbc.queryForObject("select count(*) from auth_governance."+table,Long.class);}
    private void businessGrant(Fixture f) {
        var p=new com.lrj.authz.governance.domain.AccessModels.Partition(f.owner().tenantId(),f.app(),"test");
        runtime.access().bootstrap(p,new com.lrj.authz.governance.domain.AccessModels.Delegation(f.owner().membershipId(),1,AccessValues.json(List.of(f.app()+".read")),3600),"publisher-fixture",id());
        var role=runtime.access().createRole(f.login(),p,id(),"reader",1,List.of(f.app()+".read"));
        var member=new BootstrapCommand(id(),"publisher-fixture",f.owner().tenantId(),f.owner().tenantCode(),id(),f.owner().issuer(),id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"fixture",id(),id());
        runtime.identity().bootstrapEmployee(member);
        runtime.access().grantScoped(f.login(),p,id(),member.membershipId(),1,role.id(),
                new com.lrj.authz.protocol.ScopeDtos.Rule(1,"store",List.of(new com.lrj.authz.protocol.ScopeDtos.Clause(com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES,List.of("S1"),false))),
                id(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(500));
    }
    private List<String> businessFacts(String app) {
        return jdbc.queryForList("select 'role:'||row_to_json(r)::text value from auth_governance.role_version r where application_id=? union all select 'grant:'||row_to_json(g)::text from auth_governance.access_grant g where application_id=? union all select 'scope:'||row_to_json(s)::text from auth_governance.grant_scope s where application_id=? union all select 'delegation:'||row_to_json(d)::text from auth_governance.access_delegation d where application_id=? order by value",String.class,app,app,app,app);
    }
}
