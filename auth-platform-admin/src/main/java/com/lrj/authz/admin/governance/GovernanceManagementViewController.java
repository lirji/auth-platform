package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.PortalManagement;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import com.lrj.authz.governance.web.GovernanceWeb;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 门户管理读接口与原写接口使用同一当前委派，不接受浏览器操作者字段。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled"}, havingValue="true")
@RequestMapping("/api/governance/v1/access")
public class GovernanceManagementViewController {
    private final PortalManagement management;
    /** 只复用既有治理Runtime，没有独立BFF存储。 */
    public GovernanceManagementViewController(GovernanceRuntime runtime) { management = runtime.portalManagement(); }
    /** 表单选项是当前上限的只读提示，最终提交仍重新判权。 */
    @GetMapping("/management")
    public JsonNode management(@AuthenticationPrincipal VerifiedLogin login, @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app, @RequestParam("environment") String env) {
        return GovernanceWeb.body(management.management(login, new Partition(tenant, app, env)));
    }
    /** 当前分区管理权限不能列举其他企业账号。 */
    @GetMapping("/members")
    public JsonNode members(@AuthenticationPrincipal VerifiedLogin login, @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app, @RequestParam("environment") String env,
            @RequestParam(value="after", required=false) String after) {
        return GovernanceWeb.body(management.members(login, new Partition(tenant, app, env), after));
    }
    /** 差异和引用数量均从固定角色版本计算。 */
    @GetMapping("/role-impact")
    public JsonNode roleImpact(@AuthenticationPrincipal VerifiedLogin login, @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app, @RequestParam("environment") String env, @RequestParam("role_id") String role) {
        return GovernanceWeb.body(management.roleImpact(login, new Partition(tenant, app, env), role));
    }
}
