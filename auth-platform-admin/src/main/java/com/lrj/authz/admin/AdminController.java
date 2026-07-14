package com.lrj.authz.admin;

import com.lrj.authz.admin.AdminDtos.*;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 授权管理 API (auth-console 后端)。
 * 授予/撤销关系元组, 反查主体/资源, 权限调试。读一律 full 一致性 (管理/调试要看最新)。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AuthzEngine engine;
    private final AuditStore audit;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AdminController(AuthzEngine engine, AuditStore audit) {
        this.engine = engine;
        this.audit = audit;
    }

    /** 授予一条关系 (TOUCH, 幂等)。 */
    @PostMapping("/grants")
    public TokenResponse grant(@RequestBody GrantRequest req,
                               @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        String token = engine.writeRelationships(List.of(
                RelationshipUpdate.touch(resource(req), req.relation(), subject(req)))).token();
        audit.record(actor(jwt), "grant", tupleOf(req));
        return new TokenResponse(token);
    }

    /** 撤销一条关系 (DELETE 单条元组)。 */
    @PostMapping("/grants/revoke")
    public TokenResponse revoke(@RequestBody GrantRequest req,
                                @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        String token = engine.writeRelationships(List.of(
                RelationshipUpdate.delete(resource(req), req.relation(), subject(req)))).token();
        audit.record(actor(jwt), "revoke", tupleOf(req));
        return new TokenResponse(token);
    }

    /** 谁对该资源拥有某权限 (反查主体)。 */
    @GetMapping("/resources/{type}/{id}/subjects")
    public SubjectsResponse subjects(@PathVariable String type, @PathVariable String id,
                                     @RequestParam String permission,
                                     @RequestParam(defaultValue = "user") String subjectType) {
        List<SubjectView> subjects = engine
                .lookupSubjects(ResourceRef.of(type, id), permission, subjectType, Consistency.fullyConsistent())
                .stream().map(s -> new SubjectView(s.type(), s.id())).toList();
        return new SubjectsResponse(subjects);
    }

    /** 某主体对某类型对象拥有某权限的全部对象 (反查资源)。 */
    @GetMapping("/subjects/{type}/{id}/resources")
    public ResourcesResponse resources(@PathVariable String type, @PathVariable String id,
                                       @RequestParam String permission,
                                       @RequestParam String resourceType) {
        return new ResourcesResponse(engine.lookupResources(
                SubjectRef.of(type, id), permission, resourceType, Consistency.fullyConsistent()));
    }

    /** 读取授权模型 (.zed schema 文本), 供管控台可视化。 */
    @GetMapping("/schema")
    public java.util.Map<String, String> schema() {
        return java.util.Map.of("schema", engine.readSchema());
    }

    /** 权限调试器: 单条判定。 */
    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed = engine.check(
                SubjectRef.of(req.subjectType(), req.subjectId()),
                req.permission(),
                ResourceRef.of(req.resourceType(), req.resourceId()),
                Consistency.fullyConsistent());
        return new CheckResponse(allowed);
    }

    /** 展开判定树(解释"为何 allow";用 CheckRequest 的 resource+permission,忽略 subject)。 */
    @PostMapping("/expand")
    public com.fasterxml.jackson.databind.JsonNode expand(@RequestBody CheckRequest req) {
        try {
            return mapper.readTree(engine.expand(
                    ResourceRef.of(req.resourceType(), req.resourceId()), req.permission(), Consistency.fullyConsistent()));
        } catch (Exception e) {
            throw new IllegalStateException("expand 解析失败: " + e.getMessage(), e);
        }
    }

    /** 列某资源现存的关系元组(实际授予)。 */
    @GetMapping("/relationships")
    public java.util.List<com.lrj.authz.protocol.Relationship> relationships(
            @RequestParam String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String relation) {
        return engine.readRelationships(com.lrj.authz.protocol.RelationshipFilter.of(resourceType, resourceId, relation));
    }

    /** 审计日志(内存最近记录)。 */
    @GetMapping("/audit")
    public java.util.List<AuditStore.AuditRecord> auditLog(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    private static String actor(org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (jwt == null) {
            return "-";
        }
        String name = jwt.getClaimAsString("name");
        return name != null ? name : jwt.getSubject();
    }

    private static String tupleOf(GrantRequest req) {
        String subj = req.subjectType() + ":" + req.subjectId()
                + (req.subjectRelation() != null && !req.subjectRelation().isBlank() ? "#" + req.subjectRelation() : "");
        return req.resourceType() + ":" + req.resourceId() + "#" + req.relation() + "@" + subj;
    }

    private static ResourceRef resource(GrantRequest req) {
        return ResourceRef.of(req.resourceType(), req.resourceId());
    }

    private static SubjectRef subject(GrantRequest req) {
        return new SubjectRef(req.subjectType(), req.subjectId(),
                req.subjectRelation() == null || req.subjectRelation().isBlank() ? null : req.subjectRelation());
    }
}
