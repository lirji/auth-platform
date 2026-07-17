package com.lrj.authz.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpiceDbAuthzEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private HttpServer server;
    private SpiceDbAuthzEngine engine;

    private record Captured(String path, String authorization, String contentType, String body) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        engine = new SpiceDbAuthzEngine(
                "http://127.0.0.1:" + server.getAddress().getPort(), "spice-secret",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        captured.set(new Captured(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(requestBytes, StandardCharsets.UTF_8)));
        requestCount.incrementAndGet();
        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respond(String body) {
        status.set(200);
        response.set(body);
    }

    private JsonNode requestJson() throws Exception {
        return mapper.readTree(captured.get().body());
    }

    @Test
    void checkSerializesUsersetAndFreshness() throws Exception {
        respond("{\"permissionship\":\"PERMISSIONSHIP_HAS_PERMISSION\"}");

        boolean allowed = engine.check(
                SubjectRef.ofRelation("group", "acme_eng", "member"), "view",
                ResourceRef.of("document", "d1"), Consistency.atLeastAsFresh("zed-7"));

        assertThat(allowed).isTrue();
        assertThat(captured.get().path()).isEqualTo("/v1/permissions/check");
        assertThat(captured.get().authorization()).isEqualTo("Bearer spice-secret");
        assertThat(captured.get().contentType()).startsWith("application/json");
        JsonNode body = requestJson();
        assertThat(body.at("/consistency/atLeastAsFresh/token").asText()).isEqualTo("zed-7");
        assertThat(body.at("/resource/objectType").asText()).isEqualTo("document");
        assertThat(body.at("/resource/objectId").asText()).isEqualTo("d1");
        assertThat(body.path("permission").asText()).isEqualTo("view");
        assertThat(body.at("/subject/object/objectType").asText()).isEqualTo("group");
        assertThat(body.at("/subject/optionalRelation").asText()).isEqualTo("member");
    }

    @Test
    void checkReturnsFalseForNoPermissionAndRejectsMissingPermissionship() {
        respond("{\"permissionship\":\"PERMISSIONSHIP_NO_PERMISSION\"}");
        assertThat(engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null)).isFalse();

        respond("{}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
        assertThat(ex).hasMessageContaining("permissionship");
    }

    @Test
    void serializesOtherConsistencyModes() throws Exception {
        respond("{\"permissionship\":\"PERMISSIONSHIP_NO_PERMISSION\"}");
        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), null);
        assertThat(requestJson().at("/consistency/minimizeLatency").asBoolean()).isTrue();

        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), Consistency.fullyConsistent());
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();

        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), Consistency.minimizeLatency());
        assertThat(requestJson().at("/consistency/minimizeLatency").asBoolean()).isTrue();
    }

    @Test
    void emptyBulkDoesNotCallServer() {
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", null, null)).isEmpty();
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", List.of(), null)).isEmpty();
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void bulkMapsPairsInRequestOrder() throws Exception {
        respond("""
                {"pairs":[
                  {"item":{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}},
                  {"item":{"permissionship":"PERMISSIONSHIP_NO_PERMISSION"}}
                ]}
                """);
        ResourceRef d1 = ResourceRef.of("document", "d1");
        ResourceRef d2 = ResourceRef.of("document", "d2");

        var result = engine.checkBulk(SubjectRef.user("u1"), "view", List.of(d1, d2), Consistency.fullyConsistent());

        assertThat(result).containsEntry(d1, true).containsEntry(d2, false).hasSize(2);
        assertThat(result.keySet()).containsExactly(d1, d2);
        assertThat(requestJson().path("items").size()).isEqualTo(2);
        assertThat(requestJson().at("/items/1/resource/objectId").asText()).isEqualTo("d2");
    }

    @Test
    void bulkRejectsWrongCardinalityAndPerItemError() {
        ResourceRef d1 = ResourceRef.of("document", "d1");
        respond("{\"pairs\":[]}");
        assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null));

        respond("{\"pairs\":[{\"error\":{\"code\":\"INTERNAL\"}}]}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null));
        assertThat(ex).hasMessageContaining("INTERNAL");
    }

    @Test
    void bulkRejectsNonArrayPairsExtraItemsAndMissingItemPermissionship() {
        ResourceRef d1 = ResourceRef.of("document", "d1");

        // pairs 非数组（对象）：判权响应不可信 → 抛
        respond("{\"pairs\":{\"item\":{}}}");
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null)))
                .hasMessageContaining("基数不符");

        // 多项（请求 1、返回 2）：基数不符 → 抛
        respond("""
                {"pairs":[
                  {"item":{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}},
                  {"item":{"permissionship":"PERMISSIONSHIP_NO_PERMISSION"}}
                ]}
                """);
        assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null));

        // item 缺 permissionship：与 check 严格语义对称 → 抛
        respond("{\"pairs\":[{\"item\":{}}]}");
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null)))
                .hasMessageContaining("permissionship");
    }

    @Test
    void lookupResourcesSerializesRequestAndReturnsEmptyOnBlankStream() throws Exception {
        respond("{\"result\":{\"permissionship\":\"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION\",\"resourceObjectId\":\"d1\"}}");
        assertThat(engine.lookupResources(SubjectRef.user("u1"), "view", "document", Consistency.fullyConsistent()))
                .containsExactly("d1");
        assertThat(captured.get().path()).isEqualTo("/v1/permissions/resources");
        JsonNode body = requestJson();
        assertThat(body.path("resourceObjectType").asText()).isEqualTo("document");
        assertThat(body.path("permission").asText()).isEqualTo("view");
        assertThat(body.at("/subject/object/objectId").asText()).isEqualTo("u1");
        assertThat(body.at("/consistency/fullyConsistent").asBoolean()).isTrue();

        respond("");
        assertThat(engine.lookupResources(SubjectRef.user("u1"), "view", "document", null)).isEmpty();
    }

    @Test
    void lookupSubjectsSerializesRequestFields() throws Exception {
        respond("{\"result\":{\"subject\":{\"subjectObjectId\":\"u1\"}}}");
        assertThat(engine.lookupSubjects(ResourceRef.of("document", "d1"), "view", "user", null))
                .containsExactly(SubjectRef.user("u1"));
        assertThat(captured.get().path()).isEqualTo("/v1/permissions/subjects");
        JsonNode body = requestJson();
        assertThat(body.path("subjectObjectType").asText()).isEqualTo("user");
        assertThat(body.at("/resource/objectId").asText()).isEqualTo("d1");
    }

    @Test
    void deleteAndExpandUseCorrectPaths() throws Exception {
        respond("{\"deletedAt\":{\"token\":\"zed-delete\"}}");
        engine.deleteRelationships(RelationshipFilter.of("document", "d1", "viewer"));
        assertThat(captured.get().path()).isEqualTo("/v1/relationships/delete");

        respond("{\"treeRoot\":{}}");
        engine.expand(ResourceRef.of("document", "d1"), "view", null);
        assertThat(captured.get().path()).isEqualTo("/v1/permissions/expand");
        JsonNode body = requestJson();
        assertThat(body.at("/resource/objectId").asText()).isEqualTo("d1");
        assertThat(body.path("permission").asText()).isEqualTo("view");
        assertThat(body.at("/consistency/minimizeLatency").asBoolean()).isTrue();
    }

    @Test
    void lookupResourcesParsesConcatenatedJson() {
        respond("""
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION","resourceObjectId":"d1"}}
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_CONDITIONAL_PERMISSION","resourceObjectId":"d2"}}
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION","resourceObjectId":"d3"}}
                """);

        // CONDITIONAL 被 fail-closed 排除，只保留明确 HAS_PERMISSION 的 d1/d3。
        assertThat(engine.lookupResources(SubjectRef.user("u"), "view", "document", null))
                .containsExactly("d1", "d3");
    }

    @Test
    void lookupResourcesRejectsMissingPermissionship() {
        // C01 已修：缺 permissionship 的 result 不再被 fail-open 收录，而是抛协议异常（与 check 对称）。
        respond("{\"result\":{\"resourceObjectId\":\"secret\"}}");
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.lookupResources(SubjectRef.user("u"), "view", "document", null)))
                .hasMessageContaining("permissionship");
    }

    @Test
    void lookupSubjectsParsesStream() {
        respond("""
                {"result":{"subject":{"subjectObjectId":"u1"}}}
                {"result":{"subject":{"subjectObjectId":"u2"}}}
                """);
        assertThat(engine.lookupSubjects(ResourceRef.of("document", "d"), "view", "user", null))
                .containsExactly(SubjectRef.user("u1"), SubjectRef.user("u2"));
    }

    @Test
    void streamTopLevelErrorFailsWholeCall() {
        // C02 已修：流中出现顶层 error 消息不再被静默跳过，整次调用抛异常（不返回可信部分结果）。
        respond("""
                {"result":{"subject":{"subjectObjectId":"u1"}}}
                {"error":{"code":"internal"}}
                """);
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.lookupSubjects(ResourceRef.of("document", "d"), "view", "user", null)))
                .hasMessageContaining("error");

        respond("""
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION","resourceObjectId":"d1"}}
                {"error":{"code":"internal"}}
                """);
        assertThrows(IllegalStateException.class, () ->
                engine.lookupResources(SubjectRef.user("u"), "view", "document", null));
    }

    @Test
    void writeRelationshipsSerializesOperationsAndReturnsToken() throws Exception {
        respond("{\"writtenAt\":{\"token\":\"zed-write\"}}");
        var updates = List.of(
                RelationshipUpdate.create(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1")),
                RelationshipUpdate.touch(ResourceRef.of("document", "d1"), "viewer",
                        SubjectRef.ofRelation("group", "acme_eng", "member")),
                RelationshipUpdate.delete(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u2")));

        assertThat(engine.writeRelationships(updates).token()).isEqualTo("zed-write");
        JsonNode body = requestJson();
        assertThat(captured.get().path()).isEqualTo("/v1/relationships/write");
        assertThat(body.at("/updates/0/operation").asText()).isEqualTo("OPERATION_CREATE");
        assertThat(body.at("/updates/1/operation").asText()).isEqualTo("OPERATION_TOUCH");
        assertThat(body.at("/updates/1/relationship/subject/optionalRelation").asText()).isEqualTo("member");
        assertThat(body.at("/updates/2/operation").asText()).isEqualTo("OPERATION_DELETE");
    }

    @Test
    void writeAndDeleteRejectMissingToken() {
        // C04 已修：写/删响应缺 token（空 writtenAt/deletedAt）不再静默成 ZedTokenView(null)，而是抛协议异常。
        respond("{\"writtenAt\":{}}");
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.writeRelationships(List.of(
                        RelationshipUpdate.touch(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1"))))))
                .hasMessageContaining("writtenAt.token");

        respond("{}");
        assertThat(assertThrows(IllegalStateException.class, () ->
                engine.deleteRelationships(RelationshipFilter.of("document", "d1", "viewer"))))
                .hasMessageContaining("deletedAt.token");
    }

    @Test
    void deleteRelationshipsHonorsOptionalFilterFields() throws Exception {
        respond("{\"deletedAt\":{\"token\":\"zed-delete\"}}");
        assertThat(engine.deleteRelationships(RelationshipFilter.of("document", "d1", "viewer")).token())
                .isEqualTo("zed-delete");
        JsonNode full = requestJson().path("relationshipFilter");
        assertThat(full.path("resourceType").asText()).isEqualTo("document");
        assertThat(full.path("optionalResourceId").asText()).isEqualTo("d1");
        assertThat(full.path("optionalRelation").asText()).isEqualTo("viewer");

        engine.deleteRelationships(RelationshipFilter.of("document", null, null));
        JsonNode wildcard = requestJson().path("relationshipFilter");
        assertThat(wildcard.has("optionalResourceId")).isFalse();
        assertThat(wildcard.has("optionalRelation")).isFalse();
    }

    @Test
    void readsSchemaAndExpandTree() throws Exception {
        respond("{\"schemaText\":\"definition user {}\"}");
        assertThat(engine.readSchema()).isEqualTo("definition user {}");
        assertThat(captured.get().path()).isEqualTo("/v1/schema/read");

        respond("{\"treeRoot\":{\"leaf\":{\"subjects\":[]}}}");
        String tree = engine.expand(ResourceRef.of("document", "d"), "view", Consistency.fullyConsistent());
        assertThat(mapper.readTree(tree).at("/treeRoot/leaf/subjects").isArray()).isTrue();
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();
    }

    @Test
    void readRelationshipsParsesDirectAndUsersetTuples() throws Exception {
        respond("""
                {"result":{"relationship":{"resource":{"objectType":"document","objectId":"d1"},
                  "relation":"viewer","subject":{"object":{"objectType":"user","objectId":"u1"}}}}}
                {"result":{"relationship":{"resource":{"objectType":"document","objectId":"d1"},
                  "relation":"viewer","subject":{"object":{"objectType":"group","objectId":"acme_eng"},
                  "optionalRelation":"member"}}}}
                """);

        List<Relationship> result = engine.readRelationships(RelationshipFilter.ofResource(ResourceRef.of("document", "d1")));

        assertThat(result).containsExactly(
                new Relationship(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1")),
                new Relationship(ResourceRef.of("document", "d1"), "viewer",
                        SubjectRef.ofRelation("group", "acme_eng", "member")));
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();
        // TODO(issue-C02): 内部 objectType/objectId/relation 缺失必须抛。
    }

    @Test
    void rejectsMalformedJsonAndHttpError() {
        respond("not-json");
        assertThrows(IllegalStateException.class, () -> engine.readSchema());

        status.set(503);
        response.set("{\"error\":\"unavailable\"}");
        assertThrows(RestClientResponseException.class, () ->
                engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), null));
    }
}
