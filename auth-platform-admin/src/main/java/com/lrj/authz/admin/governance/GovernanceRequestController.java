package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.access.application.AccessRequests;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.approval.domain.RequestModels.*;
import com.lrj.authz.governance.authorization.application.ScopeRules;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.governance.shared.web.GovernanceWeb;
import com.lrj.authz.protocol.RequestDtos.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 独立开关控制新申请入口；统一治理Bearer链验证身份，应用层校验申请/管理权。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(
        name = {
            "authz.governance.enabled",
            "authz.governance.access.enabled",
            "authz.governance.requests.enabled"
        },
        havingValue = "true")
@RequestMapping("/api/governance/v1/requests")
public class GovernanceRequestController {
    private final AccessRequests requests;

    /** 只复用治理权威库，不注入OA数据库。 */
    public GovernanceRequestController(GovernanceRuntime runtime) {
        requests = runtime.requests();
    }

    /** 管理策略注册需当前委派，指定审批人并不自动产生委派。 */
    @PostMapping(value = "/policies", consumes = "application/json")
    public JsonNode policy(@AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), RegisterPolicy.class);
        var policy =
                requests.registerPolicy(
                        login,
                        new Partition(r.tenantId(), r.applicationId(), r.environment()),
                        r.commandId(),
                        r.roleId(),
                        r.scopeRule(),
                        r.maxDurationSeconds(),
                        r.approverMembershipId(),
                        r.approverGeneration(),
                        r.policyVersion());
        return GovernanceWeb.body(requests.policyView(policy));
    }

    /** 202只表示申请已持久化，不表示OA已启动或Grant生效。 */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<JsonNode> submit(
            @AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), Submit.class);
        var result =
                requests.submit(
                        login,
                        new Partition(r.tenantId(), r.applicationId(), r.environment()),
                        r.commandId(),
                        r.policyId(),
                        AccessWeb.instant(r.validFrom()),
                        AccessWeb.instant(r.validTo()),
                        r.reason());
        return ResponseEntity.accepted().body(GovernanceWeb.body(view(result)));
    }

    /** 新审批仅冻结旧OA来源；202不表示授权已经切换。 */
    @PostMapping(value = "/role-migration", consumes = "application/json")
    public ResponseEntity<JsonNode> migration(
            @AuthenticationPrincipal VerifiedLogin login, HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), SubmitMigration.class);
        return ResponseEntity.accepted()
                .body(
                        GovernanceWeb.body(
                                view(
                                        requests.submitMigration(
                                                login,
                                                new Partition(
                                                        r.tenantId(),
                                                        r.applicationId(),
                                                        r.environment()),
                                                r.commandId(),
                                                r.policyId(),
                                                r.oldGrantId(),
                                                r.expectedVersion(),
                                                r.reason()))));
    }

    /** 202表示取消/回收意图已持久化；不能将尚未完成的图回收显示成完成。 */
    @PostMapping(value = "/{id}/cancel", consumes = "application/json")
    public ResponseEntity<JsonNode> cancel(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            HttpServletRequest request)
            throws IOException {
        var r = AccessWeb.read(request.getInputStream(), Cancel.class);
        return ResponseEntity.accepted()
                .body(
                        GovernanceWeb.body(
                                view(
                                        requests.cancel(
                                                login,
                                                new Partition(
                                                        r.tenantId(),
                                                        r.applicationId(),
                                                        r.environment()),
                                                r.commandId(),
                                                id,
                                                r.stateVersion()))));
    }

    /** 详情限制本人当前代际，不因知道UUID就返回其他人的申请。 */
    @GetMapping("/{id}")
    public JsonNode detail(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env) {
        return GovernanceWeb.body(view(requests.owned(login, new Partition(tenant, app, env), id)));
    }

    /** 策略目录不提供员工目录；固定每页上限，末项ID可作下一页游标。 */
    @GetMapping("/policies")
    public JsonNode policies(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        var page = requests.policyPage(login, new Partition(tenant, app, env), after);
        return GovernanceWeb.body(
                new Page<>(
                        page.items().stream().map(requests::policyView).toList(),
                        page.nextCursor()));
    }

    /** 本人升级只列真实兼容策略，保留过滤前扫描游标，不暴露审批人目录。 */
    @GetMapping("/{id}/role-migration-policies")
    public JsonNode migrationPolicies(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        var page = requests.migrationPolicyPage(login, new Partition(tenant, app, env), id, after);
        return GovernanceWeb.body(
                new Page<>(
                        page.items().stream().map(requests::policyView).toList(),
                        page.nextCursor()));
    }

    /** 所有申请状态均可找到；只返回本人当前成员代际。 */
    @GetMapping
    public JsonNode mine(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        var items = requests.mine(login, new Partition(tenant, app, env), after);
        return GovernanceWeb.body(
                new Page<>(
                        items.stream().map(GovernanceRequestController::view).toList(),
                        items.size() == 100 ? items.getLast().id() : null));
    }

    /** 站内通知仅含安全模板和本人详情链接标识。 */
    @GetMapping("/notifications")
    public JsonNode notifications(
            @AuthenticationPrincipal VerifiedLogin login,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env,
            @RequestParam(value = "after", required = false) String after) {
        var items = requests.notices(login, new Partition(tenant, app, env), after);
        return GovernanceWeb.body(
                new Page<>(items, items.size() == 100 ? items.getLast().id() : null));
    }

    /** 执行状态不由OA的APPROVED推断，只有实际双栅栏与投影回执可展示ACTIVE。 */
    @GetMapping("/{id}/execution")
    public JsonNode execution(
            @AuthenticationPrincipal VerifiedLogin login,
            @PathVariable("id") String id,
            @RequestParam("tenant_id") String tenant,
            @RequestParam("application_id") String app,
            @RequestParam("environment") String env) {
        return GovernanceWeb.body(requests.execution(login, new Partition(tenant, app, env), id));
    }

    private static View view(Request r) {
        return new View(
                r.id(),
                r.policyId(),
                r.roleId(),
                AccessValues.read(r.capabilitiesJson()),
                ScopeRules.decode(r.scopeJson()),
                r.validFrom().toString(),
                r.validTo().toString(),
                r.reason(),
                r.requestVersion(),
                r.snapshotHash(),
                r.state().code(),
                r.stateVersion(),
                r.approvalInstanceId(),
                r.grantId(),
                r.migrationOldGrantId(),
                r.migrationOldVersion(),
                r.withdrawn());
    }
}
