package com.lrj.authz.server;

import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.server.AuthzDtos.*;
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
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AuthzController(AuthzEngine engine, ZedTokenWatermark watermark,
                           @org.springframework.beans.factory.annotation.Value("${authz.server.zed-token-watermark-enabled:true}") boolean watermarkEnabled) {
        this.engine = engine;
        this.watermark = watermark;
        this.watermarkEnabled = watermarkEnabled;
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed = engine.check(req.subject(), req.permission(), req.resource(), toConsistency(req.consistency()));
        return new CheckResponse(allowed);
    }

    @PostMapping("/check-bulk")
    public CheckBulkResponse checkBulk(@RequestBody CheckBulkRequest req) {
        Map<ResourceRef, Boolean> map = engine.checkBulk(req.subject(), req.permission(), req.resources(), toConsistency(req.consistency()));
        // 引擎响应必须精确覆盖每个请求资源：缺项/ null 是端口协议故障，抛出让 SDK 层 fail-closed，
        // 不再用 Boolean.TRUE.equals(null) 把漏项静默降级成 allowed=false（否则会把依赖故障伪装成 deny，
        // 且 SDK 的严格校验因 server 已补齐每个资源而无法察觉）。
        List<ResourceAllowed> results = req.resources().stream()
                .map(r -> {
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

    @PostMapping("/lookup-resources")
    public LookupResourcesResponse lookupResources(@RequestBody LookupResourcesRequest req) {
        return new LookupResourcesResponse(
                engine.lookupResources(req.subject(), req.permission(), req.resourceType(), toConsistency(req.consistency())));
    }

    @PostMapping("/lookup-subjects")
    public LookupSubjectsResponse lookupSubjects(@RequestBody LookupSubjectsRequest req) {
        return new LookupSubjectsResponse(
                engine.lookupSubjects(req.resource(), req.permission(), req.subjectType(), toConsistency(req.consistency())));
    }

    @PostMapping("/relationships")
    public TokenResponse write(@RequestBody WriteRequest req) {
        String token = engine.writeRelationships(req.updates()).token();
        watermark.advance(token);
        return new TokenResponse(token);
    }

    @PostMapping("/relationships/delete")
    public TokenResponse delete(@RequestBody DeleteRequest req) {
        String token = engine.deleteRelationships(req.filter()).token();
        watermark.advance(token);
        return new TokenResponse(token);
    }

    @org.springframework.web.bind.annotation.GetMapping("/schema")
    public Map<String, String> schema() {
        return Map.of("schema", engine.readSchema());
    }

    @PostMapping("/expand")
    public com.fasterxml.jackson.databind.JsonNode expand(@RequestBody ExpandRequest req) {
        try {
            return mapper.readTree(engine.expand(req.resource(), req.permission(), toConsistency(req.consistency())));
        } catch (Exception e) {
            throw new IllegalStateException("expand 解析失败: " + e.getMessage(), e);
        }
    }

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
