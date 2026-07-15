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
        this(serverBaseUrl, null);
    }

    public RemoteAuthzEngine(String serverBaseUrl, String token) {
        this(serverBaseUrl, token, java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5));
    }

    /**
     * @param token          service credential (Bearer)；空/null 则不带 Authorization 头。
     * @param connectTimeout 连接超时（防判权服务不可达时长时间占用请求线程）。
     * @param readTimeout    读超时。
     */
    public RemoteAuthzEngine(String serverBaseUrl, String token,
                             java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        RestClient.Builder builder = RestClient.builder().requestFactory(factory).baseUrl(serverBaseUrl);
        if (token != null && !token.isBlank()) {
            builder = builder.defaultHeaders(h -> h.setBearerAuth(token));
        }
        this.rest = builder.build();
    }

    @Override
    public boolean check(SubjectRef subject, String permission, ResourceRef resource, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("permission", permission);
        body.put("resource", resource);
        putConsistency(body, consistency);
        return requireAllowed(post("/v1/check", body), "check");
    }

    @Override
    public Map<ResourceRef, Boolean> checkBulk(SubjectRef subject, String permission, List<ResourceRef> resources, Consistency consistency) {
        if (resources == null || resources.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("permission", permission);
        body.put("resources", resources);
        putConsistency(body, consistency);
        return parseCheckBulk(post("/v1/check-bulk", body), resources);
    }

    /**
     * 解析并<strong>严格校验</strong> check-bulk 响应：{@code results} 必须是数组、基数与请求一致、每个请求资源
     * 恰好出现一次、无未请求资源、无缺字段。任一不满足抛 {@link IllegalStateException}（协议异常）——上游 enforce
     * 会据此 fail-closed。否则旧的按下标盲映射会把"漏项"静默当成 {@code allowed=false}（deny），或在错位/乱序时
     * 把 A 的判定安到 B 头上。要求请求内 resources 互不相同（调用方 knowledge-service 已按 docId 去重）。
     */
    static Map<ResourceRef, Boolean> parseCheckBulk(JsonNode root, List<ResourceRef> resources) {
        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() != resources.size()) {
            throw new IllegalStateException("check-bulk 响应基数不符: 期望 " + resources.size()
                    + " 条, 实际 " + (results.isArray() ? String.valueOf(results.size()) : "非数组/缺失")
                    + " —— 判权结果不可信");
        }
        java.util.Set<ResourceRef> requested = new java.util.HashSet<>(resources);
        Map<ResourceRef, Boolean> parsed = new LinkedHashMap<>();
        for (JsonNode item : results) {
            JsonNode res = item.path("resource");
            if (!res.hasNonNull("type") || !res.hasNonNull("id")) {
                throw new IllegalStateException("check-bulk 响应项缺少 resource.type/id");
            }
            ResourceRef ref = new ResourceRef(res.get("type").asText(), res.get("id").asText());
            if (!requested.contains(ref)) {
                throw new IllegalStateException("check-bulk 响应含未请求的资源: " + ref.ref());
            }
            if (parsed.putIfAbsent(ref, requireAllowed(item, "check-bulk")) != null) {
                throw new IllegalStateException("check-bulk 响应资源重复: " + ref.ref());
            }
        }
        // 基数相等 + 无重复 + 无未请求 ⇒ 每个请求资源恰好出现一次；按请求顺序回填。
        Map<ResourceRef, Boolean> out = new LinkedHashMap<>();
        for (ResourceRef r : resources) {
            out.put(r, parsed.get(r));
        }
        return out;
    }

    /**
     * 严格提取判权响应里的 {@code allowed} 布尔字段：必须<strong>存在且为 JSON boolean</strong>，否则抛
     * {@link IllegalStateException}（协议异常，上游 enforce 据此 fail-closed、shadow 记为 error）。
     *
     * <p>旧实现 {@code path("allowed").asBoolean()} 会把缺失/错类型（如 server 版本漂移、代理截断、畸形 JSON）
     * <strong>静默当成 false(deny)</strong>——虽 fail-closed 不越权，但会掩盖协议错误，并把畸形响应错记成
     * shadow 的 deny 指标（污染灰度评估）。异常信息只暴露节点类型，不含响应 body（避免泄漏）。
     */
    private static boolean requireAllowed(JsonNode item, String op) {
        JsonNode allowed = item.path("allowed");
        if (!allowed.isBoolean()) {
            throw new IllegalStateException(op + " 响应缺少布尔字段 allowed(实际类型: "
                    + (allowed.isMissingNode() ? "缺失" : allowed.getNodeType()) + ") —— 判权结果不可信");
        }
        return allowed.booleanValue();
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
