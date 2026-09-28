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
    /** 展示单独开关，未配置专属图时不改变已有管理接口启动条件。 */
    public GovernancePresentationController(GovernanceRuntime runtime, Environment environment) {
        var properties=GovernanceConfigurationFile.read(environment.getProperty("authz.governance.configuration"));
        var graph=GovernanceGraph.open(properties);
        if(environment.getProperty("authz.governance.scope.enabled",Boolean.class,false)){
            var strict=new com.lrj.authz.core.SpiceDbProjectionGraph(properties.getProperty("scope.graph.http"),properties.getProperty("scope.graph.key"),java.time.Duration.ofSeconds(3));
            presentation=runtime.presentation(graph,strict);
        }else presentation=runtime.presentation(graph);
    }
    /** 仅当前已验证主体；未知principal等查询字段不参与选人。 */
    @GetMapping("/api/governance/v1/me/access")
    public JsonNode current(@AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant, @RequestParam("application_id") String app,
            @RequestParam("environment") String environment) {
        return GovernanceWeb.body(presentation.current(login, new Partition(tenant, app, environment)));
    }
}
