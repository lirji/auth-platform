package com.lrj.authz.governance.migration.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.access.persistence.CapabilityRetirementMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.persistence.IdentityMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.springframework.transaction.support.TransactionTemplate;

/** 仅既有受控运维配置执行引用退出；Owner检查不会继承平台运维或人员管理写权限。 */
public final class RetirementReferenceExit {
    private static final String OPERATION = "EXIT_RETIREMENT_REFERENCE";

    /** 首版只停止策略或管理委派，不能恢复、扩权或撤掉已有业务Grant。 */
    public enum Kind {
        DELEGATION,
        POLICY
    }

    private final CatalogMapper catalogs;
    private final CapabilityRetirementMapper mapper;
    private final IdentityMapper commands;
    private final TransactionTemplate tx;

    /** 全部事实在同一治理库，不直接操作其他项目数据库。 */
    public RetirementReferenceExit(
            CatalogMapper catalogs,
            CapabilityRetirementMapper mapper,
            IdentityMapper commands,
            TransactionTemplate tx) {
        this.catalogs = catalogs;
        this.mapper = mapper;
        this.commands = commands;
        this.tx = tx;
    }

    /** 只返回固定依据摘要，不在命令行打印能力集合和人员数据。 */
    public String inspect(Partition p, Kind kind, String id) {
        validate(p, kind, id);
        return tx.execute(
                status -> {
                    lock(p);
                    return hash(p, kind, id);
                });
    }

    /** 先完整摘要CAS，再单向disable；幂等与原因审计和减少效果同事务。 */
    public void exit(
            Partition p,
            Kind kind,
            String id,
            String expectedHash,
            String operator,
            String command,
            String reason) {
        validate(p, kind, id);
        BootstrapCommand.bounded(operator, 100);
        BootstrapCommand.uuid(command);
        BootstrapCommand.bounded(reason, 500);
        if (expectedHash == null || !expectedHash.matches("[a-f0-9]{64}"))
            throw new GovernanceException(INVALID_ARGUMENT);
        tx.executeWithoutResult(
                status -> {
                    lock(p);
                    String payload = AccessValues.hash(p, kind, id, expectedHash, operator, reason);
                    commands.reserveCommand(operator, p.tenantId(), OPERATION, command, payload);
                    var prior = commands.lockCommand(operator, p.tenantId(), OPERATION, command);
                    if (prior == null || !prior.payloadHash().equals(payload))
                        throw new GovernanceException(COMMAND_CONFLICT);
                    if (prior.completed()) return;
                    if (!expectedHash.equals(hash(p, kind, id)))
                        throw new GovernanceException(VERSION_CONFLICT);
                    if (mapper.disableReference(p, kind.name(), id) != 1)
                        throw new GovernanceException(VERSION_CONFLICT);
                    // 独立审计以完整原行摘要表达变化，不伪造旧表不存在的递增版本。
                    if (mapper.exitAudit(
                                    p,
                                    kind.name(),
                                    id,
                                    operator,
                                    command,
                                    reason,
                                    expectedHash,
                                    hash(p, kind, id))
                            != 1) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    if (commands.completeCommand(operator, p.tenantId(), OPERATION, command, id)
                            != 1) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                });
    }

    private void lock(Partition p) {
        if (catalogs.lockApplication(p.applicationId()) == null)
            throw new GovernanceException(ACCESS_DENIED);
    }

    private String hash(Partition p, Kind kind, String id) {
        String raw = mapper.exitBasis(p, kind.name(), id);
        if (raw == null) throw new GovernanceException(ACCESS_DENIED);
        return AccessValues.hash(p, kind, id, raw);
    }

    private static void validate(Partition p, Kind kind, String id) {
        AccessValues.partition(p);
        BootstrapCommand.uuid(id);
        if (kind == null) throw new GovernanceException(INVALID_ARGUMENT);
    }
}
