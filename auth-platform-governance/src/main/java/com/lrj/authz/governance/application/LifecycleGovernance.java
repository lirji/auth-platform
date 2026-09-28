package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.IdentityModels.*;
import com.lrj.authz.governance.persistence.IdentityMapper;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 受控停用事务入口；事实提交后所有新身份读取立即检查当前数据库，无图清理等待窗口。 */
public final class LifecycleGovernance {
    private final IdentityMapper mapper;
    private final TransactionTemplate transaction;

    /** 与身份读取使用相同专用 Runtime，不接受调用方数据源。 */
    public LifecycleGovernance(IdentityMapper mapper, TransactionTemplate transaction) {
        this.mapper = mapper; this.transaction = transaction;
    }

    /** 回执表示当前目标事实，不是历史 ALLOW 或可缓存权限快照。 */
    public record Receipt(String targetId, String status, long version) {}

    /** 占位、CAS、原因/前后版本审计和回执原子提交，失败不保留半完成命令。 */
    public Receipt suspend(LifecycleCommand command) {
        if (command.operation() == LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER) { throw new GovernanceException(INVALID_ARGUMENT); }
        return apply(command);
    }

    /** 保留旧 suspend 用例兼容入口，新 CLI 显式区分停用与外部退出。 */
    public Receipt apply(LifecycleCommand command) {
        return transaction.execute(status -> {
            String operation = command.operation().code();
            mapper.reserveCommand(command.operatorRef(), command.scope(), operation, command.commandId(), command.payloadHash());
            var row = mapper.lockCommand(command.operatorRef(), command.scope(), operation, command.commandId());
            if (row == null || !row.payloadHash().equals(command.payloadHash())) { throw new GovernanceException(COMMAND_CONFLICT); }
            Receipt before = current(command);
            if (row.completed()) {
                if (!command.targetId().equals(row.resultRef())) { throw new GovernanceException(COMMAND_CONFLICT); }
                return before;
            }
            if (before.version() != command.expectedVersion() || !GlobalStatus.ACTIVE.code().equals(before.status())) {
                throw new GovernanceException(VERSION_CONFLICT);
            }
            int affected = switch (command.operation()) {
                case SUSPEND_MEMBER -> mapper.suspendMember(command.targetId(), command.scope(), command.expectedVersion());
                case SUSPEND_PRINCIPAL -> mapper.suspendPrincipal(command.targetId(), command.expectedVersion());
                case LEAVE_EXTERNAL_MEMBER -> mapper.leaveExternalMember(command.targetId(), command.scope(), command.expectedVersion());
            };
            requireOne(affected);
            String resultingStatus = command.operation() == LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER ? MemberStatus.LEFT.code() : GlobalStatus.SUSPENDED.code();
            requireOne(mapper.appendLifecycleAudit(UUID.randomUUID().toString(), command.operatorRef(), command.scope(), operation,
                    command.targetId(), command.expectedVersion() + 1, command.commandId(), command.reason(), before.status(),
                    resultingStatus, command.expectedVersion()));
            requireOne(mapper.completeCommand(command.operatorRef(), command.scope(), operation, command.commandId(), command.targetId()));
            return new Receipt(command.targetId(), resultingStatus, command.expectedVersion() + 1);
        });
    }

    private Receipt current(LifecycleCommand command) {
        if (command.operation() != LifecycleCommand.Operation.SUSPEND_PRINCIPAL) {
            Membership member = mapper.membership(command.targetId());
            if (member == null || !member.tenantId().equals(command.scope())) { throw new GovernanceException(MEMBERSHIP_UNAVAILABLE); }
            if (command.operation() == LifecycleCommand.Operation.LEAVE_EXTERNAL_MEMBER && member.memberKind() == MemberKind.EMPLOYEE) { throw new GovernanceException(BINDING_CONFLICT); }
            return new Receipt(member.id(), member.status().code(), member.version());
        }
        Principal principal = mapper.principal(command.targetId());
        if (principal == null) { throw new GovernanceException(MEMBERSHIP_UNAVAILABLE); }
        return new Receipt(principal.id(), principal.status().code(), principal.version());
    }

    private static void requireOne(int affected) { if (affected != 1) { throw new GovernanceException(VERSION_CONFLICT); } }
}
