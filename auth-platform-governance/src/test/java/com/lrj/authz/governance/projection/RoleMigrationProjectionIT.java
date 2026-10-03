package com.lrj.authz.governance.projection;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.lrj.authz.governance.support.RoleMigrationFixture.id;
import static org.assertj.core.api.Assertions.*;

/** 真实PG和既有CAS测试图；只写本轮新UUID分区，不写原业务Grant或图marker。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMigrationProjectionIT {
    private RoleMigrationFixture h;
    private SpiceDbProjectionGraph graph;
    private AuthzEngine observer;
    @BeforeAll void open(){
        h=new RoleMigrationFixture();var p=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG"));assertThat(p.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        graph=new SpiceDbProjectionGraph(p.getProperty("graph.http"),p.getProperty("graph.key"),Duration.ofSeconds(3));observer=new SpiceDbAuthzEngine(p.getProperty("graph.http"),p.getProperty("graph.key"),Duration.ofSeconds(1),Duration.ofSeconds(3));
    }
    @AfterAll void close(){if(h!=null)h.close();}
    private void project(F f){project(h.runtime,f,graph);}
    private void project(GovernanceRuntime runtime,F f,ProjectionGraph target){
        for(var kind:List.of(Kind.POLICY,Kind.DIRECTORY)){
            Step result=null;long deadline=System.nanoTime()+30_000_000_000L;
            for(int n=0;n<120&&System.nanoTime()<deadline;n++){
                result=runtime.reliableProjector(target).step(f.partition(),kind,id());if(result==Step.READY||result==Step.RECOVERED)break;
                if(result!=Step.BUSY&&result!=Step.APPLIED&&result!=Step.RETRY_WAIT)break;
                // 宿主慢响应可能留下真实15秒租约；等待自然到期，不改SQL时间或抢夺租约。
                try{Thread.sleep(250);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError("投影等待中断",failure);}
            }
            assertThat(result).isIn(Step.READY,Step.RECOVERED);
        }
    }
    private boolean eligible(F f,String id){return observer.check(SubjectRef.of(ProjectionGraph.MEMBER_TYPE,f.member().membershipId()+"_g1"),"eligible",ResourceRef.of(ProjectionGraph.GRANT_TYPE,id),Consistency.fullyConsistent());}
    private AccessContext context(F f){var m=h.runtime.identity().contextForLogin(f.member().issuer(),f.member().subject(),f.partition().tenantId(),null);return new AccessContext(m.principalId(),m.membershipId(),m.membershipGeneration(),m.membershipVersion(),m.principalVersion(),m.tenantId(),f.partition().applicationId(),"test","mg13-test","HUMAN",id());}
    private boolean allowed(F f,String cap,String store){return h.runtime.reliableAuthorization(graph).allowed(context(f),cap,new Facts(f.partition().tenantId(),"store",store,1,null,null,List.of(),store,null));}
    /** 双水位采用至少新鲜读取，短暂保守拒绝允许存在；有界轮询真实判权，不改数据或放宽断言。 */
    private boolean eventuallyAllowed(F f,String cap,String store){
        long deadline=System.nanoTime()+12_000_000_000L;
        do{if(allowed(f,cap,store))return true;
            try{Thread.sleep(100);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError("真实判权验证中断",failure);}
        }while(System.nanoTime()<deadline);
        return false;
    }
    @Test void exactScopeAndDeadlineSwitchOnlyAfterRealRevocationConfirmation(){
        var f=h.fixture();var g=h.grant(f,true);project(f);assertThat(eligible(f,g.id())).as("original real graph eligibility").isTrue();assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).as("original S1 scope ALLOW").isTrue();assertThat(allowed(f,f.capabilities().getFirst(),"S2")).isFalse();
        String raw=h.scope(g.id());var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);assertThat(task.items().getFirst().newGrant()).isNull();
        task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");assertThat(task.items().getFirst().newGrant()).isNull();
        project(f);assertThat(eligible(f,g.id())).isFalse();assertThat(allowed(f,f.capabilities().getFirst(),"S1")).isFalse();
        task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("READY_TO_GRANT");assertThat(task.items().getFirst().revocationOperationId()).isNotNull();
        task=h.advance(f,task,0);var next=task.items().getFirst().newGrant();assertThat(next.state()).isEqualTo("PENDING");assertThat(next.validTo()).isEqualTo(g.validTo().toString());assertThat(Instant.parse(next.validFrom())).isAfterOrEqualTo(g.validFrom());assertThat(h.scope(next.id())).isEqualTo(raw);assertThat(eligible(f,g.id())).isFalse();
        project(f);task=h.advance(f,task,0);assertThat(task.state()).isEqualTo("COMPLETED");assertThat(task.items().getFirst().newOperationId()).isNotNull();assertThat(eligible(f,next.id())).isTrue();assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).as("new S1 scope ALLOW after conservative propagation").isTrue();assertThat(allowed(f,f.capabilities().get(1),"S1")).isFalse();assertThat(allowed(f,f.capabilities().getFirst(),"S2")).isFalse();
    }
    @Test void reopeningRuntimeAndRepeatingUnknownGrantCommandDoesNotDuplicate(){
        var f=h.fixture();var g=h.grant(f,false);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);project(f);task=h.advance(f,task,0);
        var e=task.items().getFirst();var input=new Advance(f.partition().tenantId(),f.partition().applicationId(),f.partition().environment(),id(),e.id(),e.version());String taskId=task.id();String next;
        try(var second=GovernanceRuntime.open(h.database,false)){var receipt=second.roleMigrationTasks().advance(f.login(),taskId,input);next=receipt.items().getFirst().newGrant().id();}
        try(var recovered=GovernanceRuntime.open(h.database,false)){var receipt=recovered.roleMigrationTasks().advance(f.login(),taskId,input);assertThat(receipt.items().getFirst().newGrant().id()).isEqualTo(next);project(recovered,f,graph);var current=recovered.roleMigrationTasks().get(f.login(),f.partition(),taskId);var item=current.items().getFirst();receipt=recovered.roleMigrationTasks().advance(f.login(),taskId,new Advance(f.partition().tenantId(),f.partition().applicationId(),f.partition().environment(),id(),item.id(),item.version()));assertThat(receipt.state()).isEqualTo("COMPLETED");}
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=? AND source_id LIKE 'role-migration:%'",Integer.class,f.partition().tenantId())).isEqualTo(1);assertThat(eligible(f,g.id())).isFalse();assertThat(eligible(f,next)).isTrue();
    }
    @Test void cancelAfterNewIntentKeepsItsRealStateAndDoesNotRestoreOld(){
        var f=h.fixture();var g=h.grant(f,true);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);project(f);task=h.advance(f,task,0);task=h.advance(f,task,0);var next=task.items().getFirst().newGrant().id();
        task=h.cancel(f,task);assertThat(task.state()).isEqualTo("CANCELLED");assertThat(task.items().getFirst().newGrant().state()).isEqualTo("PENDING");project(f);task=h.runtime.roleMigrationTasks().get(f.login(),f.partition(),task.id());assertThat(task.items().getFirst().newGrant().state()).isEqualTo("ACTIVE");assertThat(eligible(f,g.id())).isFalse();assertThat(eligible(f,next)).isTrue();
        var cancelled=task;assertThatThrownBy(()->h.advance(f,h.runtime.roleMigrationTasks().get(f.login(),f.partition(),cancelled.id()),0)).hasMessage("VERSION_CONFLICT");
    }
    @Test void graphTransportFailureKeepsCheckpointThenRecoversThroughExistingProjection(){
        var f=h.fixture();var g=h.grant(f,false);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);
        ProjectionGraph broken=new ProjectionGraph(){public Optional<Marker> readMarker(String p){throw new Failure(Failure.Code.DEPENDENCY_UNAVAILABLE);}public String compareAndWrite(String p,String expected,String next,List<RelationshipUpdate> updates){throw new AssertionError("不可达依赖不写图");}};
        assertThat(h.runtime.reliableProjector(broken).step(f.partition(),Kind.POLICY,id())).isEqualTo(Step.RETRY_WAIT);task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");assertThat(task.items().getFirst().newGrant()).isNull();
        h.runtime.access().retryStrict(f.login(),f.partition(),id(),Kind.POLICY);project(f);task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("READY_TO_GRANT");assertThat(eligible(f,g.id())).isFalse();
    }
    @Test void externalRevocationOfNewGrantCannotBeRecreatedByTheTask(){
        var f=h.fixture();var g=h.grant(f,false);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);project(f);task=h.advance(f,task,0);task=h.advance(f,task,0);var next=task.items().getFirst().newGrant();h.runtime.access().revoke(f.login(),f.partition(),id(),next.id(),1);project(f);task=h.advance(f,task,0);assertThat(task.state()).isEqualTo("FINISHED_WITH_FAILURES");assertThat(task.items().getFirst().reason()).isEqualTo("NEW_GRANT_CHANGED");assertThat(eligible(f,next.id())).isFalse();assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",Integer.class,f.partition().tenantId())).isEqualTo(2);
    }
    @Test void capabilityStopDuringGapPreventsNewGrant(){
        var f=h.fixture();var g=h.grant(f,true);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));task=h.advance(f,task,0);project(f);task=h.advance(f,task,0);h.runtime.catalog().changeCapability(f.login(),f.partition().applicationId(),f.capabilities().getFirst(),true,0,"隔离迁移撤权后停用验收",id());task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("FAILED");assertThat(task.items().getFirst().reason()).isEqualTo("CAPABILITY_UNAVAILABLE");assertThat(task.items().getFirst().newGrant()).isNull();assertThat(eligible(f,g.id())).isFalse();
    }
}
