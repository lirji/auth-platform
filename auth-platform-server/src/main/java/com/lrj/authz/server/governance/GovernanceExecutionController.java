package com.lrj.authz.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.web.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.ResolveRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 签发必须双身份；执行只能使用已签发引用，禁止服务任意指定被代理用户。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled","authz.governance.scope.enabled","authz.governance.executions.enabled"},havingValue="true")
@RequestMapping("/internal/governance/v1/access")
public class GovernanceExecutionController {
    private final InternalContextService contexts;
    private final ExecutionAuthorization executions;
    private final GovernanceExecutionConfiguration.ExecutionSettings settings;
    public GovernanceExecutionController(InternalContextService contexts,ExecutionAuthorization executions,GovernanceExecutionConfiguration.ExecutionSettings settings) {
        this.contexts=contexts;this.executions=executions;this.settings=settings;
    }
    /** 用户Token只在当前调用中验证，持久层只接收经过解析的可信上下文。 */
    @PostMapping(value="/executions",consumes="application/json")
    public JsonNode issue(HttpServletRequest request)throws IOException {
        String credential=credential(request); caller(credential);
        var input=AccessWeb.read(request.getInputStream(),Issue.class);
        if(input==null||input.check()==null)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        var check=input.check();
        var context=contexts.resolve(credential,GovernanceWeb.singleHeader(request.getHeaders("X-User-Access-Token")),new ResolveRequest(check.tenantId(),check.expectedMembershipGeneration()));
        return GovernanceWeb.body(executions.issue(context,input));
    }
    /** 无登录Token的后台调用只能缩小到原引用中的相同Grant范围。 */
    @PostMapping(value="/execution-check",consumes="application/json")
    public JsonNode check(HttpServletRequest request)throws IOException {
        var caller=caller(credential(request));
        return GovernanceWeb.body(executions.check(caller,AccessWeb.read(request.getInputStream(),Check.class)));
    }
    private CallerService caller(String credential) {
        var caller=contexts.authenticateCaller(credential);
        if(!settings.callers().contains(caller.callerServiceId())||!"commerce".equals(caller.applicationId()))throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        return caller;
    }
    private static String credential(HttpServletRequest request) { return GovernanceWeb.bearer(GovernanceWeb.singleHeader(request.getHeaders("Authorization"))); }
}
