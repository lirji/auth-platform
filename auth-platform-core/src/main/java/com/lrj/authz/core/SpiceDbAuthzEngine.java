package com.lrj.authz.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * AuthzEngine 的 SpiceDB 实现, 走 SpiceDB 的 HTTP/JSON API (默认 :8543)。
 * 刻意不引 authzed-java gRPC —— 保持 grpc-free, 避开与 langchain4j 根 pom 的钻石依赖冲突。
 * 需要极致性能时可另加一个基于 gRPC 的 AuthzEngine 实现替换, 上层零改。
 */
public class SpiceDbAuthzEngine implements AuthzEngine {

    private static final String HAS_PERMISSION = "PERMISSIONSHIP_HAS_PERMISSION";

    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpiceDbAuthzEngine(String baseUrl, String presharedKey) {
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBearerAuth(presharedKey))
                .build();
    }

    @Override
    public boolean check(SubjectRef subject, String permission, ResourceRef resource, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(consistency));
        body.put("resource", objectJson(resource));
        body.put("permission", permission);
        body.put("subject", subjectJson(subject));
        JsonNode resp = post("/v1/permissions/check", body);
        return hasPermission(resp, "check");
    }

    @Override
    public Map<ResourceRef, Boolean> checkBulk(SubjectRef subject, String permission, List<ResourceRef> resources, Consistency consistency) {
        Map<ResourceRef, Boolean> out = new LinkedHashMap<>();
        if (resources == null || resources.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ResourceRef r : resources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resource", objectJson(r));
            item.put("permission", permission);
            item.put("subject", subjectJson(subject));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(consistency));
        body.put("items", items);
        JsonNode resp = post("/v1/permissions/checkbulk", body);
        JsonNode pairs = resp.path("pairs");
        // 严格校验 pairs 基数：SpiceDB checkbulk 按请求顺序回 pairs，缺项/多项都说明响应不可信 → 抛协议异常
        // （否则按下标盲映射会把漏项静默当 deny）。
        if (!pairs.isArray() || pairs.size() != resources.size()) {
            throw new IllegalStateException("SpiceDB checkbulk 响应 pairs 基数不符: 期望 " + resources.size()
                    + ", 实际 " + (pairs.isArray() ? String.valueOf(pairs.size()) : "非数组/缺失") + " —— 判权结果不可信");
        }
        for (int i = 0; i < resources.size(); i++) {
            JsonNode pair = pairs.path(i);
            // pair 是 oneof(item|error)：某项返回 error（如 SpiceDB 内部错误）不能静默折成 deny，抛出让上游按依赖故障处理。
            if (pair.hasNonNull("error")) {
                throw new IllegalStateException("SpiceDB checkbulk 第 " + i + " 项返回 error(code="
                        + pair.path("error").path("code").asText("?") + ") —— 判权结果不可信");
            }
            out.put(resources.get(i), hasPermission(pair.path("item"), "checkbulk[" + i + "]"));
        }
        return out;
    }

    /**
     * 从 SpiceDB check/checkbulk 的结果节点严格取 permissionship：缺失/空即抛 {@link IllegalStateException}（协议异常）。
     * 不再把"没有 permissionship"静默折成 false —— 否则 SpiceDB 侧的错误/畸形会被伪装成普通 deny，上游 shadow
     * 会把依赖错误错记为 deny（掩盖故障）。抛出后经 server→SDK 的 HTTP 层表现为依赖错误：knowledge 侧 enforce
     * fail-closed、shadow 记 error 指标（与 SDK 的 F3 严格校验对称，端到端不再"错误伪装成 deny"）。
     * NO_PERMISSION 等合法否定仍返回 false（不改变有效判权语义）。
     */
    private static boolean hasPermission(JsonNode item, String op) {
        String ship = item.path("permissionship").asText("");
        if (ship.isEmpty()) {
            throw new IllegalStateException("SpiceDB " + op + " 响应缺 permissionship —— 判权结果不可信");
        }
        return HAS_PERMISSION.equals(ship);
    }

    @Override
    public List<String> lookupResources(SubjectRef subject, String permission, String resourceType, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(consistency));
        body.put("resourceObjectType", resourceType);
        body.put("permission", permission);
        body.put("subject", subjectJson(subject));
        List<String> ids = new ArrayList<>();
        for (JsonNode msg : postStream("/v1/permissions/resources", body)) {
            JsonNode result = msg.path("result");
            // LookupResources 用 LOOKUP_PERMISSIONSHIP_HAS_PERMISSION (带 LOOKUP_ 前缀), 故用 endsWith 兼容。
            String permissionship = result.path("permissionship").asText("");
            if (permissionship.isEmpty() || permissionship.endsWith("HAS_PERMISSION")) {
                JsonNode id = result.path("resourceObjectId");
                if (!id.isMissingNode()) {
                    ids.add(id.asText());
                }
            }
        }
        return ids;
    }

    @Override
    public List<SubjectRef> lookupSubjects(ResourceRef resource, String permission, String subjectType, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(consistency));
        body.put("resource", objectJson(resource));
        body.put("permission", permission);
        body.put("subjectObjectType", subjectType);
        List<SubjectRef> subjects = new ArrayList<>();
        for (JsonNode msg : postStream("/v1/permissions/subjects", body)) {
            JsonNode id = msg.path("result").path("subject").path("subjectObjectId");
            if (!id.isMissingNode()) {
                subjects.add(SubjectRef.of(subjectType, id.asText()));
            }
        }
        return subjects;
    }

    @Override
    public ZedTokenView writeRelationships(List<RelationshipUpdate> updates) {
        List<Map<String, Object>> jsonUpdates = new ArrayList<>();
        for (RelationshipUpdate u : updates) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("resource", objectJson(u.resource()));
            rel.put("relation", u.relation());
            rel.put("subject", subjectJson(u.subject()));
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("operation", "OPERATION_" + u.operation().name());
            upd.put("relationship", rel);
            jsonUpdates.add(upd);
        }
        JsonNode resp = post("/v1/relationships/write", Map.of("updates", jsonUpdates));
        return new ZedTokenView(resp.path("writtenAt").path("token").asText(null));
    }

    @Override
    public ZedTokenView deleteRelationships(RelationshipFilter filter) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("resourceType", filter.resourceType());
        if (filter.resourceId() != null) {
            f.put("optionalResourceId", filter.resourceId());
        }
        if (filter.relation() != null) {
            f.put("optionalRelation", filter.relation());
        }
        JsonNode resp = post("/v1/relationships/delete", Map.of("relationshipFilter", f));
        return new ZedTokenView(resp.path("deletedAt").path("token").asText(null));
    }

    @Override
    public String readSchema() {
        return post("/v1/schema/read", Map.of()).path("schemaText").asText("");
    }

    @Override
    public String expand(ResourceRef resource, String permission, Consistency consistency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(consistency));
        body.put("resource", objectJson(resource));
        body.put("permission", permission);
        return post("/v1/permissions/expand", body).toString();
    }

    @Override
    public List<Relationship> readRelationships(RelationshipFilter filter) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("resourceType", filter.resourceType());
        if (filter.resourceId() != null) {
            f.put("optionalResourceId", filter.resourceId());
        }
        if (filter.relation() != null) {
            f.put("optionalRelation", filter.relation());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consistency", consistencyJson(Consistency.fullyConsistent()));
        body.put("relationshipFilter", f);
        List<Relationship> out = new ArrayList<>();
        for (JsonNode msg : postStream("/v1/relationships/read", body)) {
            JsonNode rel = msg.path("result").path("relationship");
            if (rel.isMissingNode()) {
                continue;
            }
            JsonNode res = rel.path("resource");
            JsonNode subObj = rel.path("subject").path("object");
            String subRel = rel.path("subject").path("optionalRelation").asText("");
            out.add(new Relationship(
                    ResourceRef.of(res.path("objectType").asText(), res.path("objectId").asText()),
                    rel.path("relation").asText(),
                    new SubjectRef(subObj.path("objectType").asText(), subObj.path("objectId").asText(),
                            subRel.isEmpty() ? null : subRel)));
        }
        return out;
    }

    // --- JSON 构造 ---

    private Map<String, Object> objectJson(ResourceRef r) {
        return Map.of("objectType", r.type(), "objectId", r.id());
    }

    private Map<String, Object> subjectJson(SubjectRef s) {
        Map<String, Object> obj = Map.of("objectType", s.type(), "objectId", s.id());
        if (s.relation() != null && !s.relation().isBlank()) {
            return Map.of("object", obj, "optionalRelation", s.relation());
        }
        return Map.of("object", obj);
    }

    private Map<String, Object> consistencyJson(Consistency c) {
        if (c == null) {
            return Map.of("minimizeLatency", true);
        }
        return switch (c.mode()) {
            case MINIMIZE_LATENCY -> Map.of("minimizeLatency", true);
            case FULLY_CONSISTENT -> Map.of("fullyConsistent", true);
            case AT_LEAST_AS_FRESH -> Map.of("atLeastAsFresh", Map.of("token", c.zedToken()));
        };
    }

    // --- HTTP ---

    private JsonNode post(String path, Object body) {
        String resp = rest.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            return mapper.readTree(resp == null || resp.isBlank() ? "{}" : resp);
        } catch (Exception e) {
            throw new IllegalStateException("SpiceDB 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 服务端流式端点 (lookup-resources/subjects): 响应为若干顶层 JSON 对象的拼接。 */
    private List<JsonNode> postStream(String path, Object body) {
        String resp = rest.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        List<JsonNode> out = new ArrayList<>();
        if (resp == null || resp.isBlank()) {
            return out;
        }
        try (JsonParser p = mapper.getFactory().createParser(resp)) {
            Iterator<JsonNode> it = mapper.readValues(p, JsonNode.class);
            while (it.hasNext()) {
                out.add(it.next());
            }
        } catch (Exception e) {
            throw new IllegalStateException("SpiceDB 流式响应解析失败: " + e.getMessage(), e);
        }
        return out;
    }
}
