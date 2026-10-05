package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.access.application.AccessReviews;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.AccessReviewDtos.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 复核仅暴露当前合格管理者的人工用例，不能用任务UUID代替诊断授权。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.access.enabled"},
        havingValue = "true")
@RequestMapping("/api/governance/v1/access")
public class GovernanceReviewController {
    private final AccessReviews reviews;

    /** 沿用原宿主独立诊断配置，默认空配置不获得复核资格。 */
    public GovernanceReviewController(
            GovernanceRuntime runtime, GovernanceAdminConfiguration.Settings settings) {
        reviews = runtime.accessReviews(settings.portalDiagnostics());
    }

    /** 负责人候选仅当前合格成员编号，无登录绑定信息。 */
    @GetMapping("/review-responsibles")
    public ResponseEntity<JsonNode> responsibles(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam String environment) {
        return ok(reviews.responsibles(login, new Partition(tenant, app, environment)));
    }

    /** 当前分区有界任务页，可在页面关闭后继续原检查点。 */
    @GetMapping("/reviews")
    public ResponseEntity<JsonNode> list(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam String environment,
            @RequestParam(value = "membership_id", required = false) String member,
            @RequestParam(required = false) String after) {
        return ok(reviews.list(login, new Partition(tenant, app, environment), member, after));
    }

    /** GET不推进状态，真实撤权证明与固定输入分开显示。 */
    @GetMapping("/reviews/{id}")
    public ResponseEntity<JsonNode> detail(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable String id,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam String environment) {
        return ok(reviews.get(login, new Partition(tenant, app, environment), id));
    }

    /** 明确范围和负责人后才建立任务，不自动选择全员来源。 */
    @PostMapping(value = "/reviews", consumes = "application/json")
    public ResponseEntity<JsonNode> create(
            @AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        return ok(reviews.create(login, AccessWeb.read(request.getInputStream(), Create.class)));
    }

    /** 202表示撤权已发而尚未确认，不能显示复核已完成。 */
    @PostMapping(value = "/reviews/{id}/decision", consumes = "application/json")
    public ResponseEntity<JsonNode> decide(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable String id,
            HttpServletRequest request)
            throws IOException {
        var c = AccessWeb.read(request.getInputStream(), Decide.class);
        var result = reviews.decide(login, id, c);
        return ResponseEntity.status(c.decision() == Decision.REVOKE ? 202 : 200)
                .header("Cache-Control", "no-store")
                .body(GovernanceWeb.body(result));
    }

    /** 显式确认匹配原撤权意图的真实完成回执。 */
    @PostMapping(value = "/reviews/{id}/confirm", consumes = "application/json")
    public ResponseEntity<JsonNode> confirm(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable String id,
            HttpServletRequest request)
            throws IOException {
        return ok(
                reviews.confirm(
                        login, id, AccessWeb.read(request.getInputStream(), Confirm.class)));
    }

    /** 取消仅停止未处理条目，已撤授权不恢复。 */
    @PostMapping(value = "/reviews/{id}/cancel", consumes = "application/json")
    public ResponseEntity<JsonNode> cancel(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable String id,
            HttpServletRequest request)
            throws IOException {
        return ok(
                reviews.cancel(login, id, AccessWeb.read(request.getInputStream(), Cancel.class)));
    }

    /** 当前合格管理者显式改派负责人，原责任与原因保留审计。 */
    @PostMapping(value = "/reviews/{id}/responsible", consumes = "application/json")
    public ResponseEntity<JsonNode> reassign(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable String id,
            HttpServletRequest request)
            throws IOException {
        return ok(
                reviews.reassign(
                        login, id, AccessWeb.read(request.getInputStream(), Reassign.class)));
    }

    private static ResponseEntity<JsonNode> ok(Object value) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(GovernanceWeb.body(value));
    }
}
