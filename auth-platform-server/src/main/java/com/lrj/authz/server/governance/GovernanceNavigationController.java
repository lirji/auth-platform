package com.lrj.authz.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.authorization.application.BusinessNavigation;
import com.lrj.authz.governance.context.application.InternalContextService;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.GovernanceDtos.ResolveRequest;
import com.lrj.authz.protocol.NavigationDtos;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 先验证固定调用方，再读取租户选择和当前专属用户Token；不能代查其他主体。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.scope.enabled",
            "authz.governance.navigation.enabled"
        },
        havingValue = "true")
@RequestMapping("/internal/governance/v1/access")
public class GovernanceNavigationController {
    private final InternalContextService contexts;
    private final BusinessNavigation navigation;
    private final Set<String> callers;

    /** 配置名单只授予本只读入口，不改变业务授权。 */
    public GovernanceNavigationController(
            InternalContextService contexts,
            BusinessNavigation navigation,
            GovernanceNavigationConfiguration.NavigationSettings settings) {
        this.contexts = contexts;
        this.navigation = navigation;
        this.callers = settings.callers();
    }

    /** 拒绝伪造正文；用户证据只在本次解析使用，响应无Token。 */
    @PostMapping(value = "/navigation", consumes = "application/json")
    public JsonNode current(HttpServletRequest request) throws IOException {
        String service =
                GovernanceWeb.bearer(
                        GovernanceWeb.singleHeader(request.getHeaders("Authorization")));
        if (!callers.contains(contexts.authenticateCaller(service).callerServiceId()))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var input = AccessWeb.read(request.getInputStream(), NavigationDtos.Request.class);
        BootstrapCommand.uuid(input.tenantId());
        BootstrapCommand.uuid(input.requestId());
        if (input.expectedMembershipGeneration() != null
                && input.expectedMembershipGeneration() < 1)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        var context =
                contexts.resolve(
                        service,
                        GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token")),
                        new ResolveRequest(input.tenantId(), input.expectedMembershipGeneration()));
        var body = GovernanceWeb.body(navigation.current(context, input.requestId()));
        if (body.toString().getBytes(StandardCharsets.UTF_8).length
                > NavigationDtos.MAX_RESPONSE_BYTES)
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        return body;
    }
}
