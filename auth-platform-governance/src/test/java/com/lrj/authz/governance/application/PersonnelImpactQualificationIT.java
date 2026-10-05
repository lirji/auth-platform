package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.support.RoleMigrationFixture.id;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.governance.support.RoleMigrationFixture;
import com.lrj.authz.governance.support.RoleMigrationFixture.F;

import org.junit.jupiter.api.*;

import java.util.List;

/** 仅在真实报告读取完成的边界安排失权；报告、资格修改和拒绝审计仍执行真实PG。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonnelImpactQualificationIT {
    private RoleMigrationFixture h;

    @BeforeAll
    void open() {
        h = new RoleMigrationFixture();
    }

    @AfterAll
    void close() {
        if (h != null) h.close();
    }

    private void losesQualification(F f, Runnable lose, String code) {
        var impact = spy(h.runtime.personnelImpact());
        doAnswer(
                        inv -> {
                            var real = inv.callRealMethod();
                            lose.run();
                            return real;
                        })
                .when(impact)
                .analyze(f.partition(), f.member().membershipId(), null, null);
        var portal =
                h.runtime.portalPermissions(
                        List.of(
                                new PortalDiagnosticAuthority(
                                        f.partition(), f.owner().membershipId(), 1)));
        assertThatThrownBy(
                        () ->
                                portal.personnelImpact(
                                        f.login(),
                                        f.partition(),
                                        f.member().membershipId(),
                                        null,
                                        null,
                                        impact))
                .hasMessage(code);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.portal_diagnostic_audit WHERE tenant_id=? AND operation='READ_PERSONNEL_IMPACT' AND outcome='DENIED'",
                                Integer.class,
                                f.partition().tenantId()))
                .isEqualTo(1);
        assertThat(
                        h.jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.portal_diagnostic_audit WHERE tenant_id=? AND operation='READ_PERSONNEL_IMPACT' AND outcome='ALLOWED'",
                                Integer.class,
                                f.partition().tenantId()))
                .isZero();
    }

    @Test
    void currentManagementExitDuringReadNeverReturnsOldSuccess() {
        var f = h.fixture();
        h.directoryGroup(f);
        losesQualification(
                f,
                () -> {
                    var exit = h.runtime.retirementExit();
                    exit.exit(
                            f.partition(),
                            RetirementReferenceExit.Kind.DELEGATION,
                            f.owner().membershipId(),
                            exit.inspect(
                                    f.partition(),
                                    RetirementReferenceExit.Kind.DELEGATION,
                                    f.owner().membershipId()),
                            "mg17",
                            id(),
                            "读取期间管理资格退出");
                },
                "ACCESS_DENIED");
    }

    @Test
    void currentMemberSuspensionDuringReadNeverReturnsOldSuccess() {
        var f = h.fixture();
        h.directoryGroup(f);
        losesQualification(
                f,
                () ->
                        h.runtime
                                .lifecycle()
                                .apply(
                                        new LifecycleCommand(
                                                id(),
                                                "mg17",
                                                f.partition().tenantId(),
                                                LifecycleCommand.Operation.SUSPEND_MEMBER,
                                                f.owner().membershipId(),
                                                1,
                                                "读取期间暂停")),
                "MEMBERSHIP_UNAVAILABLE");
    }
}
