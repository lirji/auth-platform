package com.lrj.authz.governance.application;

import com.lrj.authz.governance.application.InvitationCommands.*;
import com.lrj.authz.governance.domain.IdentityModels.MemberKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 邀请证明必须唯一编码且不泄露，幂等摘要覆盖操作范围与有效期。 */
class InvitationCommandsTest {
    @Test void proofIsCanonicalAndRedacted() {
        String token = InvitationCommands.newToken();
        assertThat(token).hasSize(43);
        assertThat(InvitationCommands.tokenHash(token)).matches("[a-f0-9]{64}");
        for (String invalid : new String[]{"", token + "=", " ".repeat(43), "A".repeat(42) + "B"}) {
            assertThatThrownBy(() -> InvitationCommands.tokenHash(invalid)).isInstanceOf(GovernanceException.class);
        }
        var issue = issue(MemberKind.GUEST, Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(issue.toString()).doesNotContain(issue.tokenHash(), issue.subject());
        var request = new com.lrj.authz.protocol.GovernanceDtos.AcceptInvitationRequest(issue.invitationId(), token);
        assertThat(request.toString()).doesNotContain(token);
    }

    @Test void refusesEmployeeAndLossyTimestamp() {
        assertThatThrownBy(() -> issue(MemberKind.EMPLOYEE, Instant.parse("2030-01-01T00:00:00Z")))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> issue(MemberKind.GUEST, Instant.parse("2030-01-01T00:00:00.000000001Z")))
                .isInstanceOf(GovernanceException.class);
    }

    @Test void idempotencyBindsAuthorityAndExpiry() {
        var first = issue(MemberKind.PARTNER, Instant.parse("2030-01-01T00:00:00Z"));
        var changed = new Issue(first.commandId(), first.invitationId(), first.authority(), first.issuer(), first.subject(),
                first.memberKind(), first.tokenHash(), first.expiresAt().plusSeconds(1), first.membershipValidTo(), first.reason());
        assertThat(changed.payloadHash()).isNotEqualTo(first.payloadHash());
        var revoke = new Revoke(UUID.randomUUID().toString(), first.invitationId(), first.authority(), 1, "撤销邀请");
        var otherScope = new Authority("other", first.authority().tenantId(), first.authority().sponsorMembershipId());
        assertThat(new Revoke(revoke.commandId(), revoke.invitationId(), otherScope, 1, revoke.reason()).payloadHash())
                .isNotEqualTo(revoke.payloadHash());
    }

    private static Issue issue(MemberKind kind, Instant expiry) {
        return new Issue(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                new Authority("test", UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                "https://issuer.example", "exact-subject", kind, InvitationCommands.tokenHash(InvitationCommands.newToken()),
                expiry, Instant.parse("2031-01-01T00:00:00Z"), "显式邀请");
    }
}
