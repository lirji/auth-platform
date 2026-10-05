package com.lrj.authz.admin.governance.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.access.application.RoleMigrationTasks;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Set;

/** 人类管理员的有界迁移入口，服务端当前授权不能由任务创建身份永久继承。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {"authz.governance.enabled", "authz.governance.access.enabled"},
        havingValue = "true")
@RequestMapping("/api/governance/v1/access/role-migrations")
public final class RoleMigrationController {
    private final RoleMigrationTasks tasks;

    /** 所有路径共享原治理Runtime，没有新数据库或自动后台授权。 */
    public RoleMigrationController(GovernanceRuntime runtime) {
        tasks = runtime.roleMigrationTasks();
    }

    /** 202只代表计划已持久化，尚未撤销或授予任何来源。 */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<JsonNode> create(
            @AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        return response(
                202, tasks.create(login, AccessWeb.read(request.getInputStream(), Create.class)));
    }

    /** 管理分区稳定游标历史，拒绝额外和重复查询参数。 */
    @GetMapping
    public ResponseEntity<JsonNode> list(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "old_role_id", required = false) String old,
            @RequestParam(value = "after", required = false) String after,
            HttpServletRequest request) {
        query(
                request,
                Set.of("tenant_id", "application_id", "environment", "old_role_id", "after"));
        return response(200, tasks.list(login, new Partition(tenant, app, env), old, after));
    }

    /** 详情读取包含取消后仍存在的新Grant事实，不隐瞒已提交效果。 */
    @GetMapping("/{id}")
    public ResponseEntity<JsonNode> get(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            HttpServletRequest request) {
        query(request, Set.of("tenant_id", "application_id", "environment"));
        return response(200, tasks.get(login, new Partition(tenant, app, env), id));
    }

    /** 单子项单阶段；未知结果必须重放原command_id。 */
    @PostMapping(value = "/{id}/advance", consumes = "application/json")
    public ResponseEntity<JsonNode> advance(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            HttpServletRequest request)
            throws IOException {
        return response(
                202,
                tasks.advance(login, id, AccessWeb.read(request.getInputStream(), Advance.class)));
    }

    /** 取消不恢复已撤旧授权、不撤销已提交的新Grant。 */
    @PostMapping(value = "/{id}/cancel", consumes = "application/json")
    public ResponseEntity<JsonNode> cancel(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            HttpServletRequest request)
            throws IOException {
        return response(
                200,
                tasks.cancel(login, id, AccessWeb.read(request.getInputStream(), Cancel.class)));
    }

    private static ResponseEntity<JsonNode> response(int status, Object value) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(GovernanceWeb.body(value));
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (request.getParameterMap().entrySet().stream()
                .anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().length != 1))
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
