package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.RequestModels.*;
import com.lrj.authz.governance.domain.RequestModels.State;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;
import com.lrj.authz.protocol.RoleMigrationDtos;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.lrj.authz.governance.support.RoleMigrationFixture.id;
import static org.assertj.core.api.Assertions.*;

/** 真PG和已验签审批Inbox验证；本类SQL ACTIVE仅作状态夹具，不冒充图ALLOW。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OaRoleMigrationPostgresIT {
    private RoleMigrationFixture h;
    private record O(F f,Policy originalPolicy,Policy replacementPolicy,Request original,Grant grant) {}
    @BeforeAll void open(){h=new RoleMigrationFixture();}
    @AfterAll void close(){if(h!=null)h.close();}
    private VerifiedLogin beneficiary(F f){return new VerifiedLogin(f.member().issuer(),f.member().subject());}
    private O fixture() throws Exception{
        var f=h.fixture();var old=h.oaPolicy(f,f.oldRole());var submitted=h.oaRequest(f,old);
        h.decideOa(f,old,submitted,"APPROVED");var original=h.runtime.requests().owned(beneficiary(f),f.partition(),submitted.id());
        h.jdbc.update("UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?",original.grantId());
        return new O(f,old,h.oaPolicy(f,f.newRole()),original,h.current(f,original.grantId()));
    }
    private Request submit(O o,String command){return h.runtime.requests().submitMigration(beneficiary(o.f()),o.f().partition(),command,
            o.replacementPolicy().id(),o.grant().id(),o.grant().version(),"本人升级");}
    private Request approved(O o) throws Exception{
        var r=submit(o,id());h.decideOa(o.f(),o.replacementPolicy(),r,"APPROVED");
        return h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),r.id());
    }
    private RoleMigrationDtos.Preview preview(O o){var p=o.f().partition();return h.runtime.roleMigrationPreview().preview(o.f().login(),
            new RoleMigrationDtos.PreviewRequest(p.tenantId(),p.applicationId(),p.environment(),o.f().oldRole().id(),o.f().newRole().id(),List.of(o.grant().id())));}
    private Create create(O o,Request r){var input=h.create(o.f(),o.grant());var selected=input.grants().getFirst();
        return new Create(input.tenantId(),input.applicationId(),input.environment(),input.commandId(),input.oldRoleId(),input.newRoleId(),
                List.of(new GrantSelection(selected.grantId(),selected.expectedVersion(),selected.validTo(),selected.scopeHash(),r.id())));}
    private Request cancel(O o,Request r){return h.runtime.requests().cancel(beneficiary(o.f()),o.f().partition(),id(),r.id(),r.stateVersion());}

    @Test void freshApprovalFreezesOriginalDeadlineAndDoesNotGrantOrRevoke() throws Exception{
        var o=fixture();String command=id();var pending=submit(o,command);
        assertThat(pending.validTo()).isEqualTo(o.grant().validTo());assertThat(pending.scopeJson()).isEqualTo(h.scope(o.grant().id()));
        assertThat(pending.migrationOldGrantId()).isEqualTo(o.grant().id());assertThat(pending.migrationOldVersion()).isEqualTo(o.grant().version());
        assertThat(submit(o,command)).isEqualTo(pending);assertThat(h.current(o.f(),o.grant().id()).state()).isEqualTo(GrantState.ACTIVE);
        assertThat(preview(o).items().getFirst().reasons()).contains(RoleMigrationDtos.Exclusion.OA_APPROVAL_REQUIRED);
        assertThatThrownBy(()->submit(o,id())).hasMessage("BINDING_CONFLICT");
        var event=h.decideOa(o.f(),o.replacementPolicy(),pending,"APPROVED");
        var r=h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),pending.id());
        assertThat(r.state()).isEqualTo(State.APPROVED);assertThat(r.grantId()).isNull();
        assertThat(h.jdbc.queryForObject("SELECT result FROM auth_governance.approval_inbox WHERE event_id=?",String.class,event)).isEqualTo("MIGRATION_APPROVED_PENDING_SWITCH");
        assertThat(h.runtime.requests().execution(beneficiary(o.f()),o.f().partition(),r.id()).displayState()).isEqualTo("MIGRATION_WAITING");
        var item=preview(o).items().getFirst();assertThat(item.eligible()).isTrue();assertThat(item.replacementRequestId()).isEqualTo(r.id());
        assertThat(item.proposedSourceId()).isEqualTo(r.id()+":single:1");
        var task=h.runtime.roleMigrationTasks().create(o.f().login(),create(o,r));assertThat(task.items().getFirst().sourceType()).isEqualTo("OA_REQUEST");
        assertThat(task.items().getFirst().replacementRequestId()).isEqualTo(r.id());assertThat(h.current(o.f(),o.grant().id()).state()).isEqualTo(GrantState.ACTIVE);
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id=?",Integer.class,o.f().partition().tenantId())).isEqualTo(1);
    }
    @Test void explicitBindingCannotBeMissingOrBorrowOrdinaryApproval() throws Exception{
        var o=fixture();var replacement=approved(o);
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(o.f().login(),h.create(o.f(),o.grant()))).hasMessage("BINDING_CONFLICT");
        var wrong=create(o,o.original());assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(o.f().login(),wrong)).hasMessage("BINDING_CONFLICT");
        assertThat(h.runtime.roleMigrationTasks().create(o.f().login(),create(o,replacement)).items()).hasSize(1);
    }
    @Test void fakeApprovedRowCannotAuthorizeAndOldNodeCannotInsertGrant() throws Exception{
        var o=fixture();var r=submit(o,id());h.jdbc.update("UPDATE auth_governance.access_request SET state='APPROVED' WHERE id=?",r.id());
        assertThat(preview(o).items().getFirst().reasons()).contains(RoleMigrationDtos.Exclusion.OA_APPROVAL_REQUIRED);
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(o.f().login(),create(o,r))).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->h.jdbc.update("INSERT INTO auth_governance.access_grant(id,tenant_id,application_id,environment,membership_id,generation,role_id,scope,source_type,source_id,valid_from,valid_to,state,version,created_by) SELECT ?,tenant_id,application_id,environment,membership_id,generation,role_id,'SCOPED','OA_REQUEST',id||':single:1',valid_from,valid_to,'PENDING',1,? FROM auth_governance.access_request WHERE id=?",id(),o.f().owner().membershipId(),r.id()))
                .hasStackTraceContaining("reserved migration lineage");
    }
    @Test void originalCancelBeforeNewApprovalStopsLinkedRequest() throws Exception{
        var o=fixture();var r=submit(o,id());cancel(o,o.original());h.decideOa(o.f(),o.replacementPolicy(),r,"APPROVED");
        var current=h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),r.id());assertThat(current.state()).isEqualTo(State.CANCELLED);
        assertThat(current.withdrawn()).isTrue();assertThat(current.grantId()).isNull();
        var old=h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),o.original().id());assertThat(old.state()).isEqualTo(State.APPROVED);assertThat(old.withdrawn()).isTrue();
        assertThat(h.current(o.f(),o.grant().id()).state()).isEqualTo(GrantState.REVOKED);
    }
    @Test void newApprovalCancelledBeforeMigrationCannotRevokeOld() throws Exception{
        var o=fixture();var r=approved(o);cancel(o,r);
        assertThat(preview(o).items().getFirst().reasons()).contains(RoleMigrationDtos.Exclusion.OA_APPROVAL_REQUIRED);
        assertThatThrownBy(()->h.runtime.roleMigrationTasks().create(o.f().login(),create(o,r))).hasMessage("ACCESS_DENIED");
        assertThat(h.current(o.f(),o.grant().id()).state()).isEqualTo(GrantState.ACTIVE);
    }
    @Test void originalCancelAfterTaskRevocationStillStopsUngrantReplacement() throws Exception{
        var o=fixture();var r=approved(o);var task=h.runtime.roleMigrationTasks().create(o.f().login(),create(o,r));task=h.advance(o.f(),task,0);
        assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");cancel(o,o.original());task=h.advance(o.f(),task,0);
        assertThat(task.items().getFirst().state()).isEqualTo("FAILED");assertThat(task.items().getFirst().reason()).isEqualTo("OA_APPROVAL_REQUIRED");
        assertThat(task.items().getFirst().newGrant()).isNull();
        assertThat(h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),r.id()).withdrawn()).isTrue();
    }
    @Test void rejectedReplacementCanBeResubmittedWithoutChangingOriginalHistory() throws Exception{
        var o=fixture();var r=submit(o,id());h.decideOa(o.f(),o.replacementPolicy(),r,"REJECTED");
        var second=submit(o,id());assertThat(second.id()).isNotEqualTo(r.id());assertThat(second.validTo()).isEqualTo(o.grant().validTo());
        assertThat(h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),o.original().id())).isEqualTo(o.original());
    }
    @Test void selfOnlyAndVersionScopeCeilingsAreEnforced() throws Exception{
        var o=fixture();var f=o.f();
        assertThatThrownBy(()->h.runtime.requests().submitMigration(f.login(),f.partition(),id(),o.replacementPolicy().id(),o.grant().id(),1,"他人")).hasMessage("ACCESS_DENIED");
        assertThatThrownBy(()->h.runtime.requests().submitMigration(beneficiary(f),f.partition(),id(),o.replacementPolicy().id(),o.grant().id(),2,"旧版本")).hasMessage("ACCESS_DENIED");
        var changed=h.runtime.requests().registerPolicy(f.login(),f.partition(),id(),f.newRole().id(),new Rule(1,"store",List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S2"),false))),1200,f.owner().membershipId(),1,3);
        assertThatThrownBy(()->h.runtime.requests().submitMigration(beneficiary(f),f.partition(),id(),changed.id(),o.grant().id(),1,"扩大范围")).hasMessage("ACCESS_DENIED");
        var page=h.runtime.requests().migrationPolicyPage(beneficiary(f),f.partition(),o.original().id(),null);
        assertThat(page.items()).extracting(Policy::id).contains(o.replacementPolicy().id()).doesNotContain(changed.id(),o.originalPolicy().id());
        assertThatThrownBy(()->h.runtime.requests().migrationPolicyPage(f.login(),f.partition(),o.original().id(),null)).hasMessage("ACCESS_DENIED");
    }
    @Test void originalLinkAndWithdrawnFactCannotBeRewritten() throws Exception{
        var o=fixture();var r=submit(o,id());
        assertThatThrownBy(()->h.jdbc.update("UPDATE auth_governance.access_request SET migration_old_version=2 WHERE id=?",r.id())).hasStackTraceContaining("request snapshot is immutable");
        cancel(o,r);assertThatThrownBy(()->h.jdbc.update("UPDATE auth_governance.access_request SET withdrawn=false WHERE id=?",r.id())).hasStackTraceContaining("request snapshot is immutable");
        assertThat(h.jdbc.queryForObject("SELECT count(*) FROM pg_attribute a WHERE a.attrelid='auth_governance.access_request'::regclass AND a.attname IN ('migration_old_grant_id','migration_old_version','withdrawn') AND col_description(a.attrelid,a.attnum) IS NOT NULL",Integer.class)).isEqualTo(3);
    }
    @Test void concurrentNewApprovalAndSelfCancellationNeverCreatesGrant() throws Exception{
        var o=fixture();var r=submit(o,id());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var ready=new java.util.concurrent.CountDownLatch(1);
            var decision=pool.submit(()->{ready.await();return h.decideOa(o.f(),o.replacementPolicy(),r,"APPROVED");});
            var cancellation=pool.submit(()->{ready.await();return cancel(o,r);});ready.countDown();
            decision.get(15,java.util.concurrent.TimeUnit.SECONDS);cancellation.get(15,java.util.concurrent.TimeUnit.SECONDS);
            var current=h.runtime.requests().owned(beneficiary(o.f()),o.f().partition(),r.id());
            assertThat(current.state()).isEqualTo(State.CANCELLED);assertThat(current.withdrawn()).isTrue();assertThat(current.grantId()).isNull();
            assertThat(h.current(o.f(),o.grant().id()).state()).isEqualTo(GrantState.ACTIVE);
        }finally{pool.shutdownNow();}
    }
    @Test void realExpiryStopsApprovedButUngrantReplacement() throws Exception{
        var f=h.fixture();var oldPolicy=h.oaPolicy(f,f.oldRole());var now=java.time.Instant.now();
        var submitted=h.runtime.requests().submit(beneficiary(f),f.partition(),id(),oldPolicy.id(),now,now.plusSeconds(6),"真实到期");
        h.decideOa(f,oldPolicy,submitted,"APPROVED");var original=h.runtime.requests().owned(beneficiary(f),f.partition(),submitted.id());
        h.jdbc.update("UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?",original.grantId());
        var o=new O(f,oldPolicy,h.oaPolicy(f,f.newRole()),original,h.current(f,original.grantId()));var r=approved(o);
        var task=h.runtime.roleMigrationTasks().create(f.login(),create(o,r));
        Thread.sleep(Math.max(1,java.time.Duration.between(java.time.Instant.now(),o.grant().validTo()).toMillis()+100));
        h.runtime.requests().settle(f.partition());var current=h.runtime.requests().owned(beneficiary(f),f.partition(),r.id());
        assertThat(current.withdrawn()).isTrue();assertThat(current.grantId()).isNull();
        task=h.advance(f,task,0);assertThat(task.items().getFirst().state()).isEqualTo("FAILED");assertThat(task.items().getFirst().newGrant()).isNull();
    }
}
