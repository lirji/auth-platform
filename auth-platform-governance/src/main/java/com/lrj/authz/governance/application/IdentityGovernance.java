package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.IdentityModels.*;
import com.lrj.authz.governance.persistence.IdentityMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 身份消费用例，受控初始化与当前事实读取分开，不创建业务授权。 */
public final class IdentityGovernance {
    public static final String BOOTSTRAP_OPERATION = "BOOTSTRAP_EMPLOYEE";
    private static final int MAX_MEMBERSHIPS = 100;
    private final IdentityMapper mapper;
    private final TransactionTemplate transaction;

    /** 持久化和事务只由治理 Runtime 装配；调用方不能传入 SQL 或数据库目标。 */
    public IdentityGovernance(IdentityMapper mapper, TransactionTemplate transaction) {
        this.mapper = mapper;
        this.transaction = transaction;
    }

    /** 首次初始化不恢复生命周期，重复命令返回同一成员当前记录。 */
    public Membership bootstrapEmployee(BootstrapCommand command) {
        return transaction.execute(status -> {
            mapper.reserveCommand(command.operatorRef(), command.tenantId(), BOOTSTRAP_OPERATION,
                    command.commandId(), command.payloadHash());
            IdentityMapper.CommandRow receipt = mapper.lockCommand(command.operatorRef(), command.tenantId(),
                    BOOTSTRAP_OPERATION, command.commandId());
            if (receipt == null || !receipt.payloadHash().equals(command.payloadHash())) {
                throw new GovernanceException(COMMAND_CONFLICT);
            }
            if (receipt.completed()) { return requireMember(receipt.resultRef()); }

            mapper.insertPrincipal(new Principal(command.principalId(), PrincipalKind.HUMAN, GlobalStatus.ACTIVE, 1));
            Principal principal = mapper.principal(command.principalId());
            if (principal == null || principal.kind() != PrincipalKind.HUMAN || principal.status() != GlobalStatus.ACTIVE) {
                throw new GovernanceException(BINDING_CONFLICT);
            }
            LoginIdentity identity = new LoginIdentity(command.issuer(), command.subject(), command.principalId());
            mapper.insertLoginIdentity(identity);
            if (!identity.equals(mapper.loginIdentity(command.issuer(), command.subject()))) {
                // 唯一键冲突只允许保留原绑定；事务会回滚刚创建的孤立主体。
                throw new GovernanceException(BINDING_CONFLICT);
            }
            mapper.insertTenant(new Tenant(command.tenantId(), command.tenantCode(), GlobalStatus.ACTIVE, 1));
            Tenant tenant = mapper.tenant(command.tenantId());
            if (tenant == null || !tenant.code().equals(command.tenantCode()) || tenant.status() != GlobalStatus.ACTIVE) {
                throw new GovernanceException(BINDING_CONFLICT);
            }
            Membership expected = new Membership(command.membershipId(), command.tenantId(), command.principalId(),
                    MemberKind.EMPLOYEE, MemberStatus.ACTIVE, 1, 1, command.validFrom(), command.validTo(), null);
            mapper.insertMembership(expected);
            if (!expected.equals(mapper.membership(command.membershipId()))) {
                throw new GovernanceException(BINDING_CONFLICT);
            }
            LegacyBinding binding = new LegacyBinding(command.sourceSystem(), command.sourceTenantRef(),
                    command.sourceSubjectRef(), command.principalId(), command.membershipId(), command.tenantId());
            mapper.insertLegacyBinding(binding);
            if (!binding.equals(mapper.legacyBinding(command.sourceSystem(), command.sourceTenantRef(), command.sourceSubjectRef()))) {
                throw new GovernanceException(BINDING_CONFLICT);
            }
            requireOne(mapper.appendAudit(UUID.randomUUID().toString(), command.operatorRef(), command.tenantId(),
                    BOOTSTRAP_OPERATION, command.membershipId(), expected.version(), command.commandId()));
            requireOne(mapper.completeCommand(command.operatorRef(), command.tenantId(), BOOTSTRAP_OPERATION,
                    command.commandId(), command.membershipId()));
            return expected;
        });
    }

    /** 输入只能来自已验证身份或离线操作配置，不能采用请求 body 的 principal。 */
    public Principal principalForLogin(String issuer, String subject) {
        BootstrapCommand.bounded(issuer, 500);
        BootstrapCommand.bounded(subject, 500);
        LoginIdentity identity = mapper.loginIdentity(issuer, subject);
        if (identity == null) { throw new GovernanceException(IDENTITY_NOT_BOUND); }
        Principal principal = mapper.principal(identity.principalId());
        if (principal == null || principal.kind() != PrincipalKind.HUMAN || principal.status() != GlobalStatus.ACTIVE) {
            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
        }
        return principal;
    }

    /** 返回本人当前有效成员，超过有界列表上限时显式拒绝而非静默遗漏企业。 */
    public List<Membership> membershipsForLogin(String issuer, String subject) {
        Principal principal = principalForLogin(issuer, subject);
        List<Membership> result = mapper.activeMemberships(principal.id());
        if (result.size() > MAX_MEMBERSHIPS) { throw new GovernanceException(INVALID_ARGUMENT); }
        return List.copyOf(result);
    }

    private Membership requireMember(String id) {
        Membership member = mapper.membership(id);
        if (member == null) { throw new GovernanceException(BINDING_CONFLICT); }
        return member;
    }

    private static void requireOne(int affected) {
        if (affected != 1) { throw new GovernanceException(VERSION_CONFLICT); }
    }
}
