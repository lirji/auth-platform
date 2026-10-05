package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.ScopeDtos.Rule;

import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 单条短事务导入，进程中断可重放；墓碑拒绝优先，不能用旧完整快照覆盖新撤权。 */
public final class MigrationImport {
    public record Item(
            String sourceId,
            long sourceVersion,
            String memberId,
            long generation,
            String roleId,
            Rule scope,
            String validFrom,
            String validTo,
            boolean deny,
            String reason) {}

    public record Batch(
            String unitId,
            long sequence,
            String snapshotHash,
            String mappingHash,
            Partition partition,
            List<Item> items) {}

    private final MigrationMapper mapper;
    private final AccessManagement access;
    private final AccessMapper grants;
    private final TransactionTemplate tx;
    private final IdentityMapper audit;

    public MigrationImport(
            MigrationMapper mapper,
            AccessManagement access,
            AccessMapper grants,
            TransactionTemplate tx,
            IdentityMapper audit) {
        this.mapper = mapper;
        this.access = access;
        this.grants = grants;
        this.tx = tx;
        this.audit = audit;
    }

    /** 每次重新验证管理委派；单批最多100条，部分完成留持久检查点，禁止吞失败报告整批成功。 */
    public List<MigrationMapper.Row> apply(VerifiedLogin operator, Batch batch) {
        if (batch == null
                || batch.sequence() < 1
                || batch.items() == null
                || batch.items().isEmpty()
                || batch.items().size() > 100) throw error(INVALID_ARGUMENT);
        BootstrapCommand.bounded(batch.unitId(), 100);
        hash(batch.snapshotHash());
        hash(batch.mappingHash());
        AccessValues.partition(batch.partition());
        if (new HashSet<>(batch.items().stream().map(Item::sourceId).toList()).size()
                != batch.items().size()) throw error(INVALID_ARGUMENT);
        List<MigrationMapper.Row> result = new ArrayList<>();
        for (var item : batch.items())
            result.add(tx.execute(status -> applyOne(operator, batch, item)));
        return List.copyOf(result);
    }

    private MigrationMapper.Row applyOne(VerifiedLogin operator, Batch b, Item item) {
        if (item == null || item.sourceVersion() < 0) throw error(INVALID_ARGUMENT);
        BootstrapCommand.bounded(item.sourceId(), 100);
        BootstrapCommand.bounded(item.reason(), 256);
        var manager = access.authority(operator, b.partition());
        mapper.lock(b.unitId());
        var unit = mapper.unit(b.unitId());
        if (unit != null
                && (!unit.tenantId().equals(b.partition().tenantId())
                        || !unit.applicationId().equals(b.partition().applicationId())
                        || !unit.environment().equals(b.partition().environment())))
            throw error(COMMAND_CONFLICT);
        var old = mapper.find(b.unitId(), item.sourceId());
        String fp =
                AccessValues.hash(
                        b.partition(), b.sequence(), b.snapshotHash(), b.mappingHash(), item);
        if (old != null) {
            if (!old.tenantId().equals(b.partition().tenantId())
                    || !old.applicationId().equals(b.partition().applicationId())
                    || !old.environment().equals(b.partition().environment()))
                throw error(COMMAND_CONFLICT);
            if (b.sequence() < old.sequence() || item.sourceVersion() < old.sourceVersion())
                throw error(VERSION_CONFLICT);
            if (b.sequence() == old.sequence()) {
                if (!fp.equals(old.fingerprint())) throw error(COMMAND_CONFLICT);
                return old;
            }
            // 已保留的拒绝永不自动转回允许；需要新来源及新的显式业务审批。
            if (old.tombstone() && !item.deny()) throw error(ACCESS_DENIED);
        }
        String grantId = old == null ? null : old.grantId();
        var partition = b.partition();
        String command =
                UUID.nameUUIDFromBytes(
                                AccessValues.hash(
                                                b.partition(),
                                                b.unitId(),
                                                item.sourceId(),
                                                b.sequence())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        if (grantId != null) {
            var prior = grants.grant(partition, grantId);
            if (prior == null) throw error(AUTHZ_STATE_NOT_READY);
            if (prior.state() != com.lrj.authz.governance.domain.AccessModels.GrantState.REVOKED)
                access.revoke(operator, partition, command, grantId, prior.version());
            // 更新有效授权也创建新来源，旧任务执行引用因此不能借增量更新复活。
            if (!item.deny()) grantId = null;
        }
        if (!item.deny()) {
            // 永久来源必须先有明确期限决定；不默认变成一年或无限期，当前真实首批均为到期拒绝。
            if (item.validTo() == null || item.validFrom() == null || item.scope() == null)
                throw error(INVALID_ARGUMENT);
            var role = grants.role(partition, item.roleId());
            if (role == null
                    || !AccessValues.read(role.capabilitiesJson())
                            .equals(List.of(partition.applicationId() + ".catalog.operate"))
                    || !com.lrj.authz.protocol.ScopeDtos.STORE_RESOURCE_TYPE.equals(
                            item.scope().resourceType())
                    || item.scope().clauses().size() != 1
                    || item.scope().clauses().getFirst().kind()
                            != com.lrj.authz.protocol.ScopeDtos.Kind.SPECIFIED_STORES)
                throw error(ACCESS_DENIED);
            var grant =
                    access.grantScoped(
                            operator,
                            partition,
                            command,
                            item.memberId(),
                            item.generation(),
                            item.roleId(),
                            item.scope(),
                            "p6-" + AccessValues.hash(b.unitId(), item.sourceId(), b.sequence()),
                            Instant.parse(item.validFrom()),
                            Instant.parse(item.validTo()));
            grantId = grant.id();
        }
        var row =
                new MigrationMapper.Row(
                        b.unitId(),
                        item.sourceId(),
                        b.sequence(),
                        item.sourceVersion(),
                        fp,
                        b.snapshotHash(),
                        b.mappingHash(),
                        partition.tenantId(),
                        partition.applicationId(),
                        partition.environment(),
                        item.deny() ? "PRESERVE_DENIAL" : "IMPORTED",
                        grantId,
                        item.deny());
        if (mapper.save(row) != 1
                || audit.appendAudit(
                                UUID.randomUUID().toString(),
                                manager.membershipId(),
                                partition.tenantId(),
                                "MIGRATION_IMPORT",
                                item.sourceId(),
                                b.sequence(),
                                command)
                        != 1) throw error(DEPENDENCY_UNAVAILABLE);
        return row;
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw error(INVALID_ARGUMENT);
    }

    private static GovernanceException error(GovernanceException.Code code) {
        return new GovernanceException(code);
    }
}
