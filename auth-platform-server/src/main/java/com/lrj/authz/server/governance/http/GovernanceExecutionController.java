package com.lrj.authz.server.governance.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.authorization.application.ExecutionAuthorization;
import com.lrj.authz.governance.context.application.CallerService;
import com.lrj.authz.governance.context.application.InternalContextService;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.ResolveRequest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 签发必须双身份；执行只能使用已签发引用，禁止服务任意指定被代理用户。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.scope.enabled",
            "authz.governance.executions.enabled"
        },
        havingValue = "true")
@RequestMapping("/internal/governance/v1/access")
public class GovernanceExecutionController {
    private final InternalContextService contexts;
    private final ExecutionAuthorization executions;
    private final GovernanceExecutionConfiguration.ExecutionSettings settings;

    public GovernanceExecutionController(
            InternalContextService contexts,
            ExecutionAuthorization executions,
            GovernanceExecutionConfiguration.ExecutionSettings settings) {
        this.contexts = contexts;
        this.executions = executions;
        this.settings = settings;
    }

    /** 用户Token只在当前调用中验证，持久层只接收经过解析的可信上下文。 */
    @PostMapping(value = "/executions", consumes = "application/json")
    public JsonNode issue(HttpServletRequest request) throws IOException {
        String credential = credential(request);
        caller(credential);
        var input = AccessWeb.read(request.getInputStream(), Issue.class);
        if (input == null || input.check() == null)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        var check = input.check();
        requireOwner(check.resourceType());
        var context =
                contexts.resolve(
                        credential,
                        GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token")),
                        new ResolveRequest(check.tenantId(), check.expectedMembershipGeneration()));
        return GovernanceWeb.body(executions.issue(context, input));
    }

    /** 无登录Token的后台调用只能缩小到原引用中的相同Grant范围。 */
    @PostMapping(value = "/execution-check", consumes = "application/json")
    public JsonNode check(HttpServletRequest request) throws IOException {
        var caller = caller(credential(request));
        var input = AccessWeb.read(request.getInputStream(), Check.class);
        if (input == null || input.resource() == null || input.resource().check() == null)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        requireOwner(input.resource().check().resourceType());
        return GovernanceWeb.body(executions.check(caller, input));
    }

    /** 已签发集合引用仍受Owner类型允许列表约束，不能伪造待创建对象事实。 */
    @PostMapping(value = "/execution-scope", consumes = "application/json")
    public JsonNode scope(HttpServletRequest request) throws IOException {
        var caller = caller(credential(request));
        var input = AccessWeb.read(request.getInputStream(), ScopeCheck.class);
        if (input == null || input.check() == null)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        requireOwner(input.check().resourceType());
        var body = GovernanceWeb.body(executions.scope(caller, input));
        if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > com.lrj.authz.protocol.ScopeDtos.MAX_PLAN_BYTES)
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        return body;
    }

    private void requireOwner(String resource) {
        if (resource == null)
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        if (!settings.resources().contains(resource))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
    }

    private CallerService caller(String credential) {
        var caller = contexts.authenticateCaller(credential);
        if (!settings.callers().contains(caller.callerServiceId())
                || !"commerce".equals(caller.applicationId()))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        return caller;
    }

    private static String credential(HttpServletRequest request) {
        return GovernanceWeb.bearer(
                GovernanceWeb.singleHeader(request.getHeaders("Authorization")));
    }
}
