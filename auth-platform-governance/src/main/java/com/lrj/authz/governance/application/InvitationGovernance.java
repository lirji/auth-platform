package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;
import static com.lrj.authz.governance.application.InvitationCommands.*;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.IdentityModels.*;
import com.lrj.authz.governance.domain.InvitationModels.*;
import com.lrj.authz.governance.persistence.*;

import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/** 单次邀请应用边界；网络身份验证在调用前完成，事务内仅执行治理库事实和审计。 */
public final class InvitationGovernance {
    private static final String ISSUE = "ISSUE_INVITATION";
    private static final String REVOKE = "REVOKE_INVITATION";
    private static final String ACCEPT = "ACCEPT_INVITATION";
    private final IdentityMapper identity;
    private final InvitationMapper invitations;
    private final TransactionTemplate transaction;

    /** 与身份/生命周期用例共享唯一治理 Runtime 事务。 */
    public InvitationGovernance(
            IdentityMapper identity,
            InvitationMapper invitations,
            TransactionTemplate transaction) {
        this.identity = identity;
        this.invitations = invitations;
        this.transaction = transaction;
    }

    /** 接受回执没有业务 ALLOW、角色或任何令牌。 */
    public record Acceptance(
            String invitationId,
            String membershipId,
            long membershipGeneration,
            String membershipStatus) {}

    /** 创建只能来自已解析的受控权限范围，负责人必须为同企业有效员工。 */
    public Invitation issue(Issue command) {
        return transaction.execute(
                status -> {
                    var a = command.authority();
                    boolean replay = reserve(a, ISSUE, command.commandId(), command.payloadHash());
                    if (replay) {
                        return owned(command.invitationId(), a);
                    }
                    sponsor(a.sponsorMembershipId(), a.tenantId());
                    if (!command.expiresAt().isAfter(invitations.now())) {
                        throw new GovernanceException(INVALID_ARGUMENT);
                    }
                    var invitation =
                            new Invitation(
                                    command.invitationId(),
                                    a.tenantId(),
                                    a.sponsorMembershipId(),
                                    a.operatorRef(),
                                    command.issuer(),
                                    command.subject(),
                                    command.memberKind(),
                                    command.tokenHash(),
                                    command.expiresAt(),
                                    command.membershipValidTo(),
                                    InvitationState.PENDING,
                                    1,
                                    null,
                                    null,
                                    null);
                    if (invitations.insert(invitation) != 1) {
                        throw new GovernanceException(BINDING_CONFLICT);
                    }
                    audit(
                            invitation,
                            a.operatorRef(),
                            ISSUE,
                            command.commandId(),
                            command.reason(),
                            "ABSENT",
                            InvitationState.PENDING,
                            0);
                    one(
                            identity.completeCommand(
                                    a.operatorRef(),
                                    a.tenantId(),
                                    ISSUE,
                                    command.commandId(),
                                    invitation.id()));
                    return invitation;
                });
    }

    /** 只撤销自己受控范围内的待接受邀请，不以撤销邀请替代成员停用。 */
    public Invitation revoke(Revoke command) {
        return transaction.execute(
                status -> {
                    var a = command.authority();
                    boolean replay = reserve(a, REVOKE, command.commandId(), command.payloadHash());
                    var current = owned(command.invitationId(), a);
                    if (replay) {
                        return current;
                    }
                    if (current.state() != InvitationState.PENDING
                            || current.version() != command.expectedVersion()) {
                        throw new GovernanceException(VERSION_CONFLICT);
                    }
                    one(invitations.revoke(current.id(), current.version()));
                    audit(
                            current,
                            a.operatorRef(),
                            REVOKE,
                            command.commandId(),
                            command.reason(),
                            current.state().code(),
                            InvitationState.REVOKED,
                            current.version());
                    one(
                            identity.completeCommand(
                                    a.operatorRef(),
                                    a.tenantId(),
                                    REVOKE,
                                    command.commandId(),
                                    current.id()));
                    return invitations.lock(current.id());
                });
    }

    /** 显式邀请和已验证的精确身份共同授权建成员；令牌本身不能将陌生人变成员工。 */
    public Acceptance accept(String invitationId, String token, VerifiedLogin login) {
        BootstrapCommand.uuid(invitationId);
        String hash = tokenHash(token);
        if (login == null) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        return transaction.execute(
                status -> {
                    Invitation invitation = invitations.lock(invitationId);
                    if (invitation == null
                            || !MessageDigest.isEqual(
                                    hash.getBytes(StandardCharsets.US_ASCII),
                                    invitation.tokenHash().getBytes(StandardCharsets.US_ASCII))
                            || !invitation.targetIssuer().equals(login.issuer())
                            || !invitation.targetSubject().equals(login.subject())) {
                        throw new GovernanceException(INVALID_CREDENTIAL);
                    }
                    if (invitation.state() == InvitationState.REVOKED) {
                        throw new GovernanceException(INVALID_CREDENTIAL);
                    }
                    if (invitation.state() == InvitationState.PENDING
                            && !invitation.expiresAt().isAfter(invitations.now())) {
                        throw new GovernanceException(INVALID_CREDENTIAL);
                    }
                    sponsor(invitation.sponsorMembershipId(), invitation.tenantId());
                    Principal principal = principal(login);
                    Membership existing =
                            invitations.lockMember(principal.id(), invitation.tenantId());
                    if (invitation.state() == InvitationState.ACCEPTED) {
                        return replay(invitation, existing);
                    }
                    Instant now = invitations.now();
                    Membership result;
                    if (existing == null) {
                        result =
                                new Membership(
                                        id(),
                                        invitation.tenantId(),
                                        principal.id(),
                                        invitation.memberKind(),
                                        MemberStatus.ACTIVE,
                                        1,
                                        1,
                                        now,
                                        invitation.membershipValidTo(),
                                        invitation.sponsorMembershipId());
                        if (identity.insertMembership(result) != 1) {
                            throw new GovernanceException(BINDING_CONFLICT);
                        }
                    } else {
                        if (existing.status() == MemberStatus.SUSPENDED) {
                            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
                        }
                        if (existing.status() != MemberStatus.LEFT
                                || existing.memberKind() != invitation.memberKind()) {
                            throw new GovernanceException(BINDING_CONFLICT);
                        }
                        if (existing.generation() == Long.MAX_VALUE
                                || existing.version() == Long.MAX_VALUE) {
                            throw new GovernanceException(VERSION_CONFLICT);
                        }
                        result =
                                new Membership(
                                        existing.id(),
                                        existing.tenantId(),
                                        existing.principalId(),
                                        existing.memberKind(),
                                        MemberStatus.ACTIVE,
                                        existing.generation() + 1,
                                        existing.version() + 1,
                                        now,
                                        invitation.membershipValidTo(),
                                        invitation.sponsorMembershipId());
                        one(invitations.rejoin(result));
                    }
                    one(
                            invitations.accept(
                                    invitation.id(),
                                    invitation.version(),
                                    result.id(),
                                    result.generation()));
                    audit(
                            invitation,
                            "principal/" + principal.id(),
                            ACCEPT,
                            invitation.id(),
                            "受邀身份验证通过",
                            invitation.state().code(),
                            InvitationState.ACCEPTED,
                            invitation.version());
                    return receipt(invitation, result);
                });
    }

    private Principal principal(VerifiedLogin login) {
        LoginIdentity binding = identity.loginIdentity(login.issuer(), login.subject());
        if (binding == null) {
            Principal created = new Principal(id(), PrincipalKind.HUMAN, GlobalStatus.ACTIVE, 1);
            one(identity.insertPrincipal(created));
            var expected = new LoginIdentity(login.issuer(), login.subject(), created.id());
            identity.insertLoginIdentity(expected);
            if (!expected.equals(identity.loginIdentity(login.issuer(), login.subject()))) {
                throw new GovernanceException(BINDING_CONFLICT);
            }
            return created;
        }
        Principal principal = invitations.lockActiveHuman(binding.principalId());
        if (principal == null) {
            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
        }
        return principal;
    }

    private Acceptance replay(Invitation invitation, Membership member) {
        if (member == null || !member.id().equals(invitation.acceptedMembershipId())) {
            throw new GovernanceException(BINDING_CONFLICT);
        }
        if (member.generation() != invitation.acceptedGeneration()) {
            throw new GovernanceException(GENERATION_MISMATCH);
        }
        Instant now = invitations.now();
        if (member.status() != MemberStatus.ACTIVE
                || member.validFrom().isAfter(now)
                || (member.validTo() != null && !member.validTo().isAfter(now))) {
            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
        }
        return receipt(invitation, member);
    }

    private static Acceptance receipt(Invitation invitation, Membership member) {
        return new Acceptance(
                invitation.id(), member.id(), member.generation(), member.status().code());
    }

    private void sponsor(String sponsorId, String tenantId) {
        Membership sponsor = invitations.lockSponsor(sponsorId, tenantId);
        if (sponsor == null
                || (sponsor.validTo() != null && !sponsor.validTo().isAfter(invitations.now()))) {
            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
        }
    }

    private Invitation owned(String id, Authority authority) {
        Invitation value = invitations.lock(id);
        if (value == null
                || !value.tenantId().equals(authority.tenantId())
                || !value.operatorRef().equals(authority.operatorRef())
                || !value.sponsorMembershipId().equals(authority.sponsorMembershipId())) {
            throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
        }
        return value;
    }

    private boolean reserve(Authority authority, String operation, String command, String hash) {
        identity.reserveCommand(
                authority.operatorRef(), authority.tenantId(), operation, command, hash);
        var row =
                identity.lockCommand(
                        authority.operatorRef(), authority.tenantId(), operation, command);
        if (row == null || !row.payloadHash().equals(hash)) {
            throw new GovernanceException(COMMAND_CONFLICT);
        }
        return row.completed();
    }

    private void audit(
            Invitation invitation,
            String actor,
            String operation,
            String command,
            String reason,
            String before,
            InvitationState after,
            long previousVersion) {
        String audit = id();
        one(
                identity.appendAudit(
                        audit,
                        actor,
                        invitation.tenantId(),
                        operation,
                        invitation.id(),
                        previousVersion + 1,
                        command));
        one(
                invitations.auditDetail(
                        audit, invitation.id(), reason, before, after.code(), previousVersion));
    }

    private static void one(int affected) {
        if (affected != 1) {
            throw new GovernanceException(VERSION_CONFLICT);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
