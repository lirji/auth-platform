package com.lrj.authz.admin.casdoor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CasdoorClientTest {

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> seenPaths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private CasdoorProperties props;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        props = new CasdoorProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setClientId("client");
        props.setClientSecret("secret");
        props.setOrganization("acme");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        seenPaths.add(key);
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void effectiveOrganizationsUsesListOrFallback() {
        assertThat(props.effectiveOrganizations()).containsExactly("acme");
        props.setOrganizations(List.of("acme", "beta"));
        assertThat(props.effectiveOrganizations()).containsExactly("acme", "beta");
        props.setOrganizations(null);
        assertThat(props.effectiveOrganizations()).containsExactly("acme");
    }

    @Test
    void groupMembersScopesAndDeduplicatesAcrossOrganizations() {
        props.setOrganizations(List.of("acme", "beta"));
        responses.put("/api/get-users?owner=acme", """
                {"data":[
                  {"name":"alice","id":"sub-a","groups":["eng","acme/eng",""]},
                  {"name":"missing-id","groups":["eng"]}
                ]}
                """);
        responses.put("/api/get-users?owner=beta", """
                {"data":[{"name":"bob","id":"sub-b","groups":["eng"]}]}
                """);

        Map<String, Set<String>> result = new CasdoorClient(props).groupMembers();

        assertThat(result).containsEntry("acme_eng", Set.of("sub-a"))
                .containsEntry("beta_eng", Set.of("sub-b"));
        assertThat(result).hasSize(2);
        assertThat(seenPaths).containsExactly("/api/get-users?owner=acme", "/api/get-users?owner=beta");
        String expected = "Basic " + Base64.getEncoder().encodeToString("client:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders).allMatch(expected::equals);
        // TODO(issue-CAS03): acme 用户引用 beta/eng 应 fail-closed；当前会生成 beta_eng。
    }

    @Test
    void nameSubjectFieldUsesUserName() {
        props.setSubjectField("name");
        responses.put("/api/get-users?owner=acme", """
                {"data":[{"name":"alice","id":"opaque-id","groups":["eng"]}]}
                """);

        assertThat(new CasdoorClient(props).groupMembers())
                .containsEntry("acme_eng", Set.of("alice"));
    }

    @Test
    void groupNamesUsesOwnerAndSkipsBlankNames() {
        responses.put("/api/get-groups?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"eng"},
                  {"name":"ops"},
                  {"owner":"acme","name":" "}
                ]}
                """);

        assertThat(new CasdoorClient(props).groupNames()).containsExactly("acme_eng", "acme_ops");
    }

    @Test
    void departmentSnapshotBuildsTreeAndAdmins() {
        responses.put("/api/get-users?owner=acme", """
                {"data":[
                  {"name":"alice","id":"sub-a","groups":["child"]},
                  {"name":"bob","id":"sub-b","groups":[]}
                ]}
                """);
        responses.put("/api/get-groups?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"parent","parentId":""},
                  {"owner":"acme","name":"child","parentId":"acme/parent"}
                ]}
                """);
        responses.put("/api/get-roles?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"child-admin","users":["acme/alice","acme/unknown"]},
                  {"owner":"acme","name":"unrelated","users":["acme/bob"]}
                ]}
                """);

        CasdoorClient.DepartmentSnapshot result = new CasdoorClient(props).departmentSnapshot();

        assertThat(result.deptIds()).containsExactly("acme_parent", "acme_child");
        assertThat(result.members()).containsEntry("acme_child", Set.of("sub-a"));
        assertThat(result.parents()).containsExactly(Map.entry("acme_child", "acme_parent"));
        assertThat(result.admins()).containsEntry("acme_child", Set.of("sub-a")).hasSize(1);
        assertThat(seenPaths).containsExactly(
                "/api/get-users?owner=acme", "/api/get-groups?owner=acme", "/api/get-roles?owner=acme");
    }

    @Test
    void malformedJsonFailsWithProtocolException() {
        responses.put("/api/get-users?owner=acme", "not-json");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CasdoorClient(props).groupMembers());
        assertThat(ex).hasMessageContaining("Casdoor").hasMessageContaining("解析失败");
    }

    @Test
    void httpErrorStatusPropagates() {
        statuses.put("/api/get-users?owner=acme", 503);
        responses.put("/api/get-users?owner=acme", "{\"error\":\"unavailable\"}");

        // RestClient 默认对 4xx/5xx 抛 RestClientResponseException（非 200 不被静默当空快照）。
        assertThatThrownBy(() -> new CasdoorClient(props).groupMembers())
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
    }

    // TODO(issue-CAS01): 200 {} / blank / data 非数组应失败，不能返回空快照。
    // TODO(issue-CAS04): 特殊 organization 必须 URI 编码或预校验；修复后检查服务端 rawQuery。
}
