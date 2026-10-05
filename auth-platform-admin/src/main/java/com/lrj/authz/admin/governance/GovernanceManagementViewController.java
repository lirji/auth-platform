package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.portal.application.PortalManagement;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.web.GovernanceWeb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 门户管理读接口与原写接口使用同一当前委派，不接受浏览器操作者字段。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.access.enabled"},
        havingValue = "true")
@RequestMapping("/api/governance/v1/access")
public class GovernanceManagementViewController {
    private final PortalManagement management;
    private final com.lrj.authz.governance.access.application.RoleMigrationPreview migrations;
    private final com.lrj.authz.governance.access.application.AccessRequests requests;

    /** 只复用既有治理Runtime，没有独立BFF存储。 */
    public GovernanceManagementViewController(GovernanceRuntime runtime) {
        management = runtime.portalManagement();
        requests = runtime.requests();
        migrations = runtime.roleMigrationPreview();
    }

    /** 有界旧角色引用只供明确选择，不能把第一页冒充全部迁移对象。 */
    @GetMapping("/role-migration-grants")
    public ResponseEntity<JsonNode> migrationGrants(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam("old_role_id") String role,
            @RequestParam(value = "after", required = false) String after,
            jakarta.servlet.http.HttpServletRequest request) {
        var allowed =
                java.util.Set.of(
                        "tenant_id", "application_id", "environment", "old_role_id", "after");
        if (request.getParameterMap().entrySet().stream()
                .anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().length != 1))
            throw new com.lrj.authz.governance.shared.application.GovernanceException(
                    com.lrj.authz.governance.shared.application.GovernanceException.Code
                            .INVALID_ARGUMENT);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        GovernanceWeb.body(
                                migrations.candidates(
                                        login, new Partition(tenant, app, env), role, after)));
    }

    /** 严格只读JSON不接受操作者或来源改写，预览不能作为后续执行票据。 */
    @PostMapping(value = "/role-migration-preview", consumes = "application/json")
    public ResponseEntity<JsonNode> migrationPreview(
            @AuthenticationPrincipal VerifiedLogin login,
            jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        var input =
                com.lrj.authz.governance.shared.web.AccessWeb.read(
                        request.getInputStream(),
                        com.lrj.authz.protocol.RoleMigrationDtos.PreviewRequest.class);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(GovernanceWeb.body(migrations.preview(login, input)));
    }

    /** 完整已发布元数据受当前管理委派保护，浏览器不能缓存为长期授予资格。 */
    @GetMapping("/published-catalog")
    public ResponseEntity<JsonNode> publishedCatalog(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        GovernanceWeb.body(
                                management.publishedCatalog(
                                        login, new Partition(tenant, app, env))));
    }

    /** 表单选项是当前上限的只读提示，最终提交仍重新判权。 */
    @GetMapping("/management")
    public JsonNode management(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env) {
        return GovernanceWeb.body(management.management(login, new Partition(tenant, app, env)));
    }

    /** 当前分区管理权限不能列举其他企业账号。 */
    @GetMapping("/members")
    public JsonNode members(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        return GovernanceWeb.body(
                management.members(login, new Partition(tenant, app, env), after));
    }

    /** 管理策略的审批成员仅对当前有委派管理员开放。 */
    @GetMapping("/request-policies")
    public JsonNode policies(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        var page = management.policies(login, new Partition(tenant, app, env), after);
        return GovernanceWeb.body(
                new com.lrj.authz.protocol.RequestDtos.Page<>(
                        page.items().stream()
                                .map(
                                        p ->
                                                new com.lrj.authz.protocol.PortalDtos
                                                        .PolicyConfiguration(
                                                        requests.policyView(p),
                                                        p.approverMembershipId(),
                                                        p.approverGeneration(),
                                                        p.enabled()))
                                .toList(),
                        page.nextCursor()));
    }

    /** 差异和引用数量均从固定角色版本计算。 */
    @GetMapping("/role-impact")
    public JsonNode roleImpact(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam("role_id") String role) {
        return GovernanceWeb.body(
                management.roleImpact(login, new Partition(tenant, app, env), role));
    }
}
