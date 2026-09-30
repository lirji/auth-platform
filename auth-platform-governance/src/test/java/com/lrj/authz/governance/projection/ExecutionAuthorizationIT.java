package com.lrj.authz.governance.projection;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实PG和SpiceDB验证后台引用跨进程重读、撤权、重授及代际，不用Mock证明事务或权限。 */
class ExecutionAuthorizationIT {
    private static String id(){return UUID.randomUUID().toString();}
    @Test void durableExecutionCannotGrowOrSurviveRevocationAndIdentityChanges() throws Exception {
        var db=GovernanceDatabase.from(GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        assertThat(db.jdbcUrl()).contains("/auth_gov_p1_test_");
        var props=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph=new SpiceDbProjectionGraph(props.getProperty("graph.http"),props.getProperty("graph.key"),Duration.ofSeconds(3));
        try(var runtime=GovernanceRuntime.open(db,true);var second=GovernanceRuntime.open(db,false)) {
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
            String tenant=id(),code="p6-"+id(),app="commerce-"+id(),capability=app+".catalog.operate";
            var owner=person(runtime,tenant,code);var member=person(runtime,tenant,code);
            var login=new VerifiedLogin(owner.issuer(),owner.subject());
            runtime.catalog().register(app,owner.principalId(),"https://commerce.example","test",id());
            runtime.catalog().publish(login,new Manifest("1",app,1,List.of(new Capability(capability,"store",Risk.HIGH)),List.of()),id());
            var partition=new Partition(tenant,app,"test");
            runtime.access().bootstrap(partition,new Delegation(owner.membershipId(),1,AccessValues.json(List.of(capability)),3600),"test",id());
            var role=runtime.access().createRole(login,partition,id(),"catalog-operator",1,List.of(capability));
            runtime.access().enableStrict(login,partition,id());
            var rule=new Rule(1,"store",List.of(new Clause(ScopeDtos.Kind.SPECIFIED_STORES,List.of("S001"),false)));
            var grant=runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),rule,id(),Instant.now(),Instant.now().plusSeconds(600));
            project(runtime,graph,partition);
            var c=runtime.identity().contextForLogin(member.issuer(),member.subject(),tenant,1L);
            var context=new AccessContext(c.principalId(),c.membershipId(),c.membershipGeneration(),c.membershipVersion(),c.principalVersion(),tenant,app,"test","commerce-p6","HUMAN",id());
            var request=new CentralAccessDtos.Check(tenant,1L,id(),capability,"store");
            var issue=new Issue(request,Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
            var executions=runtime.executions(graph);var reference=executions.issue(context,issue);
            assertThat(executions.issue(context,issue).executionId()).isEqualTo(reference.executionId());
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.execution_reference where member=?",Integer.class,member.membershipId())).isEqualTo(1);
            assertThatThrownBy(()->executions.issue(context,new Issue(request,Instant.now().plusSeconds(200).toString()))).hasMessage("INVALID_ARGUMENT");
            var caller=mock(CallerService.class);when(caller.callerServiceId()).thenReturn("commerce-p6");when(caller.applicationId()).thenReturn(app);when(caller.environment()).thenReturn("test");
            var allowed=check(reference,tenant,"S001");
            assertThat(second.executions(graph).check(caller,allowed).decision()).isEqualTo("ALLOW");
            assertThat(executions.check(caller,check(reference,tenant,"S002")).decision()).isEqualTo("DENY");
            assertThatThrownBy(()->executions.check(caller,check(reference,id(),"S001"))).hasMessage("ACCESS_DENIED");
            when(caller.environment()).thenReturn("other");assertThatThrownBy(()->executions.check(caller,allowed)).hasMessage("ACCESS_DENIED");when(caller.environment()).thenReturn("test");
            // 新增其他门店的合法授权不能扩大已经排队任务的签发范围。
            var otherRule=new Rule(1,"store",List.of(new Clause(ScopeDtos.Kind.SPECIFIED_STORES,List.of("S002"),false)));
            runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),otherRule,id(),Instant.now(),Instant.now().plusSeconds(600));project(runtime,graph,partition);
            assertThat(executions.check(caller,check(reference,tenant,"S002")).decision()).isEqualTo("DENY");
            runtime.access().revoke(login,partition,id(),grant.id(),1);
            assertThatThrownBy(()->second.executions(graph).check(caller,allowed)).hasMessage("AUTHZ_STATE_NOT_READY");
            project(runtime,graph,partition);assertThat(executions.check(caller,allowed).decision()).isEqualTo("DENY");
            runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),rule,id(),Instant.now(),Instant.now().plusSeconds(600));project(runtime,graph,partition);
            assertThat(executions.check(caller,allowed).decision()).isEqualTo("DENY");
            // 旧版本引用不会被悄悄升级为新的身份版本。
            jdbc.update("update auth_governance.membership set version=version+1 where id=?",member.membershipId());
            assertThatThrownBy(()->executions.check(caller,allowed)).hasMessage("AUTHZ_STATE_NOT_READY");
            jdbc.update("update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",reference.executionId());
            assertThatThrownBy(()->executions.check(caller,allowed)).hasMessage("ACCESS_DENIED");
            var importer=runtime.migrationImport();String unit="p6-"+id();
            var item=new MigrationImport.Item("source-1",1,member.membershipId(),1,role.id(),rule,Instant.now().minusSeconds(1).toString(),Instant.now().plusSeconds(500).toString(),false,"explicit finite fixture");
            var batch=new MigrationImport.Batch(unit,1,"a".repeat(64),"b".repeat(64),partition,List.of(item));
            com.lrj.authz.governance.persistence.MigrationMapper.Row imported;
            try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate=new java.util.concurrent.CountDownLatch(1);
                var first=workers.submit(()->{gate.await();return importer.apply(login,batch).getFirst();});
                var other=workers.submit(()->{gate.await();return second.migrationImport().apply(login,batch).getFirst();});
                gate.countDown();imported=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(other.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(imported);
            }
            assertThat(second.migrationImport().apply(login,batch).getFirst()).isEqualTo(imported);
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.migration_record where unit_id=?",Integer.class,unit)).isEqualTo(1);
            var invalid=new MigrationImport.Item("source-1",2,member.membershipId(),1,role.id(),rule,item.validFrom(),null,false,"no implicit permanent grant");
            assertThatThrownBy(()->importer.apply(login,new MigrationImport.Batch(unit,2,"c".repeat(64),"b".repeat(64),partition,List.of(invalid)))).hasMessage("INVALID_ARGUMENT");
            assertThat(jdbc.queryForObject("select state from auth_governance.access_grant where id=?",String.class,imported.grantId())).isEqualTo("PENDING");
            var denial=new MigrationImport.Item("source-1",2,member.membershipId(),1,role.id(),rule,null,null,true,"source revoked");
            var delta=new MigrationImport.Batch(unit,2,"c".repeat(64),"b".repeat(64),partition,List.of(denial));
            assertThat(importer.apply(login,delta).getFirst().tombstone()).isTrue();
            assertThat(jdbc.queryForObject("select state from auth_governance.access_grant where id=?",String.class,imported.grantId())).isEqualTo("REVOKED");
            assertThatThrownBy(()->importer.apply(login,batch)).hasMessage("VERSION_CONFLICT");
            assertThatThrownBy(()->importer.apply(login,new MigrationImport.Batch(unit,3,"d".repeat(64),"b".repeat(64),partition,List.of(new MigrationImport.Item("source-1",3,member.membershipId(),1,role.id(),rule,item.validFrom(),item.validTo(),false,"stale allow"))))).hasMessage("ACCESS_DENIED");
            var latest=runtime.identity().contextForLogin(member.issuer(),member.subject(),tenant,1L);
            var newContext=new AccessContext(latest.principalId(),latest.membershipId(),latest.membershipGeneration(),latest.membershipVersion(),latest.principalVersion(),tenant,app,"test","commerce-p6","HUMAN",id());
            project(runtime,graph,partition);
            var fresh=executions.issue(newContext,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),capability,"store"),Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));
            assertThat(executions.check(caller,check(fresh,tenant,"S002")).decision()).isEqualTo("ALLOW");
            person(runtime,tenant,code);project(runtime,graph,partition);
            assertThatThrownBy(()->executions.check(caller,check(fresh,tenant,"S002"))).hasMessage("ACCESS_DENIED");
            assertThat(jdbc.queryForObject("select context_json || paths_json from auth_governance.execution_reference where id=?",String.class,reference.executionId())).doesNotContain("access_token","user-token","credential");
        }
    }
    @Test void inventoryReferencesAreShortLivedAndCannotSwapReadForReceive() {
        var db=GovernanceDatabase.from(GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph=new SpiceDbProjectionGraph(props.getProperty("graph.http"),props.getProperty("graph.key"),Duration.ofSeconds(3));
        try(var runtime=GovernanceRuntime.open(db,true)) {
            String tenant=id(),code="inventory-"+id(),app="commerce-inventory-"+id(),read=app+".inventory.read",receive=app+".inventory.receive";
            var owner=person(runtime,tenant,code);var member=person(runtime,tenant,code);
            var login=new VerifiedLogin(owner.issuer(),owner.subject());
            runtime.catalog().register(app,owner.principalId(),"https://inventory.example","test",id());
            runtime.catalog().publish(login,new Manifest("1",app,1,List.of(new Capability(read,"store",Risk.NORMAL),new Capability(receive,"store",Risk.HIGH)),List.of()),id());
            var partition=new Partition(tenant,app,"test");
            runtime.access().bootstrap(partition,new Delegation(owner.membershipId(),1,AccessValues.json(List.of(read,receive)),3600),"test",id());
            var role=runtime.access().createRole(login,partition,id(),"inventory-reader",1,List.of(read));
            runtime.access().enableStrict(login,partition,id());
            var rule=new Rule(1,"store",List.of(new Clause(ScopeDtos.Kind.SPECIFIED_STORES,List.of("S1"),false)));
            var grant=runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),rule,id(),Instant.now(),Instant.now().plusSeconds(300));
            project(runtime,graph,partition);
            var c=runtime.identity().contextForLogin(member.issuer(),member.subject(),tenant,1L);
            var context=new AccessContext(c.principalId(),c.membershipId(),1,c.membershipVersion(),c.principalVersion(),tenant,app,"test","commerce-inventory","HUMAN",id());
            var executions=runtime.executions(graph);
            var request=new CentralAccessDtos.Check(tenant,1L,id(),read,"store");
            String deadline=Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
            var ref=executions.issue(context,new Issue(request,deadline));
            assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),read,"store"),Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()))).hasMessage("INVALID_ARGUMENT");
            assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),receive,"store"),deadline))).hasMessage("ACCESS_DENIED");
            var caller=mock(CallerService.class);when(caller.callerServiceId()).thenReturn("commerce-inventory");when(caller.applicationId()).thenReturn(app);when(caller.environment()).thenReturn("test");
            assertThat(executions.check(caller,check(ref,tenant,"S1")).decision()).isEqualTo("ALLOW");
            assertThat(executions.check(caller,check(ref,tenant,"S2")).decision()).isEqualTo("DENY");
            var swapped=new Check(ref.executionId(),new ScopeAccessDtos.ResourceCheck(new CentralAccessDtos.Check(tenant,1L,id(),receive,"store"),new Facts(tenant,"store","S1",0,null,null,List.of(),"S1",null)));
            assertThatThrownBy(()->executions.check(caller,swapped)).hasMessage("ACCESS_DENIED");
            runtime.access().revoke(login,partition,id(),grant.id(),1);project(runtime,graph,partition);
            assertThat(executions.check(caller,check(ref,tenant,"S1")).decision()).isEqualTo("DENY");
        }
    }
    @Test void directoryScopeIsBoundToOriginalGrantAndCreationRequiresWholeTenant() {
        var db=GovernanceDatabase.from(GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph=new SpiceDbProjectionGraph(props.getProperty("graph.http"),props.getProperty("graph.key"),Duration.ofSeconds(3));
        try(var runtime=GovernanceRuntime.open(db,true)) {
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
            for(String suffix:List.of("merchant.read","merchant.create","store.directory.read","store.create")) {
                boolean merchant=suffix.startsWith("merchant."),creation=suffix.endsWith(".create");
                String type=merchant?"merchant":"store",tenant=id(),code="directory-"+id(),app="commerce-directory-"+id(),cap=app+"."+suffix;
                var owner=person(runtime,tenant,code);var member=person(runtime,tenant,code);var limited=person(runtime,tenant,code);
                var login=new VerifiedLogin(owner.issuer(),owner.subject());
                runtime.catalog().register(app,owner.principalId(),"https://directory.example","test",id());
                runtime.catalog().publish(login,new Manifest("1",app,1,List.of(new Capability(cap,type,creation?Risk.HIGH:Risk.NORMAL)),List.of()),id());
                var partition=new Partition(tenant,app,"test");
                runtime.access().bootstrap(partition,new Delegation(owner.membershipId(),1,AccessValues.json(List.of(cap)),3600),"test",id());
                var role=runtime.access().createRole(login,partition,id(),"directory",1,List.of(cap));runtime.access().enableStrict(login,partition,id());
                var partial=new Rule(1,type,List.of(new Clause(merchant?ScopeDtos.Kind.SPECIFIED_RESOURCES:ScopeDtos.Kind.SPECIFIED_STORES,List.of("ONE"),false)));
                var allowed=creation?new Rule(1,type,List.of(new Clause(ScopeDtos.Kind.TENANT_ALL,List.of(),false))):partial;
                var grant=runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),allowed,id(),Instant.now(),Instant.now().plusSeconds(300));
                runtime.access().grantScoped(login,partition,id(),limited.membershipId(),1,role.id(),partial,id(),Instant.now(),Instant.now().plusSeconds(300));
                project(runtime,graph,partition);
                var context=directoryContext(runtime,member,tenant,app);var executions=runtime.executions(graph);
                String until=Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
                var request=new CentralAccessDtos.Check(tenant,1L,id(),cap,type);
                var reference=executions.issue(context,new Issue(request,until));
                var caller=mock(CallerService.class);when(caller.callerServiceId()).thenReturn("commerce-directory");when(caller.applicationId()).thenReturn(app);when(caller.environment()).thenReturn("test");
                var query=new ScopeCheck(reference.executionId(),new CentralAccessDtos.Check(tenant,1L,id(),cap,type));
                var plan=executions.scope(caller,query);assertThat(plan.decision()).isEqualTo("ALLOW");assertThat(plan.alternatives()).hasSize(1);
                assertThat(plan.alternatives().getFirst().clauses()).isEqualTo(allowed.clauses());assertThat(Instant.parse(plan.validUntil())).isBeforeOrEqualTo(Instant.parse(until));
                if(creation)assertThatThrownBy(()->executions.issue(directoryContext(runtime,limited,tenant,app),new Issue(new CentralAccessDtos.Check(tenant,1L,id(),cap,type),until))).hasMessage("ACCESS_DENIED");
                assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),cap,merchant?"store":"merchant"),until))).hasMessage("ACCESS_DENIED");
                assertThatThrownBy(()->executions.scope(caller,new ScopeCheck(reference.executionId(),new CentralAccessDtos.Check(id(),1L,id(),cap,type)))).hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("foreign");assertThatThrownBy(()->executions.scope(caller,query)).hasMessage("ACCESS_DENIED");when(caller.environment()).thenReturn("test");
                runtime.access().revoke(login,partition,id(),grant.id(),1);project(runtime,graph,partition);
                assertThat(executions.scope(caller,query).decision()).isEqualTo("DENY");
                runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),allowed,id(),Instant.now(),Instant.now().plusSeconds(300));project(runtime,graph,partition);
                assertThat(executions.scope(caller,query).alternatives()).isEmpty();
                jdbc.update("update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",reference.executionId());
                assertThatThrownBy(()->executions.scope(caller,query)).hasMessage("ACCESS_DENIED");
            }
        }
    }

    /** 会员的租户范围不借门店语义，创建许可与实际目标事实分别验证。 */
    @Test void memberCoreReferencesBindFiniteCapabilitiesAndActualFactShape() {
        tenantMemberReferences(List.of("member.read","member.create","member.profile.update","member.status.update"));
    }
    /** 成长政策无虚构对象；已有会员的成长动作仍核验真实会员事实和独立能力。 */
    @Test void growthReferencesBindPolicyAndMemberCapabilitiesIndependently() {
        tenantMemberReferences(List.of("growth.policy.read","growth.policy.publish","growth.read","growth.adjust","growth.recalculate"));
    }
    /** 标签定义只取集合许可，分配和会员标签读取可使用真实会员事实；三能力相互独立。 */
    @Test void tagReferencesRejectFakeMemberForDefinitionAndKeepAssignmentsScoped() {
        tenantMemberReferences(List.of("member_tag.read","member_tag.define","member_tag.assign"));
    }
    private void tenantMemberReferences(List<String> suffixes) {
        var db=GovernanceDatabase.from(GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG")));
        var props=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));
        var graph=new SpiceDbProjectionGraph(props.getProperty("graph.http"),props.getProperty("graph.key"),Duration.ofSeconds(3));
        try(var runtime=GovernanceRuntime.open(db,true)) {
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(),db.username(),db.password()));
            for(String suffix:suffixes) {
                boolean policy=suffix.startsWith("growth.policy.");
                boolean collectionOnly=policy || suffix.equals("member.create") || suffix.equals("member_tag.define");
                String type=policy?ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE:ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE;
                String tenant=id(),code="member-"+id(),app="commerce-member-"+id(),cap=app+"."+suffix;
                var owner=person(runtime,tenant,code);var member=person(runtime,tenant,code);
                var login=new VerifiedLogin(owner.issuer(),owner.subject());
                runtime.catalog().register(app,owner.principalId(),"https://member.example","test",id());
                runtime.catalog().publish(login,new Manifest("1",app,1,List.of(new Capability(cap,type,Risk.HIGH)),List.of()),id());
                var partition=new Partition(tenant,app,"test");
                runtime.access().bootstrap(partition,new Delegation(owner.membershipId(),1,AccessValues.json(List.of(cap)),3600),"test",id());
                var role=runtime.access().createRole(login,partition,id(),"member-core",1,List.of(cap));runtime.access().enableStrict(login,partition,id());
                var all=new Rule(1,type,List.of(new Clause(ScopeDtos.Kind.TENANT_ALL,List.of(),false)));
                var partial=new Rule(1,type,List.of(new Clause(ScopeDtos.Kind.SPECIFIED_STORES,List.of("S1"),false)));
                assertThatThrownBy(()->runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),partial,id(),Instant.now(),Instant.now().plusSeconds(300))).hasMessage("SCOPE_UNSUPPORTED");
                var grant=runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),all,id(),Instant.now(),Instant.now().plusSeconds(300));project(runtime,graph,partition);
                var context=directoryContext(runtime,member,tenant,app);var executions=runtime.executions(graph);
                String until=Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
                var request=new CentralAccessDtos.Check(tenant,1L,id(),cap,type);
                var ref=executions.issue(context,new Issue(request,until));
                var caller=mock(CallerService.class);when(caller.callerServiceId()).thenReturn("commerce-directory");when(caller.applicationId()).thenReturn(app);when(caller.environment()).thenReturn("test");
                var query=new ScopeCheck(ref.executionId(),request);
                assertThat(executions.scope(caller,query).alternatives().getFirst().clauses()).isEqualTo(all.clauses());
                assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),cap,"store"),until))).hasMessage("ACCESS_DENIED");
                assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),app+".member.unknown",type),until))).hasMessage("ACCESS_DENIED");
                assertThatThrownBy(()->executions.issue(context,new Issue(new CentralAccessDtos.Check(tenant,1L,id(),cap,type),Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()))).hasMessage("INVALID_ARGUMENT");
                var facts=new Facts(tenant,type,"MEMBER-1",7,null,null,List.of(),null,null);
                var resource=new Check(ref.executionId(),new ScopeAccessDtos.ResourceCheck(request,facts));
                if(collectionOnly)assertThatThrownBy(()->executions.check(caller,resource)).hasMessage("ACCESS_DENIED");
                else assertThat(executions.check(caller,resource).decision()).isEqualTo("ALLOW");
                for(var bad:List.of(new Facts(tenant,type,"MEMBER-1",7,null,null,List.of(),"S1",null),new Facts(tenant,"store","S1",7,null,null,List.of(),"S1",null),new Facts(id(),type,"MEMBER-1",7,null,null,List.of(),null,null)))
                    assertThatThrownBy(()->executions.check(caller,new Check(ref.executionId(),new ScopeAccessDtos.ResourceCheck(request,bad)))).hasMessage("INVALID_ARGUMENT");
                assertThatThrownBy(()->executions.scope(caller,new ScopeCheck(ref.executionId(),new CentralAccessDtos.Check(tenant,2L,id(),cap,type)))).hasMessage("ACCESS_DENIED");
                assertThatThrownBy(()->executions.scope(caller,new ScopeCheck(ref.executionId(),new CentralAccessDtos.Check(tenant,1L,id(),app+".member.other",type)))).hasMessage("ACCESS_DENIED");
                when(caller.environment()).thenReturn("other");assertThatThrownBy(()->executions.scope(caller,query)).hasMessage("ACCESS_DENIED");when(caller.environment()).thenReturn("test");
                runtime.access().revoke(login,partition,id(),grant.id(),1);project(runtime,graph,partition);
                assertThat(executions.scope(caller,query).decision()).isEqualTo("DENY");
                if(!collectionOnly)assertThat(executions.check(caller,resource).decision()).isEqualTo("DENY");
                runtime.access().grantScoped(login,partition,id(),member.membershipId(),1,role.id(),all,id(),Instant.now(),Instant.now().plusSeconds(300));project(runtime,graph,partition);
                assertThat(executions.scope(caller,query).alternatives()).isEmpty();
                jdbc.update("update auth_governance.execution_reference set expires_at=clock_timestamp()-interval '1 second' where id=?",ref.executionId());
                assertThatThrownBy(()->executions.scope(caller,query)).hasMessage("ACCESS_DENIED");
            }
        }
    }
    private static AccessContext directoryContext(GovernanceRuntime runtime,BootstrapCommand member,String tenant,String app) {
        var c=runtime.identity().contextForLogin(member.issuer(),member.subject(),tenant,1L);
        return new AccessContext(c.principalId(),c.membershipId(),1,c.membershipVersion(),c.principalVersion(),tenant,app,"test","commerce-directory","HUMAN",id());
    }
    private static Check check(Reference ref,String tenant,String store) {
        return new Check(ref.executionId(),new ScopeAccessDtos.ResourceCheck(new CentralAccessDtos.Check(tenant,1L,id(),ref.capability(),"store"),new Facts(tenant,"store",store,1,null,null,List.of(),store,null)));
    }
    private static BootstrapCommand person(GovernanceRuntime r,String tenant,String code){
        var c=new BootstrapCommand(id(),"execution-test",tenant,code,id(),"https://execution.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"fixture",id(),id());r.identity().bootstrapEmployee(c);return c;
    }
    private static void project(GovernanceRuntime r,SpiceDbProjectionGraph graph,Partition partition){
        assertThat(r.reliableProjector(graph).step(partition,com.lrj.authz.governance.domain.ProjectionModels.Kind.POLICY,id())).isEqualTo(Step.READY);
        assertThat(r.reliableProjector(graph).step(partition,com.lrj.authz.governance.domain.ProjectionModels.Kind.DIRECTORY,id())).isEqualTo(Step.READY);
    }
}
