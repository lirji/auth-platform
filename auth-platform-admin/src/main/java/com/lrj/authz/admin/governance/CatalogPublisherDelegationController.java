package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogPublisherModels.*;
import com.lrj.authz.governance.web.GovernanceWeb;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** 委派只能由当前HUMAN Owner管理，机器前缀没有这些能力。 */
@RestController @GovernanceWeb.Endpoint
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.publisher.enabled"},havingValue="true")
@RequestMapping("/api/governance/v1/catalog/publisher-delegations")
public class CatalogPublisherDelegationController {
    private final CatalogPublisher publisher;
    /** 共享固定配置与主库用例；不读取前端operator或secret。 */
    public CatalogPublisherDelegationController(CatalogPublisher publisher) { this.publisher=publisher; }
    /** 建立一次性有限期slot，重试返回原事件，不延长期限。 */
    @PostMapping(consumes="application/json")
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException {
        CatalogPublisherController.noQuery(request);return reply(publisher.create(login,CatalogPublisherJson.small(request.getInputStream(),Create.class)));
    }
    /** 禁用采用版本CAS，恢复必须使用新客户端和新slot。 */
    @PostMapping(value="/disable",consumes="application/json")
    public ResponseEntity<JsonNode> disable(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException {
        CatalogPublisherController.noQuery(request);return reply(publisher.disable(login,CatalogPublisherJson.small(request.getInputStream(),Disable.class)));
    }
    /** 查询参数也严格限定一个application_id，拒绝重复或未知参数。 */
    @GetMapping
    public ResponseEntity<JsonNode> list(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request) {
        var parameters=request.getParameterMap();var app=parameters.get("application_id");
        if(parameters.size()!=1 || app==null || app.length!=1)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        return reply(publisher.list(login,app[0]));
    }
    private static ResponseEntity<JsonNode> reply(Object value) {return ResponseEntity.ok().header("Cache-Control","no-store").body(GovernanceWeb.body(value));}
}
