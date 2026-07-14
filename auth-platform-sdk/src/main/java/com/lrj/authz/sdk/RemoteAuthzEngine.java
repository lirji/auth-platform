package com.lrj.authz.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AuthzEngine 的消费方实现: 走 HTTP 调 auth-platform-server (:8200)。
 * 刻意不直连 SpiceDB gRPC —— 业务服务(如 knowledge-service)接入时保持 grpc-free。
 */
public class RemoteAuthzEngine implements AuthzEngine {

    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteAuthzEngine(String serverBaseUrl) {
        this.rest = RestClient.builder().baseUrl(serverBaseUrl).build();
    }

    @Override
    public boolean check(SubjectRef subject, String permission, ResourceRef resource, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("permission", permission);
        body.put("resource", resource);
        putConsistency(body, consistency);
        return post("/v1/check", body).path("allowed").asBoolean();
    }

    @Override
    public Map<ResourceRef, Boolean> checkBulk(SubjectRef subject, String permission, List<ResourceRef> resources, Consistency consistency) {
        Map<ResourceRef, Boolean> out = new LinkedHashMap<>();
        if (resources == null || resources.isEmpty()) {
            return out;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("permission", permission);
        body.put("resources", resources);
        putConsistency(body, consistency);
        JsonNode results = post("/v1/check-bulk", body).path("results");
        for (int i = 0; i < resources.size(); i++) {
            out.put(resources.get(i), results.path(i).path("allowed").asBoolean());
        }
        return out;
    }

    @Override
    public List<String> lookupResources(SubjectRef subject, String permission, String resourceType, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("permission", permission);
        body.put("resourceType", resourceType);
        putConsistency(body, consistency);
        List<String> ids = new ArrayList<>();
        post("/v1/lookup-resources", body).path("resourceIds").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    @Override
    public List<SubjectRef> lookupSubjects(ResourceRef resource, String permission, String subjectType, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("permission", permission);
        body.put("subjectType", subjectType);
        putConsistency(body, consistency);
        List<SubjectRef> subjects = new ArrayList<>();
        post("/v1/lookup-subjects", body).path("subjects").forEach(n ->
                subjects.add(new SubjectRef(n.path("type").asText(), n.path("id").asText(),
                        n.path("relation").isNull() ? null : n.path("relation").asText(null))));
        return subjects;
    }

    @Override
    public ZedTokenView writeRelationships(List<RelationshipUpdate> updates) {
        return new ZedTokenView(post("/v1/relationships", Map.of("updates", updates)).path("token").asText(null));
    }

    @Override
    public ZedTokenView deleteRelationships(RelationshipFilter filter) {
        return new ZedTokenView(post("/v1/relationships/delete", Map.of("filter", filter)).path("token").asText(null));
    }

    @Override
    public String readSchema() {
        String resp = rest.get().uri("/v1/schema").retrieve().body(String.class);
        try {
            return mapper.readTree(resp == null || resp.isBlank() ? "{}" : resp).path("schema").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("auth-platform-server 响应解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String expand(ResourceRef resource, String permission, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("permission", permission);
        putConsistency(body, consistency);
        return post("/v1/expand", body).toString();
    }

    @Override
    public List<Relationship> readRelationships(RelationshipFilter filter) {
        List<Relationship> out = new ArrayList<>();
        post("/v1/relationships/read", Map.of("filter", filter)).path("relationships").forEach(n ->
                out.add(new Relationship(
                        new ResourceRef(n.path("resource").path("type").asText(), n.path("resource").path("id").asText()),
                        n.path("relation").asText(),
                        new SubjectRef(n.path("subject").path("type").asText(), n.path("subject").path("id").asText(),
                                n.path("subject").path("relation").isNull() ? null : n.path("subject").path("relation").asText(null)))));
        return out;
    }

    private void putConsistency(Map<String, Object> body, Consistency c) {
        if (c == null) {
            return;
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("mode", switch (c.mode()) {
            case MINIMIZE_LATENCY -> "minimize_latency";
            case FULLY_CONSISTENT -> "full";
            case AT_LEAST_AS_FRESH -> "at_least_as_fresh";
        });
        if (c.zedToken() != null) {
            dto.put("zedToken", c.zedToken());
        }
        body.put("consistency", dto);
    }

    private JsonNode post(String path, Object body) {
        String resp = rest.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            return mapper.readTree(resp == null || resp.isBlank() ? "{}" : resp);
        } catch (Exception e) {
            throw new IllegalStateException("auth-platform-server 响应解析失败: " + e.getMessage(), e);
        }
    }
}
