package com.lrj.authz.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.web.GovernanceWeb;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

/** 内部上下文接口必须同时持有服务凭据和独立用户 Access Token。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
public class GovernanceContextController {
    private final InternalContextService contexts;

    /** 服务身份来源固定于部署配置，不接受其他 Controller 注入请求身份。 */
    public GovernanceContextController(InternalContextService contexts) { this.contexts = contexts; }

    /** 先验证服务，再读取有界严格 JSON；未知主体/app 字段一律拒绝。 */
    @PostMapping("/internal/governance/v1/context/resolve")
    public JsonNode resolve(HttpServletRequest request) throws IOException {
        String service = GovernanceWeb.bearer(GovernanceWeb.singleHeader(request.getHeaders("Authorization")));
        contexts.authenticateCaller(service);
        String user = GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token"));
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        return GovernanceWeb.body(contexts.resolve(service, user, GovernanceWeb.readResolve(request.getInputStream())));
    }
}
