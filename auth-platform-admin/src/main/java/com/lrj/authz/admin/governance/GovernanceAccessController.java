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
    /** 细范围不能走旧字符串入口；固定快照与Grant、审计和投影意图原子保存。 */
    @PostMapping(value="/access/scoped-grants",consumes="application/json")
    public ResponseEntity<JsonNode> scopedGrant(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        var r=AccessWeb.read(request.getInputStream(),com.lrj.authz.protocol.ScopeDtos.CreateScopedGrant.class);
        return ResponseEntity.accepted().body(GovernanceWeb.body(AccessWeb.grant(access.grantScoped(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.memberId(),r.memberGeneration(),r.roleId(),r.scopeRule(),r.sourceId(),AccessWeb.instant(r.validFrom()),AccessWeb.instant(r.validTo())))));
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
    /** 组来自目录，调用方不能提交组成员或任意组织映射。 */
    @PostMapping(value="/access/group-grants",consumes="application/json")
    public ResponseEntity<JsonNode> group(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        var r=AccessWeb.read(request.getInputStream(),com.lrj.authz.protocol.SafetyDtos.GroupGrant.class);
        return ResponseEntity.accepted().body(GovernanceWeb.body(AccessWeb.grant(access.grantGroup(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),r.groupId(),r.roleId(),r.scopeRule(),r.sourceId(),AccessWeb.instant(r.validFrom()),AccessWeb.instant(r.validTo())))));
    }
    /** 受理和全局完成分开；即使SQL已撤销仍返回202及可查询回执。 */
    @PostMapping(value="/access/strict-revoke",consumes="application/json")
    public ResponseEntity<JsonNode> strictRevoke(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        RevokeGrant r=AccessWeb.read(request.getInputStream(),RevokeGrant.class);var p=new Partition(r.tenantId(),r.applicationId(),r.environment());
        return ResponseEntity.accepted().body(GovernanceWeb.body(access.strictRevoke(login,p,r.commandId(),r.grantId(),r.expectedVersion())));
    }
    /** 完成回执同样校验当前管理范围。 */
    @GetMapping("/access/revocation-receipt")
    public JsonNode receipt(@AuthenticationPrincipal VerifiedLogin login,@RequestParam("tenant_id")String tenant,@RequestParam("application_id")String app,@RequestParam("environment")String env,@RequestParam("grant_id")String grant){
        return GovernanceWeb.body(access.revocationReceipt(login,new Partition(tenant,app,env),grant));
    }
    /** 只有完成升级节点路由的隔离分区才应调用此不可降级切换。 */
    @PostMapping(value="/access/enable-strict",consumes="application/json")
    public ResponseEntity<Void> strict(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        var r=AccessWeb.read(request.getInputStream(),com.lrj.authz.protocol.SafetyDtos.PartitionCommand.class);
        if(r.kind()!=null)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        access.enableStrict(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId());return ResponseEntity.accepted().build();
    }
    /** 耗尽重试经审计恢复为UPDATING，不伪造READY或替换旧payload。 */
    @PostMapping(value="/access/retry-strict",consumes="application/json")
    public ResponseEntity<Void> retryStrict(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        var r=AccessWeb.read(request.getInputStream(),com.lrj.authz.protocol.SafetyDtos.PartitionCommand.class);
        com.lrj.authz.governance.domain.ProjectionModels.Kind kind;
        try{kind=com.lrj.authz.governance.domain.ProjectionModels.Kind.valueOf(r.kind());}catch(RuntimeException e){throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);}
        access.retryStrict(login,new Partition(r.tenantId(),r.applicationId(),r.environment()),r.commandId(),kind);return ResponseEntity.accepted().build();
    }
    /** 目录组分页仅展示当前管理环境。 */
    @GetMapping("/access/groups")
    public JsonNode groups(@AuthenticationPrincipal VerifiedLogin login,@RequestParam("tenant_id")String tenant,@RequestParam("application_id")String app,@RequestParam("environment")String env,@RequestParam(value="after",required=false)String after){
        return GovernanceWeb.body(access.groups(login,new Partition(tenant,app,env),after));
    }
    /** 所有权来自已验证主体；停用与全应用策略epoch、审计同事务。 */
    @PostMapping(value="/catalog/capability-state",consumes="application/json")
    public JsonNode capability(@AuthenticationPrincipal VerifiedLogin login,HttpServletRequest request)throws IOException{
        var r=AccessWeb.read(request.getInputStream(),com.lrj.authz.protocol.SafetyDtos.CapabilityCommand.class);
        return GovernanceWeb.body(catalog.changeCapability(login,r.applicationId(),r.capability(),r.disabled(),r.expectedVersion(),r.reason(),r.commandId()));
    }
    /** 查询同样校验管理范围；游标不能改变tenant/app/env过滤。 */
    @GetMapping("/access/state")
    public JsonNode state(@AuthenticationPrincipal VerifiedLogin login,@RequestParam("tenant_id")String tenant,@RequestParam("application_id")String app,
                          @RequestParam("environment")String env,@RequestParam(value="after_role",required=false)String role,@RequestParam(value="after_grant",required=false)String grant){
        return GovernanceWeb.body(AccessWeb.state(access.state(login,new Partition(tenant,app,env),role,grant)));
    }
}
