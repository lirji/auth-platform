package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.DirectoryModels.*;
import com.lrj.authz.governance.domain.IdentityModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.DirectoryEvents;
import com.lrj.authz.protocol.DirectoryEvents.Event;
import com.lrj.authz.protocol.DirectoryEvents.DirectoryAggregateType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 受控目录消费边界；网络拉取在事务之外，身份/投影/回执/审计及连续检查点一起提交。 */
public final class DirectoryGovernance {
    private static final int MAX_PENDING_WINDOW = 20_000;
    private static final int MAX_SNAPSHOT_EVENTS = 10_000;
    private final DirectoryMapper directory;
    private final IdentityMapper identity;
    private final InvitationMapper current;
    private final TransactionTemplate transaction;
    private final TransactionTemplate conflictTransaction;

    /** 两个独立事务模板由同一治理 Runtime 装配；冲突证据不能随业务失败一起回滚。 */
    public DirectoryGovernance(DirectoryMapper directory, IdentityMapper identity, InvitationMapper current,
                               TransactionTemplate transaction, TransactionTemplate conflictTransaction) {
        this.directory = directory; this.identity = identity; this.current = current;
        this.transaction = transaction; this.conflictTransaction = conflictTransaction;
    }
    /** 返回连续检查点，不是本次最大观察序号或业务访问许可。 */
    public record Receipt(long lastSequence, String lastFingerprint, boolean replay) {}

    /** 操作者来自运维私密配置；首次登记与审计同事务，重放不重复审计。 */
    public Source register(DirectoryAuthority authority, String operatorRef) {
        BootstrapCommand.bounded(operatorRef, 160);
        return transaction.execute(status -> {
            if (directory.lockTenant(authority.tenantId()) == null) { throw new GovernanceException(MEMBERSHIP_UNAVAILABLE); }
            int inserted = directory.insertSource(authority);
            Source source = directory.lockSource(authority.id());
            if (!authority.matches(source)) { throw new GovernanceException(BINDING_CONFLICT); }
            if (inserted == 1) { one(identity.appendAudit(id(), operatorRef, authority.tenantId(), "REGISTER_DIRECTORY_SOURCE", authority.id(), 1, authority.id())); }
            return source;
        });
    }
    /** 租户级目录时区只能由受控来源运维设置，应用管理员不能改变其他应用的任职日期语义。 */
    public void configureBusinessZone(DirectoryAuthority authority, String zone, String operatorRef, String commandId) {
        BootstrapCommand.bounded(operatorRef, 160); BootstrapCommand.uuid(commandId);
        if (zone == null || !java.time.ZoneId.getAvailableZoneIds().contains(zone)) { throw new GovernanceException(INVALID_ARGUMENT); }
        String hash = AccessValues.hash(authority, zone);
        transaction.executeWithoutResult(status -> {
            requireSource(authority);
            identity.reserveCommand(operatorRef, authority.tenantId(), "DIRECTORY_CLOCK", commandId, hash);
            var receipt = identity.lockCommand(operatorRef, authority.tenantId(), "DIRECTORY_CLOCK", commandId);
            if (receipt == null || !hash.equals(receipt.payloadHash())) { throw new GovernanceException(COMMAND_CONFLICT); }
            if (receipt.completed()) { return; }
            one(directory.configureClock(authority.id(), zone));
            one(identity.appendAudit(id(), operatorRef, authority.tenantId(), "DIRECTORY_CLOCK", authority.id(), 1, commandId));
            one(identity.completeCommand(operatorRef, authority.tenantId(), "DIRECTORY_CLOCK", commandId, authority.id()));
        });
    }
    /** 只读检查点仍核对不可变配置，隔离中的来源不得继续正常导入。 */
    public Receipt checkpoint(DirectoryAuthority authority) {
        return transaction.execute(status -> receipt(requireSource(authority), false));
    }
    /** 运维状态可观察隔离事实，不绕过隔离恢复消费；仍必须精确匹配已登记权限范围。 */
    public Inspection inspect(DirectoryAuthority authority) {
        return transaction.execute(status -> {
            Source source = directory.lockSource(authority.id());
            if (!authority.matches(source)) { throw new GovernanceException(BINDING_CONFLICT); }
            return new Inspection(source.lastSequence(), source.quarantined());
        });
    }
    /** 不输出消息正文或身份数据，仅暴露本地持久化进度与冲突隔离标记。 */
    public record Inspection(long lastSequence, boolean quarantined) {}
    /** 精确来源认证由受控拉取配置保证；消息中的来源事实只能核对，不能选择权限范围。 */
    public Receipt accept(DirectoryAuthority authority, Event event) {
        if (event == null || !authority.source().equals(event.source()) || !authority.environment().equals(event.environment())
                || !authority.sourceTenantRef().equals(event.sourceTenantRef())) { throw new GovernanceException(INVALID_ARGUMENT); }
        String fingerprint = DirectoryEvents.eventFingerprint(event);
        try {
            return transaction.execute(status -> consume(authority, event, fingerprint, status));
        } catch (Conflict failure) {
            conflictTransaction.executeWithoutResult(status -> {
                Source source = directory.lockSource(authority.id());
                if (!authority.matches(source)) { throw new GovernanceException(BINDING_CONFLICT); }
                one(directory.recordConflict(source.id(), event.eventId(), fingerprint, failure.reason.code));
                one(directory.quarantine(source.id()));
            });
            throw new GovernanceException(BINDING_CONFLICT);
        }
    }
    private Receipt consume(DirectoryAuthority authority, Event event, String fingerprint, org.springframework.transaction.TransactionStatus transactionStatus) {
        Source source = requireSource(authority);
        Inbox prior = directory.event(source.id(), event.eventId());
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)) { throw conflict(Reason.EVENT_CHANGED); }
            return receipt(source, true);
        }
        if (directory.sequence(source.id(), event.partitionSequence()) != null) { throw conflict(Reason.SEQUENCE_CHANGED); }
        if (event.partitionSequence() <= source.lastSequence() || event.partitionSequence() - source.lastSequence() > MAX_PENDING_WINDOW) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
        String versionHash = directory.versionHash(source.id(), event.aggregateType().code(), event.aggregateId(), event.aggregateVersion());
        if (versionHash != null && !versionHash.equals(event.payloadHash())) { throw conflict(Reason.VERSION_CHANGED); }
        if (versionHash == null) { one(directory.insertVersion(source.id(), event)); }
        Snapshot snapshot = event.snapshotId() == null ? null : directory.snapshot(source.id(), event.snapshotId());
        if (snapshot != null && (event.partitionSequence() < snapshot.startSequence() || event.partitionSequence() > snapshot.endSequence())) {
            throw conflict(Reason.SNAPSHOT_CHANGED);
        }
        if (snapshot != null && (event.aggregateType() == DirectoryAggregateType.EMPLOYEE || event.aggregateType() == DirectoryAggregateType.ORG)
                && (event.partitionSequence() == snapshot.startSequence() || event.partitionSequence() == snapshot.endSequence())) {
            throw conflict(Reason.SNAPSHOT_CHANGED);
        }
        String target = source.id(); long targetVersion = event.partitionSequence();ChangeEvidence change=null;
        if (event.aggregateType() == DirectoryAggregateType.EMPLOYEE || event.aggregateType() == DirectoryAggregateType.ORG) {
            Entry previous = directory.entry(source.id(), event.aggregateType().code(), event.aggregateId());
            // 沿员工处理的主体→成员锁顺序取前值，避免并发手工暂停被误记成目录变更。
            Membership beforeMember=null;
            if(previous!=null&&previous.membershipId()!=null){directory.lockHuman(previous.principalId());beforeMember=current.lockMember(previous.principalId(),authority.tenantId());}
            String before=PersonnelFacts.snapshot(previous,beforeMember);
            boolean applied=previous==null||event.aggregateVersion()>previous.aggregateVersion();
            if (previous == null || event.aggregateVersion() > previous.aggregateVersion()) {
                if (event.aggregateType() == DirectoryAggregateType.EMPLOYEE) {
                    Membership member = employee(authority, event, previous, transactionStatus);
                    target = member.id(); targetVersion = member.version();
                    one(directory.saveEntry(new Entry(source.id(), source.tenantId(), event.aggregateType().code(), event.aggregateId(),
                            event.aggregateVersion(), event.payloadHash(), DirectoryJson.payload(event.payload()), member.principalId(), member.id(), event.payload().employee().userId())));
                } else {
                    String parent = event.payload().organization().parentId();
                    if (parent != null && directory.invalidParent(source.id(), event.aggregateId(), parent)) { throw conflict(Reason.ORG_CYCLE); }
                    one(directory.saveEntry(new Entry(source.id(), source.tenantId(), event.aggregateType().code(), event.aggregateId(),
                            event.aggregateVersion(), event.payloadHash(), DirectoryJson.payload(event.payload()), null, null, null)));
                }
            }
            Entry after=directory.entry(source.id(),event.aggregateType().code(),event.aggregateId());
            change=new ChangeEvidence(source.id(),event.eventId(),source.tenantId(),event.partitionSequence(),event.aggregateType().code(),event.aggregateId(),event.aggregateVersion(),fingerprint,after.membershipId(),applied?"APPLIED":"OBSOLETE",before,
                    PersonnelFacts.snapshot(after,after.membershipId()==null?null:identity.membership(after.membershipId())),event.occurredAt());
        } else { control(source, event); }
        one(directory.insertInbox(source.id(), event, fingerprint, event.aggregateType() != DirectoryAggregateType.SNAPSHOT_END));
        if(change!=null)one(directory.appendChange(change));
        if (event.snapshotId() != null) { completeSnapshot(source, event.snapshotId()); }
        one(identity.appendAudit(id(), "directory/" + source.id(), source.tenantId(), "DIRECTORY_CONSUME", target, targetVersion, event.eventId()));
        return advance(source);
    }

    private Membership employee(DirectoryAuthority authority, Event event, Entry previous, org.springframework.transaction.TransactionStatus transactionStatus) {
        var employee = event.payload().employee();
        LegacyBinding legacy = identity.legacyBinding(authority.legacySystem(), authority.sourceTenantRef(), employee.employeeId());
        if (previous != null && (legacy == null || !previous.membershipId().equals(legacy.membershipId())
                || (previous.loginSubject() != null && !previous.loginSubject().equals(employee.userId())))) { throw conflict(Reason.IDENTITY_CHANGED); }
        LoginIdentity login = employee.userId() == null ? null : identity.loginIdentity(authority.issuer(), employee.userId());
        String principalId = legacy != null ? legacy.principalId() : login != null ? login.principalId() : null;
        Principal principal;
        if (principalId == null) {
            // 不同企业可并发首次遇到同一登录身份。唯一键获胜后复用获胜主体，
            // 只回滚本事务尚未绑定的候选主体，不能因此隔离另一个合法企业或留下孤儿主体。
            Object savepoint = transactionStatus.createSavepoint();
            principal = new Principal(id(), PrincipalKind.HUMAN, GlobalStatus.ACTIVE, 1); one(identity.insertPrincipal(principal));
            if (employee.userId() != null) {
                identity.insertLoginIdentity(new LoginIdentity(authority.issuer(), employee.userId(), principal.id()));
                LoginIdentity winner = identity.loginIdentity(authority.issuer(), employee.userId());
                if (winner == null) { throw conflict(Reason.IDENTITY_CHANGED); }
                if (!principal.id().equals(winner.principalId())) {
                    transactionStatus.rollbackToSavepoint(savepoint);
                    principal = current.lockActiveHuman(winner.principalId());
                    if (principal == null) { throw conflict(Reason.PRINCIPAL_UNAVAILABLE); }
                }
            }
            transactionStatus.releaseSavepoint(savepoint);
        } else {
            // 已有权威绑定的停用员工仍需处理后续离职；只更新企业事实，不恢复全局状态。
            principal = legacy != null ? directory.lockHuman(principalId) : current.lockActiveHuman(principalId);
            if (principal == null) { throw conflict(Reason.PRINCIPAL_UNAVAILABLE); }
        }
        if (employee.userId() != null) {
            LoginIdentity expected = new LoginIdentity(authority.issuer(), employee.userId(), principal.id());
            identity.insertLoginIdentity(expected);
            if (!expected.equals(identity.loginIdentity(authority.issuer(), employee.userId()))) { throw conflict(Reason.IDENTITY_CHANGED); }
        }
        Membership member = current.lockMember(principal.id(), authority.tenantId());
        boolean left = MemberStatus.LEFT.code().equals(employee.status());
        if (member == null) {
            member = new Membership(id(), authority.tenantId(), principal.id(), MemberKind.EMPLOYEE,
                    left ? MemberStatus.LEFT : MemberStatus.ACTIVE, 1, 1, current.now(), null, null);
            if (identity.insertMembership(member) != 1) { throw conflict(Reason.MEMBER_CHANGED); }
        } else {
            if (member.memberKind() != MemberKind.EMPLOYEE || (legacy != null && !legacy.membershipId().equals(member.id()))) { throw conflict(Reason.MEMBER_CHANGED); }
            String owner = directory.employeeForMember(authority.id(), member.id());
            if (owner != null && !owner.equals(employee.employeeId())) { throw conflict(Reason.MEMBER_CHANGED); }
            // 手工暂停高于目录生命周期，来源事实可以更新，但暂停绝不被 LEFT/ACTIVE 转换绕过。
            MemberStatus desired = left ? MemberStatus.LEFT : MemberStatus.ACTIVE;
            if (member.status() != MemberStatus.SUSPENDED && member.status() != desired) {
                boolean rejoin = member.status() == MemberStatus.LEFT;
                if (member.version() == Long.MAX_VALUE || (rejoin && member.generation() == Long.MAX_VALUE)) { throw conflict(Reason.VERSION_OVERFLOW); }
                one(directory.changeEmployee(member.id(), member.tenantId(), member.version(), member.status().code(), desired.code(), rejoin));
                member = identity.membership(member.id());
            }
        }
        LegacyBinding expected = new LegacyBinding(authority.legacySystem(), authority.sourceTenantRef(), employee.employeeId(),
                principal.id(), member.id(), authority.tenantId());
        identity.insertLegacyBinding(expected);
        if (!expected.equals(identity.legacyBinding(expected.sourceSystem(), expected.sourceTenantRef(), expected.sourceSubjectRef()))) { throw conflict(Reason.IDENTITY_CHANGED); }
        return member;
    }
    private void control(Source source, Event event) {
        var claim = event.payload().snapshot();
        if (claim.expectedCount() > MAX_SNAPSHOT_EVENTS) { throw new GovernanceException(INVALID_ARGUMENT); }
        Snapshot existing = directory.snapshot(source.id(), event.snapshotId());
        boolean begin = event.aggregateType() == DirectoryAggregateType.SNAPSHOT_BEGIN;
        if (existing == null) {
            one(directory.insertSnapshot(new Snapshot(source.id(), event.snapshotId(), claim.startSequence(), claim.endSequence(),
                    claim.expectedCount(), claim.contentHash(), begin, !begin, false)));
        } else {
            if (existing.startSequence() != claim.startSequence() || existing.endSequence() != claim.endSequence()
                    || existing.expectedCount() != claim.expectedCount() || !existing.contentHash().equals(claim.contentHash())) { throw conflict(Reason.SNAPSHOT_CHANGED); }
            one(directory.seenSnapshot(source.id(), event.snapshotId(), begin));
        }
        if (directory.outsideSnapshot(source.id(), event.snapshotId(), claim.startSequence(), claim.endSequence())) { throw conflict(Reason.SNAPSHOT_CHANGED); }
    }
    private void completeSnapshot(Source source, String id) {
        Snapshot snapshot = directory.snapshot(source.id(), id);
        if (snapshot == null || snapshot.complete() || !snapshot.beginSeen() || !snapshot.endSeen()) { return; }
        var rows = directory.snapshotEvents(source.id(), id, snapshot.startSequence(), snapshot.endSequence());
        if (rows.size() < snapshot.expectedCount()) { return; }
        if (rows.size() != snapshot.expectedCount()) { throw conflict(Reason.SNAPSHOT_CHANGED); }
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JVM 缺少 SHA-256", impossible); }
        long sequence = snapshot.startSequence();
        for (Inbox row : rows) {
            if (row.partitionSequence() != ++sequence || !Objects.equals(row.snapshotId(), id) || !row.processed()) { throw conflict(Reason.SNAPSHOT_CHANGED); }
            digest.update(HexFormat.of().parseHex(row.fingerprint()));
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(snapshot.contentHash())) { throw conflict(Reason.SNAPSHOT_CHANGED); }
        one(directory.completeSnapshot(source.id(), id)); one(directory.completeSnapshotEnd(source.id(), id, snapshot.endSequence()));
    }
    private Receipt advance(Source source) {
        long sequence = source.lastSequence(); String fingerprint = source.lastFingerprint();
        for (Inbox row : directory.following(source.id(), sequence)) {
            if (sequence == Long.MAX_VALUE || row.partitionSequence() != sequence + 1 || !row.processed()) { break; }
            sequence = row.partitionSequence(); fingerprint = row.fingerprint();
        }
        if (sequence != source.lastSequence()) { one(directory.checkpoint(source.id(), source.lastSequence(), sequence, fingerprint)); }
        return new Receipt(sequence, fingerprint, false);
    }
    private Source requireSource(DirectoryAuthority authority) {
        Source source = directory.lockSource(authority.id());
        if (!authority.matches(source)) { throw new GovernanceException(BINDING_CONFLICT); }
        if (source.quarantined()) { throw new GovernanceException(BINDING_CONFLICT); }
        if (directory.lockTenant(source.tenantId()) == null) { throw new GovernanceException(MEMBERSHIP_UNAVAILABLE); }
        return source;
    }
    private static Receipt receipt(Source source, boolean replay) { return new Receipt(source.lastSequence(), source.lastFingerprint(), replay); }
    private static void one(int affected) { if (affected != 1) { throw new GovernanceException(VERSION_CONFLICT); } }
    private static String id() { return UUID.randomUUID().toString(); }
    private enum Reason {
        EVENT_CHANGED("EVENT_CHANGED"), SEQUENCE_CHANGED("SEQUENCE_CHANGED"), VERSION_CHANGED("VERSION_CHANGED"),
        IDENTITY_CHANGED("IDENTITY_CHANGED"), PRINCIPAL_UNAVAILABLE("PRINCIPAL_UNAVAILABLE"), MEMBER_CHANGED("MEMBER_CHANGED"),
        VERSION_OVERFLOW("VERSION_OVERFLOW"), ORG_CYCLE("ORG_CYCLE"), SNAPSHOT_CHANGED("SNAPSHOT_CHANGED");
        private final String code; Reason(String code) { this.code = code; }
    }
    private static Conflict conflict(Reason reason) { return new Conflict(reason); }
    private static final class Conflict extends RuntimeException {
        private final Reason reason; private Conflict(Reason reason) { super(reason.code); this.reason = reason; }
    }
}
