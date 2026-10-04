package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static com.lrj.authz.governance.support.RoleMigrationFixture.id;
import static org.assertj.core.api.Assertions.*;

/** 实库验证任务事务、并发和数据约束；真实图确认单独由迁移投影IT验收。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMigrationTasksPostgresIT {
    private RoleMigrationFixture h;
    @BeforeAll void open(){h=new RoleMigrationFixture();}
    @AfterAll void close(){if(h!=null)h.close();}
    @Test void createFreezesExactScopeAndDeadlineButDoesNotRevoke(){
        var f=h.fixture();var g=h.sqlActive(f,true);var input=h.create(f,g);var task=h.runtime.roleMigrationTasks().create(f.login(),input);
        assertThat(task.items()).hasSize(1);var e=task.items().getFirst();assertThat(e.state()).isEqualTo("READY_TO_REVOKE");assertThat(e.validTo()).isEqualTo(g.validTo().toString());assertThat(e.originalSourceId()).isEqualTo(g.sourceId());assertThat(e.scopeRule().clauses().getFirst().values()).containsExactly("S1");assertThat(e.newGrant()).isNull();assertThat(h.current(f,g.id()).state()).isEqualTo(GrantState.ACTIVE);
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",Integer.class,f.partition().tenantId())).isEqualTo(1);
        assertThat(h.runtime.roleMigrationTasks().create(f.login(),input).id()).isEqualTo(task.id());
        var changed=new Create(input.tenantId(),input.applicationId(),input.environment(),input.commandId(),input.oldRoleId(),input.newRoleId(),List.of(new GrantSelection(g.id(),2,g.validTo().toString(),e.scopeHash())));
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),changed)).hasMessage("COMMAND_CONFLICT");
    }
    @Test void staleVersionDeadlineAndHashRejectBeforeAnyTask(){
        var f=h.fixture();var g=h.sqlActive(f,true);var input=h.create(f,g);
        for(var selected:List.of(new GrantSelection(g.id(),2,g.validTo().toString(),input.grants().getFirst().scopeHash()),new GrantSelection(g.id(),1,g.validTo().plusSeconds(1).toString(),input.grants().getFirst().scopeHash()),new GrantSelection(g.id(),1,g.validTo().toString(),"a".repeat(64)))){
            var bad=new Create(input.tenantId(),input.applicationId(),input.environment(),id(),input.oldRoleId(),input.newRoleId(),List.of(selected));assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),bad)).hasMessage("VERSION_CONFLICT");
        }
        assertThat(h.runtime.roleMigrationTasks().list(f.login(),f.partition(),f.oldRole().id(),null).items()).isEmpty();
    }
    @Test void unsupportedAndDuplicateSelectionsNeverPartiallyCreate(){
        var f=h.fixture();var g=h.sqlActive(f,false);var input=h.create(f,g);var dup=new Create(input.tenantId(),input.applicationId(),input.environment(),id(),input.oldRoleId(),input.newRoleId(),List.of(input.grants().getFirst(),input.grants().getFirst()));assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),dup)).hasMessage("INVALID_ARGUMENT");
        h.jdbc.update("UPDATE auth_governance.access_grant SET source_type='OA_REQUEST' WHERE id=?",g.id());assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),input)).hasMessage("ACCESS_DENIED");assertThat(h.runtime.roleMigrationTasks().list(f.login(),f.partition(),null,null).items()).isEmpty();
    }
    @Test void revokeAndCheckpointCommitTogetherAndCannotAcceptSqlOnlyReceipt(){
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));var e=task.items().getFirst();
        var command=new Advance(f.partition().tenantId(),f.partition().applicationId(),f.partition().environment(),id(),e.id(),1);
        task=h.runtime.roleMigrationTasks().advance(f.login(),task.id(),command);assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");assertThat(h.current(f,g.id()).state()).isEqualTo(GrantState.REVOKED);
        assertThat(h.runtime.roleMigrationTasks().advance(f.login(),task.id(),command).items().getFirst().version()).isEqualTo(2);
        task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");assertThat(task.items().getFirst().newGrant()).isNull();assertThat(task.items().getFirst().reason()).isEqualTo("PROJECTION_PROCESSING");
    }
    @Test void externalRevocationFailsInsteadOfRestoringTheSource(){
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));h.runtime.access().revoke(f.login(),f.partition(),id(),g.id(),1);
        task=h.advance(f,task,0);assertThat(task.state()).isEqualTo("FINISHED_WITH_FAILURES");assertThat(task.items().getFirst().reason()).isEqualTo("ORIGINAL_CHANGED");assertThat(task.items().getFirst().newGrant()).isNull();
    }
    @Test void managerLosingDelegationCannotAdvanceOrReplayButCheckpointSurvives(){
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));var e=task.items().getFirst();
        h.jdbc.update("UPDATE auth_governance.access_delegation SET enabled=false WHERE tenant_id=?",f.partition().tenantId());
        var cmd=new Advance(f.partition().tenantId(),f.partition().applicationId(),f.partition().environment(),id(),e.id(),1);
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().advance(f.login(),task.id(),cmd)).hasMessage("ACCESS_DENIED");assertThat(h.jdbc.queryForObject("SELECT version FROM auth_governance.role_migration_item WHERE id=?",Long.class,e.id())).isEqualTo(1);assertThat(h.jdbc.queryForObject("SELECT state FROM auth_governance.access_grant WHERE id=?",String.class,g.id())).isEqualTo("ACTIVE");
    }
    @Test void memberGenerationChangeMakesTheItemFailWithoutGrant(){
        var f=h.fixture();var g=h.sqlActive(f,true);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));h.jdbc.update("UPDATE auth_governance.membership SET generation=generation+1,version=version+1 WHERE id=?",f.member().membershipId());
        task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("FAILED");assertThat(task.items().getFirst().reason()).isEqualTo("MEMBERSHIP_UNAVAILABLE");assertThat(task.items().getFirst().newGrant()).isNull();
    }
    @Test void partialFailureDoesNotHideOtherCheckpoint(){
        var f=h.fixture();var first=h.sqlActive(f,false);var second=h.sqlActive(f,true);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,first,second));
        h.runtime.access().revoke(f.login(),f.partition(),id(),first.id(),1);
        for(int i=0;i<2;i++)task=h.advance(f,task,i);
        assertThat(task.failedCount()).isEqualTo(1);assertThat(task.waitingCount()).isEqualTo(1);assertThat(task.state()).isEqualTo("RUNNING");assertThat(task.items().stream().map(Item::state)).containsExactlyInAnyOrder("FAILED","WAIT_REVOKE_CONFIRM");
    }
    @Test void cancelBeforeAndAfterRevokeNeverRestoresOriginal(){
        for(boolean revoke:List.of(false,true)){var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));if(revoke)task=h.advance(f,task,0);task=h.cancel(f,task);assertThat(task.state()).isEqualTo("CANCELLED");assertThat(task.cancelledCount()).isEqualTo(1);assertThat(h.current(f,g.id()).state()).isEqualTo(revoke?GrantState.REVOKED:GrantState.ACTIVE);}
    }
    @Test void databaseProtectsImmutablePlanAndReservedSourceFromOldWriter(){
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));
        assertThatThrownBy(()->h.jdbc.update("UPDATE auth_governance.role_migration_item SET valid_to=valid_to+interval '1 second',version=version+1 WHERE task_id=?",task.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->h.jdbc.update("INSERT INTO auth_governance.access_grant(id,tenant_id,application_id,environment,membership_id,generation,role_id,scope,source_type,source_id,valid_from,valid_to,state,version,created_by) SELECT ?,tenant_id,application_id,environment,membership_id,generation,role_id,scope,source_type,?,valid_from,valid_to,'PENDING',1,created_by FROM auth_governance.access_grant WHERE id=?",id(),task.items().getFirst().newSourceId(),g.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->h.runtime.access().grant(f.login(),f.partition(),id(),f.member().membershipId(),1,f.newRole().id(),"TENANT_ALL",task.items().getFirst().newSourceId(),g.validFrom(),g.validTo())).hasMessage("INVALID_ARGUMENT");
    }
    @Test void duplicateLineageAndCrossPartitionCannotBeRebound(){
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g))).hasMessage("BINDING_CONFLICT");
        var input=h.create(f,g);var mixed=new Create(input.tenantId(),input.applicationId(),input.environment(),id(),input.oldRoleId(),input.newRoleId(),List.of(input.grants().getFirst(),new GrantSelection(id(),1,g.validTo().toString(),null)));
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(f.login(),mixed)).hasMessage("ACCESS_DENIED");
        var foreign=h.fixture();assertThatThrownBy(()->h.runtime.roleMigrationTasks().get(foreign.login(),foreign.partition(),task.id())).hasMessage("ACCESS_DENIED");assertThat(h.runtime.roleMigrationTasks().list(f.login(),f.partition(),f.oldRole().id(),null).items()).extracting(Summary::id).containsExactly(task.id());
    }
    @Test void twoRealRuntimesCannotApplyTheSameCheckpointTwice()throws Exception{
        var f=h.fixture();var g=h.sqlActive(f,false);var task=h.runtime.roleMigrationTasks().create(f.login(),h.create(f,g));var e=task.items().getFirst();
        try(var second=GovernanceRuntime.open(h.database,false)){var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
            try{var calls=List.of(h.runtime,second).stream().map(r->pool.submit(()->{start.await();try{return r.roleMigrationTasks().advance(f.login(),task.id(),new Advance(f.partition().tenantId(),f.partition().applicationId(),f.partition().environment(),id(),e.id(),1)).items().getFirst().state();}catch(com.lrj.authz.governance.application.GovernanceException failure){return failure.getMessage();}})).toList();start.countDown();assertThat(List.of(calls.get(0).get(10,TimeUnit.SECONDS),calls.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("WAIT_REVOKE_CONFIRM","VERSION_CONFLICT");}
            finally{pool.shutdownNow();}
        }
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.grant_projection WHERE grant_id=? AND operation='DELETE'",Integer.class,g.id())).isEqualTo(1);
    }
    @Test void legacyDirectCreationCommandHashSurvivesNullableApprovalExtension(){
        var f=h.fixture();var g=h.sqlActive(f,true);var input=h.create(f,g);var selected=input.grants().getFirst();
        String legacy="[GrantSelection[grantId="+selected.grantId()+", expectedVersion="+selected.expectedVersion()+", validTo="+selected.validTo()+", scopeHash="+selected.scopeHash()+"]]";
        String expected=com.lrj.authz.governance.application.AccessValues.hash(f.partition(),input.oldRoleId(),input.newRoleId(),legacy);
        var task=h.runtime.roleMigrationTasks().create(f.login(),input);
        assertThat(h.jdbc.queryForObject("SELECT payload_hash FROM auth_governance.command_record WHERE tenant_id=? AND operation='CREATE_ROLE_MIGRATION' AND command_id=?",String.class,f.partition().tenantId(),input.commandId())).isEqualTo(expected);
        assertThat(h.runtime.roleMigrationTasks().create(f.login(),input).id()).isEqualTo(task.id());
    }

}
