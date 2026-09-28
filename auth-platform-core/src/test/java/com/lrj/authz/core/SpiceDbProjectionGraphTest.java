package com.lrj.authz.core;

import com.lrj.authz.protocol.ProjectionGraph;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** 协议/资源边界单测；远端原子性由真实ProjectionCasIT证明。 */
class SpiceDbProjectionGraphTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> request = new AtomicReference<>();
    private SpiceDbProjectionGraph graph;
    @BeforeEach void open() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        graph = new SpiceDbProjectionGraph("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", Duration.ofSeconds(1));
    }
    @AfterEach void close() { server.stop(0); }
    private String id() { return UUID.randomUUID().toString(); }
    @Test void initializationAndAdvanceCarryRemotePreconditions() {
        body.set("{\"writtenAt\":{\"token\":\"opaque\"}}");
        String partition = id(), before = id();
        assertThat(graph.compareAndWrite(partition, null, before, List.of())).isEqualTo("opaque");
        assertThat(request.get()).contains("OPERATION_MUST_NOT_MATCH", "optionalPreconditions", "OPERATION_CREATE");
        graph.compareAndWrite(partition, before, id(), List.of());
        assertThat(request.get()).contains("OPERATION_MUST_MATCH", "OPERATION_DELETE", before);
    }
    @Test void missingTokenTrailingJsonAndErrorStreamNeverConfirm() {
        assertThatThrownBy(() -> graph.compareAndWrite(id(), null, id(), List.of())).hasMessage("PROTOCOL_INVALID");
        body.set("{\"writtenAt\":{\"token\":\"opaque\"}}{}");
        assertThatThrownBy(() -> graph.compareAndWrite(id(), null, id(), List.of())).hasMessage("PROTOCOL_INVALID");
        body.set("{\"error\":{\"code\":13}}");
        assertThatThrownBy(() -> graph.readMarker(id())).hasMessage("PROTOCOL_INVALID");
    }
    @Test void duplicateJsonKeysCannotHideInvalidToken() {
        body.set("{\"writtenAt\":{\"token\":\"\",\"token\":\"opaque\"}}");
        assertThatThrownBy(() -> graph.compareAndWrite(id(), null, id(), List.of())).hasMessage("PROTOCOL_INVALID");
    }
    @Test void stalledResponseBodyHasTotalDeadline() throws Exception {
        server.removeContext("/");
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> graph.compareAndWrite(id(), null, id(), List.of())).hasMessage("DEPENDENCY_UNAVAILABLE");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); }
    }
    @Test void hugeResponsesAreBoundedAndRemoteHttpIsRejected() {
        body.set(" ".repeat(524289));
        assertThatThrownBy(() -> graph.readMarker(id())).isInstanceOf(ProjectionGraph.Failure.class);
        assertThatThrownBy(() -> new SpiceDbProjectionGraph("http://example.test", "key", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
