package com.lrj.authz.admin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import com.lrj.authz.governance.web.*;
import com.lrj.authz.protocol.AccessDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

/** 当前管理Token加服务端委派双重保护；成员本身不意味着授权管理员。 */
@RestController
@GovernanceWeb.Endpoint
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled"},havingValue="true")
@RequestMapping("/api/governance/v1")
public class GovernanceAccessController {
    private final AccessManagement access;private final ApplicationCatalog catalog;
    /** 独立开关默认关闭，不改变旧管理工作区。 */
    public GovernanceAccessController(GovernanceRuntime runtime){access=runtime.access();catalog=runtime.catalog();}
    /** 拥有者可以预览自己的清单，未知字段和外部应用能力拒绝。 */
    @PostMapping(value="/catalog/preview",consumes="application/json")
    public JsonNode preview(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{return GovernanceWeb.body(catalog.preview(login,CatalogManifest.read(request.getInputStream())));}
    /** 所有权来自已验证登录，不能在body中替换发布人。 */
    @PostMapping(value="/catalog/publish",consumes="application/json")
    public JsonNode publish(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{return GovernanceWeb.body(catalog.publish(login,CatalogManifest.read(request.getInputStream()),GovernanceWeb.singleHeader(request.getHeaders("X-Command-Id"))));}
    /** 固定角色版本创建；业务角色不授予管理权。 */
    @PostMapping(value="/access/roles",consumes="application/json")
    public JsonNode role(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        CreateRole r=AccessWeb.read(request.getInputStream(),CreateRole.class);
        return GovernanceWeb.body(AccessWeb.role(access.createRole(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.roleCode(),r.roleVersion(),r.capabilities())));
    }
    /** 202表示已受理且待图确认，不能把数据库插入当成权限生效。 */
    @PostMapping(value="/access/grants",consumes="application/json")
    public ResponseEntity<JsonNode> grant(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        CreateGrant r=AccessWeb.read(request.getInputStream(),CreateGrant.class);
        return ResponseEntity.accepted().body(GovernanceWeb.body(AccessWeb.grant(access.grant(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.memberId(),r.memberGeneration(),r.roleId(),r.scope(),r.sourceId(),AccessWeb.instant(r.validFrom()),AccessWeb.instant(r.validTo())))));
    }
    /** 撤销先去掉SQL资格，即便图暂时不可用也不能继续ALLOW。 */
    @PostMapping(value="/access/revoke",consumes="application/json")
    public JsonNode revoke(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        RevokeGrant r=AccessWeb.read(request.getInputStream(),RevokeGrant.class);
        return GovernanceWeb.body(AccessWeb.grant(access.revoke(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.grantId(),r.expectedVersion())));
    }
    /** 依赖恢复后显式重试耗尽意图；委派、乐观版本、幂等和审计仍完整执行。 */
    @PostMapping(value="/access/retry-projection",consumes="application/json")
    public JsonNode retry(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        RevokeGrant r=AccessWeb.read(request.getInputStream(),RevokeGrant.class);
        return GovernanceWeb.body(AccessWeb.grant(access.retryProjection(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.grantId(),r.expectedVersion())));
    }
    /** 查询同样校验管理范围；游标不能改变tenant/app/env过滤。 */
    @GetMapping("/access/state")
    public JsonNode state(@AuthenticationPrincipal VerifiedLogin login,@RequestParam("tenant_id")String tenant,@RequestParam("application_id")String app,
                          @RequestParam("environment")String env,@RequestParam(value="after_role",required=false)String role,@RequestParam(value="after_grant",required=false)String grant){
        return GovernanceWeb.body(AccessWeb.state(access.state(login,new Partition(tenant,app,env),role,grant)));
    }
}
