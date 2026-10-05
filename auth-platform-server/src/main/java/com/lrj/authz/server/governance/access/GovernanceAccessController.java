package com.lrj.authz.server.governance.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.authorization.application.AccessAuthorization;
import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.context.application.InternalContextService;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.CentralAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/** 服务与最终用户分别认证，不能通过传入principal或app代查其他人。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.access.enabled"},
        havingValue = "true")
@RequestMapping("/internal/governance/v1/access")
public class GovernanceAccessController {
    private final InternalContextService contexts;
    private final AccessAuthorization access;
    private final Set<String> callers;

    /** 独立允许名单避免旧服务绑定自动获得新协议能力。 */
    public GovernanceAccessController(
            InternalContextService contexts,
            AccessAuthorization access,
            GovernanceAccessConfiguration.AccessSettings settings) {
        this.contexts = contexts;
        this.access = access;
        this.callers = settings.callers();
    }

    /** 单项检查成功只返回明确ALLOW或DENY，故障由边界返回503。 */
    @PostMapping(value = "/check", consumes = "application/json")
    public JsonNode check(HttpServletRequest request) throws IOException {
        String service = authenticate(request);
        String user = GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token"));
        return GovernanceWeb.body(
                decide(service, user, AccessWeb.read(request.getInputStream(), Check.class)));
    }

    /** 同一用户/租户的有界批量，先验证完整请求再逐项执行，结果不跨请求缓存。 */
    @PostMapping(value = "/check-bulk", consumes = "application/json")
    public JsonNode bulk(HttpServletRequest request) throws IOException {
        String service = authenticate(request);
        String user = GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token"));
        Batch batch = AccessWeb.read(request.getInputStream(), Batch.class);
        if (batch.checks() == null || batch.checks().isEmpty() || batch.checks().size() > 20)
            throw invalid();
        Set<String> ids = new HashSet<>();
        Check first = batch.checks().getFirst();
        validate(first);
        for (Check check : batch.checks()) {
            validate(check);
            if (!ids.add(check.requestId())
                    || !first.tenantId().equals(check.tenantId())
                    || !Objects.equals(
                            first.expectedMembershipGeneration(),
                            check.expectedMembershipGeneration())) throw invalid();
        }
        var context =
                contexts.resolve(
                        service,
                        user,
                        new GovernanceDtos.ResolveRequest(
                                first.tenantId(), first.expectedMembershipGeneration()));
        long deadline = System.nanoTime() + 10_000_000_000L;
        List<Decision> results = new ArrayList<>();
        for (Check check : batch.checks()) {
            if (System.nanoTime() >= deadline)
                throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
            results.add(decision(context, check));
        }
        return GovernanceWeb.body(new Decisions(List.copyOf(results)));
    }

    private String authenticate(HttpServletRequest request) {
        String service =
                GovernanceWeb.bearer(
                        GovernanceWeb.singleHeader(request.getHeaders("Authorization")));
        if (!callers.contains(contexts.authenticateCaller(service).callerServiceId()))
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        return service;
    }

    private Decision decide(String service, String user, Check check) {
        validate(check);
        var context =
                contexts.resolve(
                        service,
                        user,
                        new GovernanceDtos.ResolveRequest(
                                check.tenantId(), check.expectedMembershipGeneration()));
        return decision(context, check);
    }

    private Decision decision(GovernanceDtos.AccessContext context, Check check) {
        boolean allowed = access.allowed(context, check.capability(), check.resourceType());
        return new Decision(
                "1",
                check.requestId(),
                check.capability(),
                check.resourceType(),
                allowed ? "ALLOW" : "DENY",
                allowed ? "TENANT_ALL" : null,
                context,
                UUID.randomUUID().toString());
    }

    private void validate(Check c) {
        if (c == null) throw invalid();
        BootstrapCommand.uuid(c.tenantId());
        BootstrapCommand.uuid(c.requestId());
        CatalogManifest.code(c.capability());
        CatalogManifest.code(c.resourceType());
        if (c.expectedMembershipGeneration() != null && c.expectedMembershipGeneration() < 1)
            throw invalid();
    }

    private GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
