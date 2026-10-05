package com.lrj.authz.admin.governance.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.access.application.PortalPermissions;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.web.GovernanceWeb;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 只读解释不会调用OA，普通本人查询和显式管理诊断是两个授权边界。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.presentation.enabled"
        },
        havingValue = "true")
@RequestMapping("/api/governance/v1")
public class PortalPermissionController {
    private final PortalPermissions permissions;

    /** 配置由私密文件的启动快照装配，不由请求选择。 */
    public PortalPermissionController(
            GovernanceRuntime runtime, GovernanceAdminConfiguration.Settings settings) {
        permissions = runtime.portalPermissions(settings.portalDiagnostics());
    }

    /** 分区和候选严格解析；他人影响必须拥有独立诊断资格，403不表示零影响。 */
    @PostMapping(value = "/access/catalog-impact", consumes = "application/json")
    public JsonNode catalogImpact(
            @AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        var input =
                com.lrj.authz.governance.shared.web.CatalogImpactWeb.read(request.getInputStream());
        return GovernanceWeb.body(
                permissions.catalogImpact(
                        login,
                        new Partition(input.tenantId(), input.applicationId(), input.environment()),
                        input.manifest(),
                        input.cursor()));
    }

    /** 本人当前代际的有界授权来源，不暴露其他员工。 */
    @GetMapping("/me/permissions")
    public JsonNode mine(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        return GovernanceWeb.body(permissions.mine(login, new Partition(tenant, app, env), after));
    }

    /** 独立诊断授权加当前管理委派共同保护他人授权详情。 */
    @GetMapping("/access/explanations")
    public JsonNode explain(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam("grant_id") String grant) {
        return GovernanceWeb.body(
                permissions.explain(login, new Partition(tenant, app, env), grant));
    }

    /** 追加事件只读页，分区外事件始终不返回。 */
    @GetMapping("/access/audit")
    public JsonNode audit(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        return GovernanceWeb.body(permissions.audit(login, new Partition(tenant, app, env), after));
    }
}
