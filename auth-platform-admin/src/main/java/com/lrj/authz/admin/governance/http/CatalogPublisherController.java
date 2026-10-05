package com.lrj.authz.admin.governance.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.catalog.application.CatalogPublication;
import com.lrj.authz.governance.catalog.application.CatalogPublisher;
import com.lrj.authz.governance.catalog.application.CatalogPublisherJson;
import com.lrj.authz.governance.catalog.domain.CatalogPublisherModels.*;
import com.lrj.authz.governance.identity.authentication.VerifiedMachine;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.GovernanceWeb;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 机器只提交固定候选及原命令；所有目标和委派最终由同事务主库判定。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.publisher.enabled"},
        havingValue = "true")
@RequestMapping("/api/catalog-publisher/v1")
public class CatalogPublisherController {
    private final CatalogPublisher publisher;

    /** 仅使用已装配的固定目标，不能根据请求临时连接其他库。 */
    public CatalogPublisherController(CatalogPublisher publisher) {
        this.publisher = publisher;
    }

    /** 语义变化返回人工审查报告，不能获得机器票据。 */
    @PostMapping(value = "/preview", consumes = "application/json")
    public ResponseEntity<JsonNode> preview(
            @AuthenticationPrincipal VerifiedMachine machine, HttpServletRequest request)
            throws IOException {
        noQuery(request);
        return reply(
                publisher.preview(
                        machine,
                        CatalogPublication.read(request.getInputStream(), PreviewInput.class)));
    }

    /** 4KiB小命令不能替换票据中的清单或操作主体。 */
    @PostMapping(value = "/publish", consumes = "application/json")
    public ResponseEntity<JsonNode> publish(
            @AuthenticationPrincipal VerifiedMachine machine, HttpServletRequest request)
            throws IOException {
        noQuery(request);
        return reply(
                publisher.publish(
                        machine,
                        CatalogPublisherJson.small(request.getInputStream(), Publish.class)));
    }

    /** 未知结果仅查询本委派原命令，当前停用身份仍然拒绝。 */
    @PostMapping(value = "/receipt", consumes = "application/json")
    public ResponseEntity<JsonNode> receipt(
            @AuthenticationPrincipal VerifiedMachine machine, HttpServletRequest request)
            throws IOException {
        noQuery(request);
        return reply(
                publisher.receipt(
                        machine,
                        CatalogPublisherJson.small(request.getInputStream(), ReceiptQuery.class)));
    }

    static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty())
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }

    private static ResponseEntity<JsonNode> reply(Object value) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(GovernanceWeb.body(value));
    }
}
