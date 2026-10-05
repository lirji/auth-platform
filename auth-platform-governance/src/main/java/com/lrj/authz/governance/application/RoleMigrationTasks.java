package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;
import static com.lrj.authz.governance.domain.RoleMigrationTaskModels.GROUP_SOURCE;
import static com.lrj.authz.governance.domain.RoleMigrationTaskModels.Stage.*;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.RoleMigrationModels.Snapshot;
import com.lrj.authz.governance.domain.RoleMigrationTaskModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.AccessDtos;
import com.lrj.authz.protocol.RequestDtos.Page;
import com.lrj.authz.protocol.RoleMigrationDtos;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 直接与组来源迁移用例；每请求一个短事务检查点，不持有身份在后台继续授权。 */
public final class RoleMigrationTasks {
    private static final String RUNNING = "RUNNING";
    private static final String COMPLETE = "COMPLETED";
    private static final String BLOCKED = "BLOCKED";
    private final AccessManagement access;
    private final AccessRequests requests;
    private final RoleMigrationPreview preview;
    private final AccessMapper grants;
    private final RoleMigrationMapper snapshots;
    private final RoleMigrationTaskMapper tasks;
    private final IdentityMapper commands;
    private final TransactionTemplate tx;

    /** 所有管理副作用共享原治理事务管理器，图网络调用由原可靠投影负责。 */
    public RoleMigrationTasks(
            AccessManagement access,
            RoleMigrationPreview preview,
            AccessMapper grants,
            RoleMigrationMapper snapshots,
            RoleMigrationTaskMapper tasks,
            IdentityMapper commands,
            TransactionTemplate transaction,
            AccessRequests requests) {
        this.requests = requests;
        this.access = access;
        this.preview = preview;
        this.grants = grants;
        this.snapshots = snapshots;
        this.tasks = tasks;
        this.commands = commands;
        tx = new TransactionTemplate(transaction.getTransactionManager());
        tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 创建只冻结明确计划；任一对象已变化或不可迁移时整批拒绝。 */
    public Detail create(VerifiedLogin login, Create input) {
        if (input == null
                || input.grants() == null
                || input.grants().isEmpty()
                || input.grants().size() > RoleMigrationPreview.MAX_SELECTED)
            throw error(INVALID_ARGUMENT);
        var p = partition(input.tenantId(), input.applicationId(), input.environment());
        BootstrapCommand.uuid(input.commandId());
        BootstrapCommand.uuid(input.oldRoleId());
        BootstrapCommand.uuid(input.newRoleId());
        var selected = new TreeMap<String, GrantSelection>();
        for (var value : input.grants()) {
            if (value == null || value.expectedVersion() < 1) throw error(INVALID_ARGUMENT);
            BootstrapCommand.uuid(value.grantId());
            instant(value.validTo());
            if (value.replacementRequestId() != null)
                BootstrapCommand.uuid(value.replacementRequestId());
            if (value.scopeHash() != null && !value.scopeHash().matches("[a-f0-9]{64}"))
                throw error(INVALID_ARGUMENT);
            if (selected.put(value.grantId(), value) != null) throw error(INVALID_ARGUMENT);
        }
        return tx.execute(
                status -> {
                    var manager = access.lockAuthority(login, p);
                    String result =
                            command(
                                    manager,
                                    p,
                                    "CREATE_ROLE_MIGRATION",
                                    input.commandId(),
                                    AccessValues.hash(
                                            p,
                                            input.oldRoleId(),
                                            input.newRoleId(),
                                            selected.values()),
                                    () -> {
                                        var rows =
                                                snapshots.selected(
                                                        p, List.copyOf(selected.keySet()));
                                        if (rows.size() != selected.size())
                                            throw error(ACCESS_DENIED);
                                        // 锁序使正式Grant写入串行；再次对比预览所见版本和固定范围，不能信任旧报告。
                                        for (var row : rows) {
                                            var expected = selected.get(row.id());
                                            if (row.version() != expected.expectedVersion()
                                                    || !row.validTo()
                                                            .equals(instant(expected.validTo()))
                                                    || !Objects.equals(
                                                            row.scopeHash(), expected.scopeHash()))
                                                throw error(VERSION_CONFLICT);
                                            if (tasks.lineageExists(p, row.id(), input.newRoleId())
                                                    || tasks.sourceExists(
                                                            p,
                                                            row.membershipId(),
                                                            row.generation(),
                                                            row.groupId(),
                                                            selected.get(row.id())
                                                                                    .replacementRequestId()
                                                                            == null
                                                                    ? source(
                                                                            row.id(),
                                                                            input.newRoleId())
                                                                    : selected.get(row.id())
                                                                                    .replacementRequestId()
                                                                            + ":single:1"))
                                                throw error(BINDING_CONFLICT);
                                        }
                                        var report =
                                                preview.preview(
                                                        login,
                                                        new RoleMigrationDtos.PreviewRequest(
                                                                p.tenantId(),
                                                                p.applicationId(),
                                                                p.environment(),
                                                                input.oldRoleId(),
                                                                input.newRoleId(),
                                                                List.copyOf(selected.keySet())));
                                        if (report.excludedCount() != 0) throw error(ACCESS_DENIED);
                                        for (var item : report.items())
                                            if (!Objects.equals(
                                                    item.replacementRequestId(),
                                                    selected.get(item.grant().id())
                                                            .replacementRequestId()))
                                                throw error(BINDING_CONFLICT);
                                        if (!rows.equals(
                                                snapshots.selected(
                                                        p, List.copyOf(selected.keySet()))))
                                            throw error(VERSION_CONFLICT);
                                        var now = snapshots.now();
                                        String id = id();
                                        var task =
                                                new Task(
                                                        id,
                                                        p.tenantId(),
                                                        p.applicationId(),
                                                        p.environment(),
                                                        input.oldRoleId(),
                                                        input.newRoleId(),
                                                        RUNNING,
                                                        1,
                                                        manager.membershipId(),
                                                        manager.generation(),
                                                        input.commandId(),
                                                        now,
                                                        now);
                                        one(tasks.insertTask(task));
                                        for (var row : rows) {
                                            one(
                                                    tasks.insertEntry(
                                                            new Entry(
                                                                    id(),
                                                                    id,
                                                                    p.tenantId(),
                                                                    p.applicationId(),
                                                                    p.environment(),
                                                                    row.id(),
                                                                    row.version(),
                                                                    input.newRoleId(),
                                                                    row.membershipId(),
                                                                    row.generation(),
                                                                    row.sourceId(),
                                                                    row.scope(),
                                                                    row.ruleJson(),
                                                                    row.scopeHash(),
                                                                    row.validFrom(),
                                                                    row.validTo(),
                                                                    originalHash(row),
                                                                    selected.get(row.id())
                                                                                            .replacementRequestId()
                                                                                    == null
                                                                            ? source(
                                                                                    row.id(),
                                                                                    input
                                                                                            .newRoleId())
                                                                            : selected.get(row.id())
                                                                                            .replacementRequestId()
                                                                                    + ":single:1",
                                                                    id(),
                                                                    id(),
                                                                    id(),
                                                                    READY_TO_REVOKE,
                                                                    1,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    now,
                                                                    row.sourceType(),
                                                                    row.groupId(),
                                                                    selected.get(row.id())
                                                                            .replacementRequestId())));
                                        }
                                        audit(
                                                manager,
                                                p,
                                                "CREATE_ROLE_MIGRATION",
                                                id,
                                                1,
                                                input.commandId());
                                        return id;
                                    });
                    return detail(p, requireTask(p, result, false));
                });
    }

    /** 当前管理分区的真实任务页，关闭页面后依旧可以找到原检查点。 */
    public Page<Summary> list(VerifiedLogin login, Partition p, String oldRole, String after) {
        AccessValues.partition(p);
        if (oldRole != null) BootstrapCommand.uuid(oldRole);
        if (after != null) BootstrapCommand.uuid(after);
        return tx.execute(
                status -> {
                    access.authority(login, p);
                    if (oldRole != null) requireRole(p, oldRole);
                    var rows = tasks.tasks(p, oldRole, after == null ? "" : after);
                    return new Page<>(
                            rows.stream()
                                    .limit(20)
                                    .map(
                                            t ->
                                                    new Summary(
                                                            t.id(),
                                                            t.oldRoleId(),
                                                            t.newRoleId(),
                                                            t.state(),
                                                            t.version(),
                                                            t.createdAt().toString(),
                                                            t.updatedAt().toString()))
                                    .toList(),
                            rows.size() > 20 ? rows.get(19).id() : null);
                });
    }

    /** 只读当前状态；不得把历史完成当作新的访问许可。 */
    public Detail get(VerifiedLogin login, Partition p, String id) {
        BootstrapCommand.uuid(id);
        return tx.execute(
                status -> {
                    access.authority(login, p);
                    return detail(p, requireTask(p, id, false));
                });
    }

    /** 原命令、原检查点重放；外部撤权和资格变化转失败，绝不补回授权。 */
    public Detail advance(VerifiedLogin login, String taskId, Advance input) {
        if (input == null || input.expectedVersion() < 1) throw error(INVALID_ARGUMENT);
        BootstrapCommand.uuid(taskId);
        BootstrapCommand.uuid(input.itemId());
        BootstrapCommand.uuid(input.commandId());
        var p = partition(input.tenantId(), input.applicationId(), input.environment());
        return tx.execute(
                status -> {
                    var manager = access.lockAuthority(login, p);
                    var task = requireTask(p, taskId, true);
                    command(
                            manager,
                            p,
                            "ADVANCE_ROLE_MIGRATION",
                            input.commandId(),
                            AccessValues.hash(p, taskId, input.itemId(), input.expectedVersion()),
                            () -> {
                                var entry =
                                        tasks.entries(p, taskId).stream()
                                                .filter(e -> e.id().equals(input.itemId()))
                                                .findFirst()
                                                .orElseThrow(() -> error(ACCESS_DENIED));
                                if (!RUNNING.equals(task.state())
                                        || entry.stage().terminal()
                                        || entry.version() != input.expectedVersion())
                                    throw error(VERSION_CONFLICT);
                                step(login, p, task, entry);
                                one(tasks.refresh(p, task, false));
                                audit(
                                        manager,
                                        p,
                                        "ADVANCE_ROLE_MIGRATION",
                                        entry.id(),
                                        entry.version() + 1,
                                        input.commandId());
                                return taskId;
                            });
                    return detail(p, requireTask(p, taskId, false));
                });
    }

    /** 取消只停止尚未提交的下一步；新Grant或已撤旧效果仍真实保留并展示。 */
    public Detail cancel(VerifiedLogin login, String taskId, Cancel input) {
        if (input == null || input.expectedVersion() < 1) throw error(INVALID_ARGUMENT);
        BootstrapCommand.uuid(taskId);
        BootstrapCommand.uuid(input.commandId());
        var p = partition(input.tenantId(), input.applicationId(), input.environment());
        return tx.execute(
                status -> {
                    var manager = access.lockAuthority(login, p);
                    var task = requireTask(p, taskId, true);
                    command(
                            manager,
                            p,
                            "CANCEL_ROLE_MIGRATION",
                            input.commandId(),
                            AccessValues.hash(p, taskId, input.expectedVersion()),
                            () -> {
                                if (!RUNNING.equals(task.state())
                                        || task.version() != input.expectedVersion())
                                    throw error(VERSION_CONFLICT);
                                tasks.cancelEntries(p, taskId);
                                one(tasks.refresh(p, task, true));
                                audit(
                                        manager,
                                        p,
                                        "CANCEL_ROLE_MIGRATION",
                                        taskId,
                                        task.version() + 1,
                                        input.commandId());
                                return taskId;
                            });
                    return detail(p, requireTask(p, taskId, false));
                });
    }

    private void step(VerifiedLogin login, Partition p, Task task, Entry entry) {
        var rows = snapshots.selected(p, List.of(entry.oldGrantId()));
        if (rows.size() != 1) {
            checkpoint(p, entry, FAILED, "ORIGINAL_CHANGED", null, null, null);
            return;
        }
        var old = rows.getFirst();
        if (!originalHash(old).equals(entry.originalHash())
                || !old.roleId().equals(task.oldRoleId())
                || old.version()
                        != (entry.stage() == READY_TO_REVOKE
                                ? entry.oldVersion()
                                : entry.oldVersion() + 1)
                || old.state()
                        != (entry.stage() == READY_TO_REVOKE
                                ? GrantState.ACTIVE
                                : GrantState.REVOKED)) {
            checkpoint(
                    p,
                    entry,
                    FAILED,
                    "ORIGINAL_CHANGED",
                    entry.revokeReceiptId(),
                    entry.newGrantId(),
                    entry.newReceiptId());
            return;
        }
        // 复用最新只读资格规则；仅任务已自行撤旧的阶段忽略预期的REVOKED排除。
        var assessment =
                preview.preview(
                                login,
                                new RoleMigrationDtos.PreviewRequest(
                                        p.tenantId(),
                                        p.applicationId(),
                                        p.environment(),
                                        task.oldRoleId(),
                                        task.newRoleId(),
                                        List.of(entry.oldGrantId())))
                        .items()
                        .getFirst();
        var reasons =
                assessment.reasons().stream()
                        .filter(
                                r ->
                                        entry.stage() == READY_TO_REVOKE
                                                || r
                                                        != RoleMigrationDtos.Exclusion
                                                                .GRANT_NOT_ACTIVE)
                        .toList();
        if (!Objects.equals(assessment.replacementRequestId(), entry.replacementRequestId())) {
            checkpoint(
                    p,
                    entry,
                    FAILED,
                    "OA_APPROVAL_REQUIRED",
                    entry.revokeReceiptId(),
                    entry.newGrantId(),
                    entry.newReceiptId());
            return;
        }
        if (!reasons.isEmpty()) {
            checkpoint(
                    p,
                    entry,
                    FAILED,
                    reasons.getFirst().name(),
                    entry.revokeReceiptId(),
                    entry.newGrantId(),
                    entry.newReceiptId());
            return;
        }
        switch (entry.stage()) {
            case READY_TO_REVOKE -> {
                access.strictRevoke(
                        login, p, entry.revokeCommand(), entry.oldGrantId(), entry.oldVersion());
                checkpoint(p, entry, WAIT_REVOKE_CONFIRM, null, null, null, null);
            }
            case WAIT_REVOKE_CONFIRM, READY_TO_GRANT -> {
                var receipt = access.revocationReceipt(login, p, entry.oldGrantId());
                var proof = tasks.confirmation(p, entry.oldGrantId(), entry.oldVersion() + 1, true);
                if (!COMPLETE.equals(receipt.status())
                        || proof == null
                        || !COMPLETE.equals(proof.status())
                        || !Objects.equals(receipt.operationId(), proof.operationId())) {
                    checkpoint(
                            p,
                            entry,
                            entry.stage(),
                            BLOCKED.equals(receipt.status())
                                            || proof != null && BLOCKED.equals(proof.status())
                                    ? "PROJECTION_BLOCKED"
                                    : "PROJECTION_PROCESSING",
                            entry.revokeReceiptId(),
                            null,
                            null);
                    return;
                }
                if (entry.stage() == WAIT_REVOKE_CONFIRM) {
                    checkpoint(p, entry, READY_TO_GRANT, null, proof.operationId(), null, null);
                    return;
                }
                if (!Objects.equals(entry.revokeReceiptId(), proof.operationId())) {
                    checkpoint(
                            p,
                            entry,
                            FAILED,
                            "REVOCATION_PROOF_CHANGED",
                            entry.revokeReceiptId(),
                            null,
                            null);
                    return;
                }
                if (tasks.sourceExists(
                        p,
                        entry.memberId(),
                        entry.generation(),
                        entry.groupId(),
                        entry.newSourceId())) {
                    checkpoint(
                            p,
                            entry,
                            FAILED,
                            "LINEAGE_CONFLICT",
                            entry.revokeReceiptId(),
                            null,
                            null);
                    return;
                }
                var now = snapshots.now();
                var from = now.isBefore(entry.validFrom()) ? entry.validFrom() : now;
                if (!from.isBefore(entry.validTo())) {
                    checkpoint(
                            p, entry, FAILED, "GRANT_EXPIRED", entry.revokeReceiptId(), null, null);
                    return;
                }
                var created =
                        entry.replacementRequestId() == null
                                ? access.grantMigration(login, p, entry, from)
                                : requests.grantMigration(p, entry, from);
                checkpoint(
                        p,
                        entry,
                        WAIT_NEW_CONFIRM,
                        null,
                        entry.revokeReceiptId(),
                        created.id(),
                        null);
            }
            case WAIT_NEW_CONFIRM -> {
                var next = grants.grant(p, entry.newGrantId());
                if (next == null
                        || next.state() == GrantState.REVOKED
                        || next.version() != 1
                        || !next.roleId().equals(entry.newRoleId())
                        || !next.sourceId().equals(entry.newSourceId())
                        || !next.validTo().equals(entry.validTo())
                        || !next.sourceType().equals(entry.sourceType())
                        || !Objects.equals(next.groupId(), entry.groupId())
                        || !Objects.equals(next.membershipId(), entry.memberId())
                        || next.generation() != entry.generation()) {
                    checkpoint(
                            p,
                            entry,
                            FAILED,
                            "NEW_GRANT_CHANGED",
                            entry.revokeReceiptId(),
                            entry.newGrantId(),
                            null);
                    return;
                }
                var proof = tasks.confirmation(p, next.id(), 1, false);
                if (proof == null || !COMPLETE.equals(proof.status())) {
                    checkpoint(
                            p,
                            entry,
                            WAIT_NEW_CONFIRM,
                            proof != null && BLOCKED.equals(proof.status())
                                    ? "PROJECTION_BLOCKED"
                                    : "PROJECTION_PROCESSING",
                            entry.revokeReceiptId(),
                            next.id(),
                            null);
                    return;
                }
                checkpoint(
                        p,
                        entry,
                        COMPLETED,
                        null,
                        entry.revokeReceiptId(),
                        next.id(),
                        proof.operationId());
            }
            default -> throw error(VERSION_CONFLICT);
        }
    }

    private Detail detail(Partition p, Task task) {
        var entries = tasks.entries(p, task.id());
        var ids = entries.stream().map(Entry::newGrantId).filter(Objects::nonNull).toList();
        var current =
                ids.isEmpty()
                        ? Map.<String, Snapshot>of()
                        : snapshots.selected(p, ids).stream()
                                .collect(Collectors.toMap(Snapshot::id, r -> r));
        var items =
                entries.stream()
                        .map(
                                e ->
                                        new Item(
                                                e.id(),
                                                e.oldGrantId(),
                                                e.oldVersion(),
                                                e.memberId(),
                                                e.generation(),
                                                e.oldSourceId(),
                                                e.scope(),
                                                e.ruleJson() == null
                                                        ? null
                                                        : ScopeRules.decode(e.ruleJson()),
                                                e.scopeHash(),
                                                e.validFrom().toString(),
                                                e.validTo().toString(),
                                                e.newSourceId(),
                                                e.stage().code(),
                                                e.version(),
                                                e.reason(),
                                                e.revokeReceiptId(),
                                                e.newGrantId() != null
                                                                && current.containsKey(
                                                                        e.newGrantId())
                                                        ? grantView(
                                                                current.get(e.newGrantId()).grant())
                                                        : null,
                                                e.newReceiptId(),
                                                e.updatedAt().toString(),
                                                e.sourceType(),
                                                e.groupId(),
                                                e.replacementRequestId()))
                        .toList();
        return new Detail(
                task.id(),
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                roleView(requireRole(p, task.oldRoleId())),
                roleView(requireRole(p, task.newRoleId())),
                task.state(),
                task.version(),
                task.commandId(),
                task.createdAt().toString(),
                task.updatedAt().toString(),
                items,
                entries.stream().filter(e -> e.stage() == COMPLETED).count(),
                entries.stream().filter(e -> e.stage() == FAILED).count(),
                entries.stream().filter(e -> e.stage() == CANCELLED).count(),
                entries.stream().filter(e -> !e.stage().terminal()).count());
    }

    private void checkpoint(
            Partition p,
            Entry e,
            Stage stage,
            String reason,
            String revoke,
            String next,
            String newReceipt) {
        one(tasks.checkpoint(p, e, stage, reason, revoke, next, newReceipt));
    }

    private Task requireTask(Partition p, String id, boolean lock) {
        var task = tasks.task(p, id, lock);
        if (task == null) throw error(ACCESS_DENIED);
        return task;
    }

    private RoleVersion requireRole(Partition p, String id) {
        var role = grants.role(p, id);
        if (role == null) throw error(ACCESS_DENIED);
        return role;
    }

    private String command(
            Delegation actor,
            Partition p,
            String operation,
            String command,
            String hash,
            Supplier<String> write) {
        String operator = actor.membershipId() + ":" + actor.generation();
        commands.reserveCommand(operator, p.tenantId(), operation, command, hash);
        var receipt = commands.lockCommand(operator, p.tenantId(), operation, command);
        if (receipt == null || !receipt.payloadHash().equals(hash)) throw error(COMMAND_CONFLICT);
        if (receipt.completed()) return receipt.resultRef();
        String result = write.get();
        one(commands.completeCommand(operator, p.tenantId(), operation, command, result));
        return result;
    }

    private void audit(
            Delegation actor,
            Partition p,
            String operation,
            String target,
            long version,
            String command) {
        one(
                commands.appendAudit(
                        id(),
                        actor.membershipId(),
                        p.tenantId(),
                        operation,
                        target,
                        version,
                        command));
    }

    /** DIRECT旧任务摘要保持不变；GROUP摘要额外固定组ID，不能换组继续执行。 */
    private static String originalHash(Snapshot row) {
        String original =
                AccessValues.hash(
                        row.tenantId(),
                        row.applicationId(),
                        row.environment(),
                        row.membershipId(),
                        row.generation(),
                        row.roleId(),
                        row.scope(),
                        row.sourceType(),
                        row.sourceId(),
                        row.validFrom(),
                        row.validTo(),
                        row.ruleJson(),
                        row.scopeHash());
        return GROUP_SOURCE.equals(row.sourceType())
                ? AccessValues.hash(original, row.groupId())
                : original;
    }

    private static String source(String old, String next) {
        return "role-migration:" + old + ":" + next;
    }

    private static Partition partition(String tenant, String app, String env) {
        var p = new Partition(tenant, app, env);
        AccessValues.partition(p);
        return p;
    }

    private static Instant instant(String value) {
        try {
            if (value == null || !value.endsWith("Z")) throw error(INVALID_ARGUMENT);
            return Instant.parse(value);
        } catch (DateTimeException e) {
            throw error(INVALID_ARGUMENT);
        }
    }

    private static AccessDtos.RoleView roleView(RoleVersion r) {
        return new AccessDtos.RoleView(
                r.id(), r.roleCode(), r.version(), AccessValues.read(r.capabilitiesJson()));
    }

    private static AccessDtos.GrantView grantView(Grant g) {
        return new AccessDtos.GrantView(
                g.id(),
                g.membershipId(),
                g.generation(),
                g.roleId(),
                g.scope(),
                g.sourceType(),
                g.sourceId(),
                g.validFrom().toString(),
                g.validTo().toString(),
                g.state().code(),
                g.version(),
                g.groupId());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static GovernanceException error(GovernanceException.Code code) {
        return new GovernanceException(code);
    }

    private static void one(int rows) {
        if (rows != 1) throw error(VERSION_CONFLICT);
    }
}
