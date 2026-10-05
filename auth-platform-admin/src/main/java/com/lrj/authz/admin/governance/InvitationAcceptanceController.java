package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.identity.invitation.application.InvitationGovernance;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.GovernanceDtos;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/** 只接受已验证身份与单次邀请的交集；请求不能指定主体、企业、类型或角色。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        prefix = "authz.governance",
        name = {"enabled", "invitations.enabled"},
        havingValue = "true")
public class InvitationAcceptanceController {
    private final InvitationGovernance invitations;

    /** 复用治理专用事务和当前状态读取。 */
    public InvitationAcceptanceController(GovernanceRuntime runtime) {
        this.invitations = runtime.invitations();
    }

    /** 外部凭据和邀请原文都不回显；响应只是身份成员确认，不是业务授权。 */
    @PostMapping("/api/governance/v1/invitations/accept")
    public JsonNode accept(@AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        if (request.getContentType() == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(
                        MediaType.parseMediaType(request.getContentType()))) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        var input = GovernanceWeb.readInvitation(request.getInputStream());
        var result = invitations.accept(input.invitationId(), input.token(), login);
        return GovernanceWeb.body(
                new GovernanceDtos.InvitationAcceptance(
                        result.invitationId(),
                        result.membershipId(),
                        result.membershipGeneration(),
                        result.membershipStatus(),
                        UUID.randomUUID().toString()));
    }
}
