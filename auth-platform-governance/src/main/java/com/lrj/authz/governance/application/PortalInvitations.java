package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.IdentityModels.MemberKind;
import com.lrj.authz.governance.domain.InvitationModels.Invitation;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.PortalInvitationDtos.*;
import com.lrj.authz.protocol.RequestDtos.Page;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 人类门户管理适配器：独立邀请白名单、当前委派、员工负责人三者同时成立。 */
public final class PortalInvitations {
    private final IdentityGovernance identity;
    private final AccessManagement access;
    private final AccessMapper partitions;
    private final IdentityMapper identities;
    private final PortalMapper mapper;
    private final InvitationMapper sponsorLocks;
    private final InvitationGovernance invitations;
    private final TransactionTemplate transaction;
    private final List<PortalInvitationAuthority> authorities;
    private final String issuer;
    /** 权限快照只由部署配置装配；请求不能注入Authority。 */
    public PortalInvitations(IdentityGovernance identity, AccessManagement access, AccessMapper partitions, IdentityMapper identities,
                             PortalMapper mapper, InvitationMapper sponsorLocks, InvitationGovernance invitations, TransactionTemplate transaction,
                             List<PortalInvitationAuthority> authorities, String issuer) {
        this.identity=identity; this.access=access; this.partitions=partitions; this.identities=identities;
        this.mapper=mapper; this.sponsorLocks=sponsorLocks; this.invitations=invitations; this.transaction=transaction; this.authorities=List.copyOf(authorities); this.issuer=issuer;
    }
    /** 无配置或失去当前代际/管理资格时拒绝，不能仅靠页面隐藏。 */
    public Authority authority(VerifiedLogin login, Partition p) {
        var allowed=allowed(login,p); return new Authority(issuer,allowed.maxInvitationSeconds(),allowed.maxMembershipSeconds());
    }
    /** 页查询绑定当前操作者、员工负责人及完整租户，最多100项。 */
    public Page<View> list(VerifiedLogin login, Partition p, String after) {
        var allowed=allowed(login,p); if(after!=null && !after.isEmpty()) BootstrapCommand.uuid(after);
        var rows=mapper.invitations(authority(allowed),after==null?"":after);
        return new Page<>(rows.subList(0,Math.min(100,rows.size())),rows.size()>100?rows.get(99).id():null);
    }
    /** 分区锁与现有管理写入串行化，再校验期限；领域事务保存摘要和审计。 */
    public View issue(VerifiedLogin login, Partition p, Issue input, Instant expires, Instant validTo) {
        return transaction.execute(status -> {
            lock(p); var allowed=lockedAuthority(login,p); Instant now=partitions.now();
            if(expires==null || validTo==null || expires.isAfter(now.plusSeconds(allowed.maxInvitationSeconds()))
                    || validTo.isAfter(now.plusSeconds(allowed.maxMembershipSeconds()))) throw new GovernanceException(INVALID_ARGUMENT);
            MemberKind kind;
            try { kind=MemberKind.valueOf(input.memberKind()); } catch(IllegalArgumentException | NullPointerException failure) { throw new GovernanceException(INVALID_ARGUMENT); }
            return view(invitations.issue(new InvitationCommands.Issue(input.commandId(),input.invitationId(),authority(allowed),issuer,input.targetSubject(),
                    kind,InvitationCommands.tokenHash(input.token()),expires,validTo,input.reason())));
        });
    }
    /** 撤销同样复核当前委派；已接受邀请的成员生命周期须走原专用命令。 */
    public View revoke(VerifiedLogin login, Partition p, String id, Revoke input) {
        return transaction.execute(status -> { lock(p); var allowed=lockedAuthority(login,p);
            return view(invitations.revoke(new InvitationCommands.Revoke(input.commandId(),id,authority(allowed),input.expectedVersion(),input.reason()))); });
    }
    private PortalInvitationAuthority lockedAuthority(VerifiedLogin login, Partition p) {
        var allowed=allowed(login,p);
        // 在发邀请和撤销事务中锁住负责人/主体/企业，防止资格校验后并发停用或换代。
        var sponsor=sponsorLocks.lockSponsor(allowed.membershipId(),p.tenantId());
        if(sponsor==null || sponsor.generation()!=allowed.generation()) throw new GovernanceException(ACCESS_DENIED);
        return allowed;
    }
    private void lock(Partition p) { AccessValues.partition(p); if(!Boolean.TRUE.equals(partitions.lockPartition(p))) throw new GovernanceException(ACCESS_DENIED); }
    private PortalInvitationAuthority allowed(VerifiedLogin login, Partition p) {
        var member=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null); access.authority(login,p);
        if(identities.membership(member.membershipId()).memberKind()!=MemberKind.EMPLOYEE) throw new GovernanceException(ACCESS_DENIED);
        return authorities.stream().filter(a -> a.partition().equals(p) && a.membershipId().equals(member.membershipId()) && a.generation()==member.membershipGeneration())
                .findFirst().orElseThrow(() -> new GovernanceException(ACCESS_DENIED));
    }
    private InvitationCommands.Authority authority(PortalInvitationAuthority a) {
        return new InvitationCommands.Authority("portal-invite:"+AccessValues.hash(a.partition(),a.membershipId(),a.generation()),a.partition().tenantId(),a.membershipId());
    }
    private static View view(Invitation i) { return new View(i.id(),i.targetIssuer(),i.targetSubject(),i.memberKind().code(),i.expiresAt().toString(),i.membershipValidTo().toString(),i.state().code(),i.version()); }
}
