package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import com.lrj.authz.governance.application.IdentityGovernance;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.lrj.authz.governance.web.GovernanceWeb;
import com.lrj.authz.protocol.GovernanceDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 本人当前有效成员查询；每次验证当前身份/成员，不提供企业目录或业务角色。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
public class GovernanceMeController {
    private final IdentityGovernance identities;

    /** 只有显式启用的受控 Runtime 才能装配该 Controller。 */
    public GovernanceMeController(GovernanceRuntime runtime) { this.identities = runtime.identity(); }

    /** 必须提供单一 Access Bearer；请求中的 tenant/principal 参数不参与身份选取。 */
    @GetMapping("/api/governance/v1/me/memberships")
    public JsonNode memberships(@AuthenticationPrincipal VerifiedLogin login) {
        var members = identities.membershipsForLogin(login.issuer(), login.subject()).stream().map(member -> new GovernanceDtos.MembershipView(member.id(),
                member.tenantId(), member.memberKind().code(), member.generation(), member.version(), member.validFrom().toString(),
                member.validTo() == null ? null : member.validTo().toString())).toList();
        return GovernanceWeb.body(new GovernanceDtos.MembershipsResponse(members, UUID.randomUUID().toString()));
    }
}
