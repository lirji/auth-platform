package com.lrj.authz.admin.approval.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.admin.approval.configuration.ApprovalCallbackConfiguration;
import com.lrj.authz.governance.approval.application.ApprovalInbox;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.ApprovalSignature;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 可信审批事件入口；收到事件不伪造申请已批准或Grant已生效。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.requests.enabled"},
        havingValue = "true")
public class ApprovalCallbackController {
    private final ApprovalInbox inbox;
    private final ApprovalCallbackConfiguration.CallbackSettings settings;

    /** 唯一配置分区不能被回调body覆盖。 */
    public ApprovalCallbackController(
            GovernanceRuntime runtime, ApprovalCallbackConfiguration.CallbackSettings settings) {
        inbox = runtime.approvalInbox();
        this.settings = settings;
    }

    /** 同ID改体返回409但证据已提交；重复业务事件返回持久当前回执。 */
    @PostMapping(value = ApprovalInbox.PATH, consumes = "application/json")
    public ResponseEntity<JsonNode> event(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(ApprovalSignature.MAX_BODY + 1);
        var result =
                inbox.receive(
                        settings.partition(),
                        settings.key(),
                        GovernanceWeb.singleHeader(request.getHeaders(ApprovalSignature.HEADER)),
                        body);
        return ResponseEntity.status(
                        com.lrj.authz.protocol.ApprovalDtos.Status.CONFLICT
                                        .code()
                                        .equals(result.status())
                                ? 409
                                : 202)
                .body(GovernanceWeb.body(result));
    }
}
