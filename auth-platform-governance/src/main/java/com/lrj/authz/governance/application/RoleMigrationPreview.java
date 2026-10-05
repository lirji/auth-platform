package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;
import static com.lrj.authz.governance.domain.RoleMigrationTaskModels.*;
import static com.lrj.authz.protocol.RoleMigrationDtos.Exclusion.*;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.RoleMigrationModels.Snapshot;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.RequestDtos.Page;
import com.lrj.authz.protocol.RoleMigrationDtos.*;
import com.lrj.authz.protocol.ScopeDtos.Rule;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 固定角色版本迁移的有界只读预览；永远不修改Grant、来源或投影。 */
public final class RoleMigrationPreview {
    public static final int MAX_SELECTED = 50;
    private static final int PAGE_SIZE = 20;
    private static final String CONTINUITY = "REVOKE_CONFIRM_THEN_GRANT";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String SCOPED = "SCOPED";
    private final AccessManagement access;
    private final AccessRequests requests;
    private final PortalManagement portal;
    private final AccessMapper roles;
    private final RoleMigrationMapper mapper;
    private final TransactionTemplate read;

    /** 复用本服务主库事务并显式只读，未来执行器不能复用此入口做写入。 */
    public RoleMigrationPreview(
            AccessManagement access,
            PortalManagement portal,
            AccessMapper roles,
            RoleMigrationMapper mapper,
            TransactionTemplate transaction,
            AccessRequests requests) {
        this.requests = requests;
        this.access = access;
        this.portal = portal;
        this.roles = roles;
        this.mapper = mapper;
        read = new TransactionTemplate(transaction.getTransactionManager());
        read.setReadOnly(true);
        read.setTimeout(5);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 只返回旧角色真实引用页，完整集合和迁移对象由管理员明确选择。 */
    public Page<com.lrj.authz.protocol.AccessDtos.GrantView> candidates(
            VerifiedLogin login, Partition p, String role, String after) {
        BootstrapCommand.uuid(role);
        if (after != null) BootstrapCommand.uuid(after);
        return read.execute(
                status -> {
                    var before = portal.publishedCatalog(login, p);
                    requireRole(p, role);
                    var rows = mapper.candidates(p, role, after == null ? "" : after);
                    var afterView = portal.publishedCatalog(login, p);
                    if (!before.viewHash().equals(afterView.viewHash()))
                        throw new GovernanceException(VERSION_CONFLICT);
                    return new Page<>(
                            rows.stream()
                                    .limit(PAGE_SIZE)
                                    .map(RoleMigrationPreview::grantView)
                                    .toList(),
                            rows.size() > PAGE_SIZE ? rows.get(PAGE_SIZE - 1).id() : null);
                });
    }

    /** 每次使用当前资格及两份独立SQL快照，摘要只便于核对，不是执行票据。 */
    public Preview preview(VerifiedLogin login, PreviewRequest input) {
        if (input == null
                || input.grantIds() == null
                || input.grantIds().isEmpty()
                || input.grantIds().size() > MAX_SELECTED)
            throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.oldRoleId());
        BootstrapCommand.uuid(input.newRoleId());
        var ids = new TreeSet<String>();
        for (String id : input.grantIds()) {
            BootstrapCommand.uuid(id);
            if (!ids.add(id)) throw new GovernanceException(INVALID_ARGUMENT);
        }
        var selected = List.copyOf(ids);
        var p = new Partition(input.tenantId(), input.applicationId(), input.environment());
        AccessValues.partition(p);
        return read.execute(
                status -> {
                    var ceiling = access.authority(login, p);
                    var before = portal.publishedCatalog(login, p);
                    var old = requireRole(p, input.oldRoleId());
                    var next = requireRole(p, input.newRoleId());
                    if (!old.roleCode().equals(next.roleCode()) || next.version() <= old.version())
                        throw new GovernanceException(INVALID_ARGUMENT);
                    var rows = mapper.selected(p, selected);
                    if (rows.size() != selected.size())
                        throw new GovernanceException(ACCESS_DENIED);
                    var after = portal.publishedCatalog(login, p);
                    var current = mapper.selected(p, selected);
                    var latest = access.authority(login, p);
                    if (!before.viewHash().equals(after.viewHash())
                            || !rows.equals(current)
                            || !ceiling.equals(latest))
                        throw new GovernanceException(VERSION_CONFLICT);
                    var now = mapper.now();
                    var oldCaps = AccessValues.read(old.capabilitiesJson());
                    var newCaps = AccessValues.read(next.capabilitiesJson());
                    var added = newCaps.stream().filter(c -> !oldCaps.contains(c)).toList();
                    var removed = oldCaps.stream().filter(c -> !newCaps.contains(c)).toList();
                    var retained = oldCaps.stream().filter(newCaps::contains).toList();
                    var allowed = AccessValues.read(ceiling.capabilitiesJson());
                    var definitions =
                            after.capabilities().stream()
                                    .collect(
                                            java.util.stream.Collectors.toMap(
                                                    c -> c.code(), c -> c));
                    var items = new ArrayList<Item>();
                    for (var row : current) {
                        var reasons = new ArrayList<Exclusion>();
                        if (!row.roleId().equals(old.id())) reasons.add(GRANT_ROLE_MISMATCH);
                        if (!DIRECT_SOURCE.equals(row.sourceType())
                                && !GROUP_SOURCE.equals(row.sourceType())
                                && !com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE
                                        .equals(row.sourceType())) reasons.add(UNSUPPORTED_SOURCE);
                        if (row.state() != GrantState.ACTIVE) reasons.add(GRANT_NOT_ACTIVE);
                        if (now.isBefore(row.validFrom())) reasons.add(GRANT_NOT_CURRENT);
                        if (!now.isBefore(row.validTo())) reasons.add(GRANT_EXPIRED);
                        if (DIRECT_SOURCE.equals(row.sourceType())
                                || com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(
                                        row.sourceType())) {
                            // 最终评估时钟可能跨过成员期限；不能仅使用稍早快照中的布尔资格。
                            if (!row.memberActive()
                                    || row.memberValidFrom() == null
                                    || now.isBefore(row.memberValidFrom())
                                    || row.memberValidTo() != null
                                            && !now.isBefore(row.memberValidTo()))
                                reasons.add(Exclusion.MEMBERSHIP_UNAVAILABLE);
                            if (ceiling.membershipId().equals(row.membershipId()))
                                reasons.add(SELF_GRANT_DENIED);
                        }
                        if (GROUP_SOURCE.equals(row.sourceType())) {
                            var groupReason =
                                    access.groupMigrationExclusion(p, row.groupId(), ceiling);
                            if (!row.groupAvailable()) groupReason = GROUP_UNAVAILABLE;
                            if (groupReason != null) reasons.add(groupReason);
                        }
                        var approval =
                                com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(
                                                row.sourceType())
                                        ? requests.migrationApproval(p, row, next.id())
                                        : null;
                        if (com.lrj.authz.governance.domain.RequestModels.SOURCE_TYPE.equals(
                                        row.sourceType())
                                && approval == null) reasons.add(OA_APPROVAL_REQUIRED);
                        if (approval != null
                                && !approval.equals(requests.migrationApproval(p, row, next.id())))
                            throw new GovernanceException(VERSION_CONFLICT);
                        if (!allowed.containsAll(oldCaps) || !allowed.containsAll(newCaps))
                            reasons.add(MANAGEMENT_CEILING);
                        if (newCaps.stream()
                                .anyMatch(
                                        c ->
                                                !definitions.containsKey(c)
                                                        || definitions.get(c).disabled()))
                            reasons.add(CAPABILITY_UNAVAILABLE);
                        if (!added.isEmpty()) reasons.add(REQUIRES_SEPARATE_AUTHORIZATION);
                        if (!row.strictPartition()) reasons.add(STRICT_PARTITION_REQUIRED);
                        Duration remainingDuration =
                                Duration.between(
                                        now.isAfter(row.validFrom()) ? now : row.validFrom(),
                                        row.validTo());
                        long remaining = Math.max(0, remainingDuration.getSeconds());
                        if (remainingDuration.compareTo(
                                        Duration.ofSeconds(ceiling.maxDurationSeconds()))
                                > 0) reasons.add(DURATION_EXCEEDS_CEILING);
                        Rule rule = scope(row);
                        if (rule != null
                                && newCaps.stream()
                                        .anyMatch(
                                                c ->
                                                        !definitions.containsKey(c)
                                                                || !rule.resourceType()
                                                                        .equals(
                                                                                definitions
                                                                                        .get(c)
                                                                                        .resourceType())))
                            reasons.add(Exclusion.SCOPE_UNSUPPORTED);
                        items.add(
                                new Item(
                                        grantView(row.grant()),
                                        rule,
                                        row.scopeHash(),
                                        remaining,
                                        (approval == null
                                                ? "role-migration:" + row.id() + ":" + next.id()
                                                : approval.id()
                                                        + ":single:"
                                                        + approval.requestVersion()),
                                        reasons.isEmpty(),
                                        reasons,
                                        approval == null ? null : approval.id()));
                    }
                    long eligible = items.stream().filter(Item::eligible).count();
                    String hash =
                            AccessValues.hash(
                                    before.viewHash(),
                                    old.contentHash(),
                                    next.contentHash(),
                                    current.toString(),
                                    now.toString());
                    return new Preview(
                            p.tenantId(),
                            p.applicationId(),
                            p.environment(),
                            roleView(old),
                            roleView(next),
                            added,
                            removed,
                            retained,
                            now.toString(),
                            hash,
                            CONTINUITY,
                            UNKNOWN,
                            items,
                            eligible,
                            items.size() - eligible);
                });
    }

    /** 协议投影保留固定版本，应用层不依赖HTTP适配层。 */
    private static com.lrj.authz.protocol.AccessDtos.RoleView roleView(RoleVersion role) {
        return new com.lrj.authz.protocol.AccessDtos.RoleView(
                role.id(),
                role.roleCode(),
                role.version(),
                AccessValues.read(role.capabilitiesJson()));
    }

    /** 不输出图水位；来源和范围仍是当前主库真实字段。 */
    private static com.lrj.authz.protocol.AccessDtos.GrantView grantView(Grant g) {
        return new com.lrj.authz.protocol.AccessDtos.GrantView(
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

    /** 角色UUID只可在当前管理分区内读取，不能作为跨租户探测入口。 */
    private RoleVersion requireRole(Partition p, String id) {
        var role = roles.role(p, id);
        if (role == null) throw new GovernanceException(ACCESS_DENIED);
        return role;
    }

    /** 存储损坏必须报依赖错误，绝不能把未知或混合资源范围解释为全企业。 */
    private static Rule scope(Snapshot row) {
        if (!SCOPED.equals(row.scope())) {
            if (!"TENANT_ALL".equals(row.scope())
                    || row.ruleJson() != null
                    || row.scopeHash() != null)
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return null;
        }
        try {
            if (row.ruleJson() == null
                    || !AccessValues.hash(row.ruleJson()).equals(row.scopeHash()))
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return ScopeRules.decode(row.ruleJson());
        } catch (GovernanceException corrupt) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }
}
