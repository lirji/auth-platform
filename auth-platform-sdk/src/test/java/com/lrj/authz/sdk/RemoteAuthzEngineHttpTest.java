package com.lrj.authz.sdk;

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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteAuthzEngineHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>("{\"allowed\":true}");
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private RemoteAuthzEngine engine;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        engine = new RemoteAuthzEngine("http://127.0.0.1:" + server.getAddress().getPort(), "service-token",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void singleCheckRequiresBooleanAllowedAndSendsFreshness() throws Exception {
        boolean allowed = engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"),
                Consistency.atLeastAsFresh("zed-1"));

        assertThat(allowed).isTrue();
        assertThat(requestPath).hasValue("/v1/check");
        assertThat(authorization).hasValue("Bearer service-token");
        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.at("/consistency/mode").asText()).isEqualTo("at_least_as_fresh");
        assertThat(body.at("/consistency/zedToken").asText()).isEqualTo("zed-1");
        assertThat(body.at("/resource/id").asText()).isEqualTo("d1");

        response.set("{}");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
        assertThat(missing).hasMessageContaining("allowed");

        response.set("{\"allowed\":\"true\"}");
        assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
    }

    @Test
    void emptyBulkDoesNotPerformHttpRequest() {
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", null, null)).isEmpty();
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", List.of(), null)).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @Test
    void singleCheckMapsFalse() {
        response.set("{\"allowed\":false}");
        assertThat(engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null)).isFalse();
    }

    @Test
    void otherPortsMapResponsesAndUseCorrectPaths() throws Exception {
        SubjectRef subject = SubjectRef.user("u1");
        ResourceRef d1 = ResourceRef.of("document", "d1");

        response.set("{\"resourceIds\":[\"d1\",\"d2\"]}");
        assertThat(engine.lookupResources(subject, "view", "document", null)).containsExactly("d1", "d2");
        assertThat(requestPath).hasValue("/v1/lookup-resources");
        assertThat(mapper.readTree(requestBody.get()).at("/resourceType").asText()).isEqualTo("document");

        response.set("{\"subjects\":[{\"type\":\"user\",\"id\":\"u1\",\"relation\":null},"
                + "{\"type\":\"group\",\"id\":\"g1\",\"relation\":\"member\"}]}");
        assertThat(engine.lookupSubjects(d1, "view", "user", null))
                .containsExactly(SubjectRef.user("u1"), SubjectRef.ofRelation("group", "g1", "member"));
        assertThat(requestPath).hasValue("/v1/lookup-subjects");

        response.set("{\"token\":\"zed-w\"}");
        assertThat(engine.writeRelationships(List.of(
                RelationshipUpdate.touch(d1, "viewer", subject))).token()).isEqualTo("zed-w");
        assertThat(requestPath).hasValue("/v1/relationships");

        response.set("{\"token\":\"zed-d\"}");
        assertThat(engine.deleteRelationships(RelationshipFilter.ofResource(d1)).token()).isEqualTo("zed-d");
        assertThat(requestPath).hasValue("/v1/relationships/delete");

        response.set("{\"schema\":\"definition user {}\"}");
        assertThat(engine.readSchema()).isEqualTo("definition user {}");
        assertThat(requestPath).hasValue("/v1/schema");

        response.set("{\"treeRoot\":{}}");
        assertThat(mapper.readTree(engine.expand(d1, "view", null)).has("treeRoot")).isTrue();
        assertThat(requestPath).hasValue("/v1/expand");

        response.set("{\"relationships\":[{\"resource\":{\"type\":\"document\",\"id\":\"d1\"},"
                + "\"relation\":\"viewer\",\"subject\":{\"type\":\"user\",\"id\":\"u1\",\"relation\":null}}]}");
        assertThat(engine.readRelationships(RelationshipFilter.ofResource(d1)))
                .containsExactly(new Relationship(d1, "viewer", SubjectRef.user("u1")));
        assertThat(requestPath).hasValue("/v1/relationships/read");
    }

    @Test
    void omittingTokenSendsNoAuthorizationHeader() {
        RemoteAuthzEngine anonymous = new RemoteAuthzEngine(
                "http://127.0.0.1:" + server.getAddress().getPort());
        response.set("{\"allowed\":true}");

        anonymous.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), null);

        assertThat(authorization.get()).isNull();
    }
}
