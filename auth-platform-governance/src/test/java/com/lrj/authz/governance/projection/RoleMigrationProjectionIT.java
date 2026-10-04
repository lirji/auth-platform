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
    @Test void groupSwitchPreservesScopeAndDynamicMembershipWithoutRemovingOtherSources(){
        var f=h.fixture();var group=h.directoryGroup(f);var old=h.groupGrant(f,group);project(f);
        assertThat(eligible(f,old.id())).isTrue();assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).isTrue();
        String scope=h.scope(old.id());var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,old));
        task=h.advance(f,task,0);assertThat(task.items().getFirst().newGrant()).isNull();project(f);
        assertThat(eligible(f,old.id())).isFalse();assertThat(allowed(f,f.capabilities().getFirst(),"S1")).isFalse();
        task=h.advance(f,task,0);task=h.advance(f,task,0);var next=task.items().getFirst().newGrant();
        assertThat(next.sourceType()).isEqualTo("GROUP");assertThat(next.memberId()).isNull();assertThat(next.memberGeneration()).isZero();
        assertThat(next.groupId()).isEqualTo(group.groupId());assertThat(next.validTo()).isEqualTo(old.validTo().toString());assertThat(h.scope(next.id())).isEqualTo(scope);
        project(f);task=h.advance(f,task,0);assertThat(task.state()).isEqualTo("COMPLETED");assertThat(eligible(f,next.id())).isTrue();
        assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).isTrue();assertThat(allowed(f,f.capabilities().getFirst(),"S2")).isFalse();assertThat(allowed(f,f.capabilities().get(1),"S1")).isFalse();
        var other=h.runtime.access().grantScoped(f.login(),f.partition(),id(),f.member().membershipId(),1,f.newRole().id(),task.items().getFirst().scopeRule(),id(),Instant.now(),old.validTo());project(f);
        h.groupEmployee(f,group,4,2,false,false);project(f);
        assertThat(eligible(f,next.id())).as("退组后不保留组资格").isFalse();assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).as("独立DIRECT来源仍保留").isTrue();
        h.runtime.access().revoke(f.login(),f.partition(),id(),other.id(),1);project(f);assertThat(allowed(f,f.capabilities().getFirst(),"S1")).isFalse();
    }
    @Test void memberLeavingDuringGapCannotBeAddedBackByMigration(){
        var f=h.fixture();var group=h.directoryGroup(f);var old=h.groupGrant(f,group);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,old));
        task=h.advance(f,task,0);h.groupEmployee(f,group,4,2,false,false);project(f);task=h.advance(f,task,0);task=h.advance(f,task,0);
        var next=task.items().getFirst().newGrant();project(f);task=h.advance(f,task,0);assertThat(task.state()).isEqualTo("COMPLETED");
        assertThat(eligible(f,old.id())).isFalse();assertThat(eligible(f,next.id())).isFalse();assertThat(allowed(f,f.capabilities().getFirst(),"S1")).isFalse();
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=? AND source_type='DIRECT'",Integer.class,f.partition().tenantId())).isZero();
        h.groupEmployee(f,group,5,3,true,false);project(f);assertThat(eligible(f,next.id())).as("新权威任职遵循动态组语义").isTrue();assertThat(eventuallyAllowed(f,f.capabilities().getFirst(),"S1")).isTrue();
    }
    @Test void groupStoppedAfterOldRevocationFailsWithoutRestoringAnySource(){
        var f=h.fixture();var group=h.directoryGroup(f);var old=h.groupGrant(f,group);project(f);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,old));
        task=h.advance(f,task,0);project(f);task=h.advance(f,task,0);
        h.jdbc.update("UPDATE auth_governance.directory_group SET active=false WHERE id=?",group.groupId());
        task=h.advance(f,task,0);assertThat(task.items().getFirst().reason()).isEqualTo("GROUP_UNAVAILABLE");assertThat(task.items().getFirst().newGrant()).isNull();assertThat(eligible(f,old.id())).isFalse();
    }
    @Test void foreignRealReceiptCannotAuthorizeReservedGroupSourceThroughOldSqlWriter(){
        var f=h.fixture();var group=h.directoryGroup(f);var old=h.groupGrant(f,group);project(f);
        var other=h.fixture();var unrelated=h.grant(other,false);project(other);h.runtime.access().revoke(other.login(),other.partition(),id(),unrelated.id(),1);project(other);
        String foreign=h.runtime.access().revocationReceipt(other.login(),other.partition(),unrelated.id()).operationId();
        var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,old));task=h.advance(f,task,0);var item=task.items().getFirst();
        // 仅本轮新UUID夹具模拟错误旧SQL节点引用另一分区的真实回执，不伪造图回执本身。
        h.jdbc.update("INSERT INTO auth_governance.projection_grant_receipt(grant_id,grant_version,operation_id) VALUES(?,2,?)",old.id(),foreign);
        h.jdbc.update("UPDATE auth_governance.role_migration_item SET stage='READY_TO_GRANT',version=version+1,revoke_receipt_id=? WHERE id=?",foreign,item.id());
        assertThatThrownBy(()->h.jdbc.update("INSERT INTO auth_governance.access_grant(id,tenant_id,application_id,environment,membership_id,generation,group_id,role_id,scope,source_type,source_id,valid_from,valid_to,state,version,created_by) SELECT i.planned_grant_id,i.tenant_id,i.application_id,i.environment,i.member_id,i.generation,i.group_id,i.new_role_id,i.scope,i.source_type,i.new_source_id,clock_timestamp(),i.valid_to,'PENDING',1,g.created_by FROM auth_governance.role_migration_item i JOIN auth_governance.access_grant g ON g.id=i.old_grant_id WHERE i.id=?",item.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("reserved migration lineage");
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",Integer.class,f.partition().tenantId())).isEqualTo(1);
    }
}
