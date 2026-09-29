package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import com.lrj.authz.governance.web.GovernanceWeb;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 专用管理Token查询本人菜单；不消费旧Casdoor组，也不向浏览器发放服务密钥。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled","authz.governance.presentation.enabled"},havingValue="true")
public class GovernancePresentationController {
    private final AccessPresentation presentation;
    private final PortalDirectory portal;
    /** 展示单独开关，未配置专属图时不改变已有管理接口启动条件。 */
    public GovernancePresentationController(GovernanceRuntime runtime, Environment environment) {
        var properties=GovernanceConfigurationFile.read(environment.getProperty("authz.governance.configuration"));
        var graph=GovernanceGraph.open(properties);
        if(environment.getProperty("authz.governance.scope.enabled",Boolean.class,false)){
            var strict=new com.lrj.authz.core.SpiceDbProjectionGraph(properties.getProperty("scope.graph.http"),properties.getProperty("scope.graph.key"),java.time.Duration.ofSeconds(3));
            presentation=runtime.presentation(graph,strict);
        }else presentation=runtime.presentation(graph);
        portal=runtime.portal(presentation);
    }
    /** 仅当前已验证主体；未知principal等查询字段不参与选人。 */
    @GetMapping("/api/governance/v1/me/access")
    public JsonNode current(@AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant, @RequestParam("application_id") String app,
            @RequestParam("environment") String environment) {
        return GovernanceWeb.body(presentation.current(login, new Partition(tenant, app, environment)));
    }
    /** 只列出本人有效组织，名称也来自权威库。 */
    @GetMapping("/api/governance/v1/me/organizations")
    public JsonNode organizations(@AuthenticationPrincipal VerifiedLogin login) {
        return GovernanceWeb.body(portal.organizations(login));
    }
    /** 关联目录与菜单均服务端过滤，浏览器游标不能扩大租户范围。 */
    @GetMapping("/api/governance/v1/me/applications")
    public JsonNode applications(@AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant, @RequestParam(value="after", required=false) String after) {
        return GovernanceWeb.body(portal.applications(login, tenant, after));
    }

}
