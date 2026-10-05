package com.lrj.authz.server.governance.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.authorization.application.ReliableAuthorization;
import com.lrj.authz.governance.authorization.application.ScopeRules;
import com.lrj.authz.governance.authorization.domain.ScopeModels.Evaluation;
import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.context.application.InternalContextService;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.CentralAccessDtos;
import com.lrj.authz.protocol.GovernanceDtos;
import com.lrj.authz.protocol.ScopeAccessDtos.*;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.protocol.ScopeResourceBindings;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单一资源Owner认证边界：租户选择属于用户，app/env/事实发布权限属于服务绑定。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.scope.enabled"
        },
        havingValue = "true")
@RequestMapping("/internal/governance/v1/access")
public class GovernanceScopeController {
    private final InternalContextService contexts;
    private final ReliableAuthorization access;
    private final GovernanceScopeConfiguration.ScopeSettings settings;

    /** 依赖只取治理Runtime，不从业务浏览器接收已授权标记。 */
    public GovernanceScopeController(
            InternalContextService contexts,
            ReliableAuthorization access,
            GovernanceScopeConfiguration.ScopeSettings settings) {
        this.contexts = contexts;
        this.access = access;
        this.settings = settings;
    }

    /** 无有效路径返回DENY；未知版本或依赖故障为ERROR，不能扩大到全范围。 */
    @PostMapping(value = "/scope-plan", consumes = "application/json")
    public JsonNode plan(HttpServletRequest request) throws IOException {
        var check = AccessWeb.read(request.getInputStream(), CentralAccessDtos.Check.class);
        var context = context(request, check);
        var result = access.evaluate(context, check.capability(), check.resourceType());
        var stamp = result.stamp();
        var plan =
                new Plan(
                        "1",
                        check.requestId(),
                        check.capability(),
                        check.resourceType(),
                        result.alternatives().isEmpty() ? "DENY" : "ALLOW",
                        result.decisionId(),
                        context,
                        stamp.policyId(),
                        stamp.policyEpoch(),
                        stamp.directoryId(),
                        stamp.directoryEpoch(),
                        stamp.manifestVersion(),
                        stamp.tenantVersion(),
                        result.validUntil().toString(),
                        result.alternatives());
        JsonNode body = GovernanceWeb.body(plan);
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > ScopeDtos.MAX_PLAN_BYTES)
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        return body;
    }

    /** 资源行先由Owner从当前本地租户读取；本接口拒绝跨租户或伪造字段绑定。 */
    @PostMapping(value = "/check-resource", consumes = "application/json")
    public JsonNode resource(HttpServletRequest request) throws IOException {
        var input = AccessWeb.read(request.getInputStream(), ResourceCheck.class);
        var c = input.check();
        var context = context(request, c);
        var facts = input.facts();
        if (facts == null
                || !context.tenantId().equals(facts.tenantId())
                || !c.resourceType().equals(facts.resourceType())
                || !ScopeResourceBindings.validFacts(facts)) throw invalid();
        Evaluation result = access.evaluate(context, c.capability(), c.resourceType());
        boolean allowed =
                ScopeRules.matches(
                        context.tenantId(), context.principalId(), result.alternatives(), facts);
        return GovernanceWeb.body(
                new ResourceDecision(
                        "1",
                        c.requestId(),
                        c.capability(),
                        c.resourceType(),
                        facts.resourceId(),
                        facts.resourceVersion(),
                        allowed ? "ALLOW" : "DENY",
                        result.decisionId(),
                        context,
                        result.validUntil().toString()));
    }

    private GovernanceDtos.AccessContext context(
            HttpServletRequest request, CentralAccessDtos.Check c) {
        if (c == null) throw invalid();
        BootstrapCommand.uuid(c.tenantId());
        BootstrapCommand.uuid(c.requestId());
        CatalogManifest.code(c.capability());
        CatalogManifest.code(c.resourceType());
        if (c.expectedMembershipGeneration() != null && c.expectedMembershipGeneration() < 1)
            throw invalid();
        String service =
                GovernanceWeb.bearer(
                        GovernanceWeb.singleHeader(request.getHeaders("Authorization")));
        if (!settings.callers().contains(contexts.authenticateCaller(service).callerServiceId()))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var result =
                contexts.resolve(
                        service,
                        GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token")),
                        new GovernanceDtos.ResolveRequest(
                                c.tenantId(), c.expectedMembershipGeneration()));
        if (!settings.owners()
                .getOrDefault(result.applicationId(), Set.of())
                .contains(c.resourceType()))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        return result;
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
