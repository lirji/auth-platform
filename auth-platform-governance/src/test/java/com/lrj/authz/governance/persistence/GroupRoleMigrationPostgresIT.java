package com.lrj.authz.governance.persistence;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.domain.AccessModels.GrantState;
import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.protocol.RoleMigrationDtos;

import org.junit.jupiter.api.*;

/** 真实PG验证GROUP计划、目录资格与旧写节点守卫，不用SQL ACTIVE证明图生效。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupRoleMigrationPostgresIT {
    private RoleMigrationFixture h;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private com.lrj.authz.governance.domain.AccessModels.Grant active(
            RoleMigrationFixture.F f, RoleMigrationFixture.G group) {
        var grant = h.groupGrant(f, group);
        h.jdbc.update(
                "UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?", grant.id());
        return grant;
    }

    @Test
    void groupPlanKeepsOneDynamicSourceAndOriginalDeadline() {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = active(f, group);
        var input = h.create(f, grant);
        var task = h.runtime.roleMigrationTasks().create(f.login(), input);
        var item = task.items().getFirst();
        assertThat(item.sourceType()).isEqualTo("GROUP");
        assertThat(item.memberId()).isNull();
        assertThat(item.memberGeneration()).isZero();
        assertThat(item.groupId()).isEqualTo(group.groupId());
        assertThat(item.validTo()).isEqualTo(grant.validTo().toString());
        assertThat(item.scopeRule().clauses().getFirst().values()).containsExactly("S1");
        assertThat(item.newGrant()).isNull();
        assertThat(h.current(f, grant.id()).state()).isEqualTo(GrantState.ACTIVE);
        assertThat(h.runtime.roleMigrationTasks().create(f.login(), input).id())
                .isEqualTo(task.id());
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.role_migration_item WHERE task_id=?",
                                Integer.class,
                                task.id()))
                .isEqualTo(1);
    }

    @Test
    void groupStopOrSourceQuarantineFailsBeforeRevoke() {
        for (boolean quarantine : new boolean[] {false, true}) {
            var f = h.fixture();
            var group = h.directoryGroup(f);
            var grant = active(f, group);
            var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant));
            if (quarantine)
                h.jdbc.update(
                        "UPDATE auth_governance.directory_source SET quarantined=true WHERE id=?",
                        group.authority().id());
            else
                h.jdbc.update(
                        "UPDATE auth_governance.directory_group SET active=false WHERE id=?",
                        group.groupId());
            task = h.advance(f, task, 0);
            assertThat(task.items().getFirst().reason()).isEqualTo("GROUP_UNAVAILABLE");
            assertThat(task.items().getFirst().newGrant()).isNull();
            assertThat(h.current(f, grant.id()).state()).isEqualTo(GrantState.ACTIVE);
        }
    }

    @Test
    void managerJoiningGroupCannotUseMigrationForSelfGrant() {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = active(f, group);
        h.groupEmployee(f, group, 4, 1, true, true);
        var report =
                h.runtime
                        .roleMigrationPreview()
                        .preview(
                                f.login(),
                                new RoleMigrationDtos.PreviewRequest(
                                        f.partition().tenantId(),
                                        f.partition().applicationId(),
                                        f.partition().environment(),
                                        f.oldRole().id(),
                                        f.newRole().id(),
                                        java.util.List.of(grant.id())));
        assertThat(report.items().getFirst().reasons())
                .contains(RoleMigrationDtos.Exclusion.SELF_GRANT_DENIED);
        assertThatThrownBy(
                        () -> h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant)))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void leavingGroupDoesNotRewriteTheDynamicPlanOrRestoreMembership() {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = active(f, group);
        var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant));
        h.groupEmployee(f, group, 4, 2, false, false);
        task = h.advance(f, task, 0);
        assertThat(task.items().getFirst().state()).isEqualTo("WAIT_REVOKE_CONFIRM");
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT current FROM auth_governance.directory_group_member WHERE group_id=? AND membership_id=?",
                                Boolean.class,
                                group.groupId(),
                                f.member().membershipId()))
                .isFalse();
        assertThat(task.items().getFirst().memberId()).isNull();
        assertThat(task.items().getFirst().groupId()).isEqualTo(group.groupId());
    }

    @Test
    void immutableSourceShapeAndReservedPrefixRejectOldSqlAndApiWriters() {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = active(f, group);
        var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant));
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "UPDATE auth_governance.role_migration_item SET source_type='DIRECT',member_id=?,generation=1,group_id=NULL,version=version+1 WHERE task_id=?",
                                        f.member().membershipId(),
                                        task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                h.runtime
                                        .access()
                                        .grantGroup(
                                                f.login(),
                                                f.partition(),
                                                id(),
                                                group.groupId(),
                                                f.newRole().id(),
                                                task.items().getFirst().scopeRule(),
                                                task.items().getFirst().newSourceId(),
                                                grant.validFrom(),
                                                grant.validTo()))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(
                        () ->
                                h.jdbc.update(
                                        "INSERT INTO auth_governance.access_grant(id,tenant_id,application_id,environment,membership_id,generation,group_id,role_id,scope,source_type,source_id,valid_from,valid_to,state,version,created_by) SELECT ?,tenant_id,application_id,environment,membership_id,generation,group_id,role_id,scope,source_type,?,valid_from,valid_to,'PENDING',1,created_by FROM auth_governance.access_grant WHERE id=?",
                                        id(),
                                        task.items().getFirst().newSourceId(),
                                        grant.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void externalGroupRevocationNeverCreatesReplacement() {
        var f = h.fixture();
        var group = h.directoryGroup(f);
        var grant = active(f, group);
        var task = h.runtime.roleMigrationTasks().create(f.login(), h.create(f, grant));
        h.runtime.access().revoke(f.login(), f.partition(), id(), grant.id(), 1);
        task = h.advance(f, task, 0);
        assertThat(task.items().getFirst().reason()).isEqualTo("ORIGINAL_CHANGED");
        assertThat(task.items().getFirst().newGrant()).isNull();
    }
}
