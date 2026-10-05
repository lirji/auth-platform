package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.PortalInvitations;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import com.lrj.authz.governance.web.*;
import com.lrj.authz.protocol.PortalInvitationDtos.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 门户邀请的管理路由沿用已绑定Bearer链；仅精确accept路由可接受未绑定身份。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.invitations.enabled"
        },
        havingValue = "true")
@RequestMapping("/api/governance/v1/portal-invitations")
public class PortalInvitationController {
    private final PortalInvitations invitations;

    /** 宿主配置固定可邀请范围和发行方，不从请求体获取。 */
    public PortalInvitationController(
            GovernanceRuntime runtime, GovernanceAdminConfiguration.Settings settings) {
        invitations =
                runtime.portalInvitations(
                        settings.portalInvitations(),
                        settings.invitationAuthority().orElseThrow().issuer());
    }

    /** 每次重新查询本人显式邀请资格。 */
    @GetMapping("/authority")
    public JsonNode authority(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env) {
        return GovernanceWeb.body(invitations.authority(login, new Partition(tenant, app, env)));
    }

    /** 有界分页仅返回自己的邀请，证明及摘要永不返回。 */
    @GetMapping
    public JsonNode list(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        return GovernanceWeb.body(invitations.list(login, new Partition(tenant, app, env), after));
    }

    /** 证明只传入领域哈希用例，响应不含原文。 */
    @PostMapping(consumes = "application/json")
    public JsonNode issue(@AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), Issue.class);
        return GovernanceWeb.body(
                invitations.issue(
                        login,
                        new Partition(r.tenantId(), r.applicationId(), r.environment()),
                        r,
                        AccessWeb.instant(r.expiresAt()),
                        AccessWeb.instant(r.membershipValidTo())));
    }

    /** 已接受邀请不能在此撤回成员或权限。 */
    @PostMapping(value = "/{id}/revoke", consumes = "application/json")
    public JsonNode revoke(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), Revoke.class);
        return GovernanceWeb.body(
                invitations.revoke(
                        login,
                        new Partition(r.tenantId(), r.applicationId(), r.environment()),
                        id,
                        r));
    }
}
