package com.lrj.authz.server.legacy.web;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.server.legacy.web.AuthzDtos.*;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 判权 REST 端点。SDK 的 RemoteAuthzEngine 调这里。
 * ZedToken 水位：写/删推进 {@link ZedTokenWatermark}；at_least_as_fresh 无 token 的请求自动代入水位
 * （原先这类请求会被 SpiceDB 拒绝），水位也没有时安全回退 full。开关 authz.server.zed-token-watermark-enabled。
 */
@RestController
@RequestMapping("/v1")
public class AuthzController {

    private final AuthzEngine engine;
    private final ZedTokenWatermark watermark;
    private final boolean watermarkEnabled;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 显式绑定 AuthzController 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public AuthzController(
            AuthzEngine engine,
            ZedTokenWatermark watermark,
            @org.springframework.beans.factory.annotation.Value(
                            "${authz.server.zed-token-watermark-enabled:true}")
                    boolean watermarkEnabled) {
        this.engine = engine;
        this.watermark = watermark;
        this.watermarkEnabled = watermarkEnabled;
    }

    /** 将主体、资源和一致性要求交给已绑定的判权目标，未知失败不能解释为允许。 */
    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed =
                engine.check(
                        req.subject(),
                        req.permission(),
                        req.resource(),
                        toConsistency(req.consistency()));
        return new CheckResponse(allowed);
    }

    /** 复用既有批量判权协议与一致性要求，调用方仍须按关联位置消费各项结果。 */
    @PostMapping("/check-bulk")
    public CheckBulkResponse checkBulk(@RequestBody CheckBulkRequest req) {
        Map<ResourceRef, Boolean> map =
                engine.checkBulk(
                        req.subject(),
                        req.permission(),
                        req.resources(),
                        toConsistency(req.consistency()));
        // 引擎响应必须精确覆盖每个请求资源：缺项/ null 是端口协议故障，抛出让 SDK 层 fail-closed，
        // 不再用 Boolean.TRUE.equals(null) 把漏项静默降级成 allowed=false（否则会把依赖故障伪装成 deny，
        // 且 SDK 的严格校验因 server 已补齐每个资源而无法察觉）。
        List<ResourceAllowed> results =
                req.resources().stream()
                        .map(
                                r -> {
                                    Boolean allowed = map.get(r);
                                    if (allowed == null) {
                                        throw new IllegalStateException(
                                                "check-bulk 引擎响应缺资源 " + r.ref() + " —— 判权结果不可信");
                                    }
                                    return new ResourceAllowed(r, allowed);
                                })
                        .toList();
        return new CheckBulkResponse(results);
    }

    /** 在当前主体与权限条件下查询资源，不能把缺失结果扩张成无范围访问。 */
    @PostMapping("/lookup-resources")
    public LookupResourcesResponse lookupResources(@RequestBody LookupResourcesRequest req) {
        return new LookupResourcesResponse(
                engine.lookupResources(
                        req.subject(),
                        req.permission(),
                        req.resourceType(),
                        toConsistency(req.consistency())));
    }

    /** 按给定资源与权限查询主体，查询结果不替代后续实际操作的授权检查。 */
    @PostMapping("/lookup-subjects")
    public LookupSubjectsResponse lookupSubjects(@RequestBody LookupSubjectsRequest req) {
        return new LookupSubjectsResponse(
                engine.lookupSubjects(
                        req.resource(),
                        req.permission(),
                        req.subjectType(),
                        toConsistency(req.consistency())));
    }

    /** 绑定原关系写入请求并交给现有引擎，保持旧接口令牌与错误契约。 */
    @PostMapping("/relationships")
    public TokenResponse write(@RequestBody WriteRequest req) {
        String token = engine.writeRelationships(req.updates()).token();
        watermark.advance(token);
        return new TokenResponse(token);
    }

    /** 绑定原关系删除请求并交给现有引擎，保持旧接口令牌与错误契约。 */
    @PostMapping("/relationships/delete")
    public TokenResponse delete(@RequestBody DeleteRequest req) {
        String token = engine.deleteRelationships(req.filter()).token();
        watermark.advance(token);
        return new TokenResponse(token);
    }

    /** 保留旧协议的模式读取入口，配置目标与错误行为由现有引擎负责。 */
    @org.springframework.web.bind.annotation.GetMapping("/schema")
    public Map<String, String> schema() {
        return Map.of("schema", engine.readSchema());
    }

    /** 沿用引擎的资源关系展开契约，诊断结果不能被当作执行授权回执。 */
    @PostMapping("/expand")
    public com.fasterxml.jackson.databind.JsonNode expand(@RequestBody ExpandRequest req) {
        try {
            return mapper.readTree(
                    engine.expand(
                            req.resource(), req.permission(), toConsistency(req.consistency())));
        } catch (Exception e) {
            throw new IllegalStateException("expand 解析失败: " + e.getMessage(), e);
        }
    }

    /** 保留原关系过滤条件与一致性模式读取事实，不把查询作为额外写入权威。 */
    @PostMapping("/relationships/read")
    public ReadRelationshipsResponse readRelationships(@RequestBody DeleteRequest req) {
        return new ReadRelationshipsResponse(engine.readRelationships(req.filter()));
    }

    private Consistency toConsistency(ConsistencyDto c) {
        if (c == null || c.mode() == null || c.mode().isBlank()) {
            return Consistency.minimizeLatency();
        }
        return switch (c.mode().toLowerCase(Locale.ROOT)) {
            case "full", "fully_consistent" -> Consistency.fullyConsistent();
            case "at_least_as_fresh" -> atLeastAsFresh(c.zedToken());
            default -> Consistency.minimizeLatency();
        };
    }

    /** at_least_as_fresh：调用方带 token 优先；没带则用本实例写水位；水位也没有时安全回退 full（宁慢勿漏读）。 */
    private Consistency atLeastAsFresh(String zedToken) {
        if (zedToken != null && !zedToken.isBlank()) {
            return Consistency.atLeastAsFresh(zedToken);
        }
        String wm = watermarkEnabled ? watermark.latest() : null;
        return wm != null ? Consistency.atLeastAsFresh(wm) : Consistency.fullyConsistent();
    }
}
