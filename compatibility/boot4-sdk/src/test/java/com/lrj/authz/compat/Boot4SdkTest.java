package com.lrj.authz.compat;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Boot4实际宿主+HTTP协议夹具验证二进制/自动装配/AOP，业务图闭环另由P2-06验收。 */
class Boot4SdkTest {
    @Test
    void startsAutoConfigurationAndEnforcesExplicitAndProxyCallsOverHttp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext(
                "/v1/check",
                exchange -> {
                    calls.incrementAndGet();
                    String request =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String response =
                            request.contains("broken")
                                    ? "{}"
                                    : request.contains("allow")
                                            ? "{\"allowed\":true}"
                                            : "{\"allowed\":false}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        fixture.start();
        try (var app =
                SpringApplication.run(
                        App.class,
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--authz.client.server-url=http://127.0.0.1:"
                                + fixture.getAddress().getPort())) {
            assertThat(app.getBean(AuthzEngine.class)).isInstanceOf(RemoteAuthzEngine.class);
            assertThat(app.getBean(CheckAccessAspect.class)).isNotNull();
            assertThat(Class.forName("com.fasterxml.jackson.databind.ObjectMapper")).isNotNull();
            assertThat(Class.forName("tools.jackson.databind.ObjectMapper")).isNotNull();
            int port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newHttpClient();
            for (String path : new String[] {"/explicit/allow", "/proxy/allow"})
                assertThat(status(client, port, path)).isEqualTo(200);
            for (String path : new String[] {"/explicit/deny", "/proxy/deny"})
                assertThat(status(client, port, path)).isEqualTo(403);
            assertThat(status(client, port, "/explicit/broken")).isEqualTo(503);
            // 非HTTP代理调用仍需判权，无法代理路径使用显式引擎，不把自调用当作安全屏障。
            assertThatThrownBy(() -> app.getBean(Guarded.class).read("deny"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(calls.get()).isEqualTo(6);
        } finally {
            fixture.stop(0);
        }
    }

    private int status(HttpClient client, int port, String path) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableAspectJAutoProxy
    static class App {
        @Bean
        SubjectResolver subject() {
            return () -> SubjectRef.user("compat-user");
        }

        @Bean
        Guarded guarded() {
            return new Guarded();
        }

        @Bean
        Api api(AuthzEngine engine, Guarded guarded) {
            return new Api(engine, guarded);
        }
    }

    /** 由实际Spring代理测试注解拦截。 */
    static class Guarded {
        @CheckAccess(permission = "view", resourceType = "document", resourceIdParam = "id")
        public String read(String id) {
            return "ok";
        }
    }

    @RestController
    static class Api {
        private final AuthzEngine engine;
        private final Guarded guarded;

        Api(AuthzEngine engine, Guarded guarded) {
            this.engine = engine;
            this.guarded = guarded;
        }

        @GetMapping("/explicit/{id}")
        public String explicit(@PathVariable String id) {
            if (!engine.check(
                    SubjectRef.user("compat-user"),
                    "view",
                    ResourceRef.of("document", id),
                    Consistency.fullyConsistent())) throw new AccessDeniedException("DENIED");
            return "ok";
        }

        @GetMapping("/proxy/{id}")
        public String proxy(@PathVariable String id) {
            return guarded.read(id);
        }

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<String> denied() {
            return ResponseEntity.status(403).body("DENIED");
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<String> unavailable() {
            return ResponseEntity.status(503).body("UNAVAILABLE");
        }
    }
}
