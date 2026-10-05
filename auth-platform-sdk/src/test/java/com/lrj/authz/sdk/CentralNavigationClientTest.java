package com.lrj.authz.sdk;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.NavigationDtos.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 实际HTTP校验导航双身份、关联字段及损坏响应，不以隐藏菜单代替判权。 */
class CentralNavigationClientTest {
    private HttpServer server;
    private CentralAccessClient client;
    private final AtomicReference<String> response = new AtomicReference<>(),
            path = new AtomicReference<>(),
            user = new AtomicReference<>(),
            service = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final ObjectMapper json =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final String tenant = UUID.randomUUID().toString();
    private final Request request = new Request(tenant, 1L, UUID.randomUUID().toString());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/governance/v1/access/navigation",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    user.set(exchange.getRequestHeaders().getFirst("X-User-Access-Token"));
                    service.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        client =
                new CentralAccessClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "s".repeat(48),
                        "commerce",
                        "test",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        response.set(json.writeValueAsString(view()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private View view() {
        var context =
                new AccessContext(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        1,
                        1,
                        1,
                        tenant,
                        "commerce",
                        "test",
                        "commerce-nav",
                        "HUMAN",
                        UUID.randomUUID().toString());
        return new View(
                "1",
                request.requestId(),
                context,
                2,
                "a".repeat(64),
                "b".repeat(64),
                Instant.now().toString(),
                "AVAILABLE",
                List.of(
                        new Menu("group.products", null, null, "商品", 0),
                        new Menu(
                                "page.products",
                                "group.products",
                                "/operations/products",
                                "商品档案",
                                1)),
                List.of("commerce.product.read"));
    }

    @Test
    void separateHeadersAndNoAccessAreNotBusinessPermits() throws Exception {
        assertThat(client.navigation("business-token", request).menus().getFirst().route())
                .isNull();
        assertThat(path.get()).endsWith("/navigation");
        assertThat(user.get()).isEqualTo("business-token");
        assertThat(service.get()).isEqualTo("Bearer " + "s".repeat(48));
        var v = view();
        response.set(
                json.writeValueAsString(
                        new View(
                                v.schemaVersion(),
                                v.requestId(),
                                v.context(),
                                v.manifestVersion(),
                                v.contentHash(),
                                v.presentationHash(),
                                v.observedAt(),
                                "NO_ACCESS",
                                List.of(),
                                List.of())));
        assertThat(client.navigation("business-token", request).state()).isEqualTo("NO_ACCESS");
        status.set(401);
        assertThatThrownBy(() -> client.navigation("business-token", request))
                .isInstanceOfSatisfying(
                        CentralAccessException.class, e -> assertThat(e.status()).isEqualTo(401));
        status.set(403);
        assertThatThrownBy(() -> client.navigation("business-token", request))
                .isInstanceOf(AccessDeniedException.class);
        status.set(503);
        assertThatThrownBy(() -> client.navigation("business-token", request))
                .isInstanceOf(CentralAccessException.class);
    }

    @Test
    void injectedTargetsInvalidVersionsRoutesAndAncestorsFailClosed() throws Exception {
        String good = json.writeValueAsString(view());
        var bads =
                new ArrayList<>(
                        List.of(
                                "{}",
                                "null",
                                good.replace("\"test\"", "\"prod\""),
                                good.replace(tenant, UUID.randomUUID().toString()),
                                good.replace(request.requestId(), UUID.randomUUID().toString()),
                                good.replace(
                                        "\"membership_generation\":1",
                                        "\"membership_generation\":2"),
                                good.replace("\"manifest_version\":2", "\"manifest_version\":0"),
                                good.replace("AVAILABLE", "UNKNOWN"),
                                good.replace("AVAILABLE", "NO_ACCESS"),
                                good.replace("/operations/products", "//external/path"),
                                good.replace("commerce.product.read", "other.product.read"),
                                good.replace("\"parent\":null", "\"parent\":\"page.products\""),
                                good.replace("\"position\":1", "\"position\":0"),
                                good.replace("\"position\":1", "\"position\":null"),
                                good.replace(
                                        "\"code\":\"page.products\"",
                                        "\"code\":\"group.products\""),
                                " ".repeat(131073)));
        var stale = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(good);
        stale.put("observed_at", "2020-01-01T00:00:00Z");
        bads.add(stale.toString());
        for (String bad : bads) {
            response.set(bad);
            assertThatThrownBy(() -> client.navigation("business-token", request))
                    .isInstanceOf(CentralAccessException.class);
        }
    }
}
