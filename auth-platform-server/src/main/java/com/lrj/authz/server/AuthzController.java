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

/** 判权 REST 端点。SDK 的 RemoteAuthzEngine 调这里。 */
@RestController
@RequestMapping("/v1")
public class AuthzController {

    private final AuthzEngine engine;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AuthzController(AuthzEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed = engine.check(req.subject(), req.permission(), req.resource(), toConsistency(req.consistency()));
        return new CheckResponse(allowed);
    }

    @PostMapping("/check-bulk")
    public CheckBulkResponse checkBulk(@RequestBody CheckBulkRequest req) {
        Map<ResourceRef, Boolean> map = engine.checkBulk(req.subject(), req.permission(), req.resources(), toConsistency(req.consistency()));
        List<ResourceAllowed> results = req.resources().stream()
                .map(r -> new ResourceAllowed(r, Boolean.TRUE.equals(map.get(r))))
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
        return new TokenResponse(engine.writeRelationships(req.updates()).token());
    }

    @PostMapping("/relationships/delete")
    public TokenResponse delete(@RequestBody DeleteRequest req) {
        return new TokenResponse(engine.deleteRelationships(req.filter()).token());
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

    private static Consistency toConsistency(ConsistencyDto c) {
        if (c == null || c.mode() == null || c.mode().isBlank()) {
            return Consistency.minimizeLatency();
        }
        return switch (c.mode().toLowerCase(Locale.ROOT)) {
            case "full", "fully_consistent" -> Consistency.fullyConsistent();
            case "at_least_as_fresh" -> Consistency.atLeastAsFresh(c.zedToken());
            default -> Consistency.minimizeLatency();
        };
    }
}
