package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.protocol.DirectoryEvents;
import com.lrj.authz.protocol.DirectoryEvents.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 用真实回环 HTTP 验证有界传输和提交/确认调用顺序；数据库原子性另由真实 PG 用例证明。 */
class DirectoryPullImporterTest {
    private HttpServer source;
    private final DirectoryGovernance directory = mock(DirectoryGovernance.class);
    private final DirectoryAuthority authority =
            new DirectoryAuthority(
                    UUID.randomUUID().toString(),
                    "oa",
                    "test",
                    "41",
                    UUID.randomUUID().toString(),
                    "https://issuer.example");
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicReference<String> page = new AtomicReference<>("{\"events\":[]}");
    private final AtomicReference<String> sourceTenant = new AtomicReference<>("41");
    private final AtomicBoolean failAck = new AtomicBoolean();
    private final AtomicInteger eventCode = new AtomicInteger(200);
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private final AtomicInteger ackCalls = new AtomicInteger();
    private final AtomicBoolean slowBody = new AtomicBoolean();
    private final Event event = event(1);
    private String fingerprint;

    @BeforeEach
    void open() throws Exception {
        fingerprint = DirectoryEvents.eventFingerprint(event);
        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext(
                "/internal/directory/v1/",
                exchange -> {
                    if (!("Bearer " + "x".repeat(43))
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        exchange.close();
                        return;
                    }
                    String path = exchange.getRequestURI().getPath();
                    String response;
                    int code = 200;
                    if (path.endsWith("/events")) {
                        seenQuery.set(exchange.getRequestURI().getQuery());
                        response = page.get();
                        code = eventCode.get();
                        if (code == 302) {
                            exchange.getResponseHeaders()
                                    .add(
                                            "Location",
                                            "http://127.0.0.1:"
                                                    + source.getAddress().getPort()
                                                    + "/forbidden");
                        }
                    } else if (path.endsWith("/ack")) {
                        ackCalls.incrementAndGet();
                        String body =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8);
                        // 只有治理事务已经成功返回连续水位后，导入器才可以发送此确认。
                        if (committed.get() < 1 || !body.contains(fingerprint)) {
                            code = 409;
                        } else {
                            confirmed.set(committed.get());
                        }
                        if (failAck.getAndSet(false)) {
                            code = 503;
                        }
                        response = status();
                    } else {
                        response = status();
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        if (path.endsWith("/events") && slowBody.get()) {
                            out.write(bytes, 0, 1);
                            out.flush();
                            try {
                                Thread.sleep(400);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                            out.write(bytes, 1, bytes.length - 1);
                        } else {
                            out.write(bytes);
                        }
                    }
                });
        source.start();
        when(directory.checkpoint(authority)).thenAnswer(call -> receipt());
        when(directory.inspect(authority))
                .thenAnswer(call -> new DirectoryGovernance.Inspection(committed.get(), false));
        when(directory.accept(eq(authority), any()))
                .thenAnswer(
                        call -> {
                            committed.set(1);
                            page.set("{\"events\":[]}");
                            return receipt();
                        });
    }

    @AfterEach
    void close() {
        source.stop(0);
    }

    @Test
    void commitsBeforeConfirmAndNextRunRepairsLostConfirmationResponse() {
        page.set("{\"events\":[" + DirectoryJson.event(event) + "]}");
        failAck.set(true);
        try (var importer = importer(10, 1000)) {
            assertThatThrownBy(importer::pull)
                    .isInstanceOfSatisfying(
                            GovernanceException.class,
                            failure ->
                                    assertThat(failure.code()).isEqualTo(DEPENDENCY_UNAVAILABLE));
        }
        assertThat(committed.get()).isEqualTo(1);
        assertThat(ackCalls.get()).isEqualTo(1);
        try (var importer = importer(10, 1000)) {
            var result = importer.pull();
            assertThat(result.processed()).isZero();
            assertThat(result.lastSequence()).isEqualTo(1);
        }
        assertThat(ackCalls.get()).isEqualTo(2);
        verify(directory, times(1)).accept(authority, event);
    }

    @Test
    void wrongSourceRejectsBeforeRegistrationOrImport() {
        sourceTenant.set("42");
        try (var importer = importer(10, 1000)) {
            assertThatThrownBy(importer::register).isInstanceOf(GovernanceException.class);
        }
        verifyNoInteractions(directory);
    }

    @Test
    void pageGapDuplicateFieldAndUnknownEnvelopeCannotBeAcknowledged() {
        for (String invalid :
                List.of(
                        "{\"events\":[" + DirectoryJson.event(event(2)) + "]}",
                        "{\"events\":[],\"events\":[]}",
                        "{\"events\":[],\"tenant\":41}")) {
            page.set(invalid);
            try (var importer = importer(10, 1000)) {
                assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
            }
        }
        verify(directory, never()).accept(any(), any());
        assertThat(ackCalls.get()).isZero();
    }

    @Test
    void consumerFailureDoesNotSendConfirmation() {
        page.set("{\"events\":[" + DirectoryJson.event(event) + "]}");
        when(directory.accept(eq(authority), any()))
                .thenThrow(new GovernanceException(BINDING_CONFLICT));
        try (var importer = importer(10, 1000)) {
            assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
        }
        assertThat(ackCalls.get()).isZero();
        assertThat(committed.get()).isZero();
    }

    @Test
    void maxEventsLimitsPageAndStopsWithoutBackgroundLoop() {
        page.set("{\"events\":[" + DirectoryJson.event(event) + "]}");
        try (var importer = importer(1, 1000)) {
            assertThat(importer.pull()).isEqualTo(new DirectoryPullImporter.Result(1, 1));
        }
        assertThat(seenQuery.get()).isEqualTo("after_sequence=0&limit=1");
        assertThat(ackCalls.get()).isEqualTo(1);
    }

    @Test
    void redirectsAndOversizedPageAreRejectedWithoutConsumerEffects() {
        eventCode.set(302);
        try (var importer = importer(10, 1000)) {
            assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
        }
        eventCode.set(200);
        page.set("x".repeat(100 * 65536 + 2048));
        try (var importer = importer(10, 3000)) {
            assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
        }
        verify(directory, never()).accept(any(), any());
        assertThat(ackCalls.get()).isZero();
    }

    @Test
    void stalledResponseBodyTimesOutAndMissingCommittedPageFailsExplicitly() {
        slowBody.set(true);
        long started = System.nanoTime();
        try (var importer = importer(10, 100)) {
            assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
        }
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(java.time.Duration.ofSeconds(2));
        slowBody.set(false);
        try (var importer = importer(10, 1000)) {
            assertThatThrownBy(importer::pull).isInstanceOf(GovernanceException.class);
        }
        verify(directory, never()).accept(any(), any());
        assertThat(ackCalls.get()).isZero();
    }

    @Test
    void configurationRejectsInsecureOrAmbiguousDestinationsAndRedactsSecret() {
        for (String endpoint :
                List.of(
                        "http://outside.example/internal/directory/v1",
                        "https://example.com/other",
                        "https://user:password@example.com/internal/directory/v1",
                        "https://example.com/internal/directory/v1?q=x")) {
            assertThatThrownBy(
                            () ->
                                    new DirectoryPullConfiguration(
                                            authority,
                                            URI.create(endpoint),
                                            "x".repeat(43),
                                            "it",
                                            10,
                                            1000))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(config(10, 1000).toString()).doesNotContain("x".repeat(43));
        assertThatThrownBy(() -> config(1001, 1000)).isInstanceOf(GovernanceException.class);
    }

    @Test
    void operationalStatusDistinguishesPendingAcknowledgedAndOfflineConflict() {
        try (var importer = importer(10, 1000)) {
            assertThat(importer.inspect().state())
                    .isEqualTo(DirectoryPullImporter.SyncState.PENDING);
            committed.set(1);
            assertThat(importer.inspect().state())
                    .isEqualTo(DirectoryPullImporter.SyncState.PENDING);
            confirmed.set(1);
            assertThat(importer.inspect().state())
                    .isEqualTo(DirectoryPullImporter.SyncState.ACKNOWLEDGED);
            when(directory.inspect(authority))
                    .thenReturn(new DirectoryGovernance.Inspection(1, true));
            source.stop(0);
            var conflict = importer.inspect();
            assertThat(conflict.state()).isEqualTo(DirectoryPullImporter.SyncState.CONFLICT);
            assertThat(conflict.sourceSequence()).isNull();
            assertThat(conflict.confirmedSequence()).isNull();
        }
        verify(directory, never()).accept(any(), any());
        assertThat(ackCalls.get()).isZero();
    }

    private DirectoryPullImporter importer(int max, int timeout) {
        return new DirectoryPullImporter(directory, config(max, timeout));
    }

    private DirectoryPullConfiguration config(int max, int timeout) {
        return new DirectoryPullConfiguration(
                authority,
                URI.create(
                        "http://127.0.0.1:"
                                + source.getAddress().getPort()
                                + "/internal/directory/v1"),
                "x".repeat(43),
                "directory-test",
                max,
                timeout);
    }

    private DirectoryGovernance.Receipt receipt() {
        return new DirectoryGovernance.Receipt(
                committed.get(), committed.get() == 0 ? null : fingerprint, false);
    }

    private String status() {
        return "{\"source\":\"oa\",\"environment\":\"test\",\"source_tenant_ref\":\""
                + sourceTenant.get()
                + "\",\"last_sequence\":1,\"acked_sequence\":"
                + confirmed.get()
                + ",\"acked_fingerprint\":"
                + (confirmed.get() == 0 ? "null" : "\"" + fingerprint + "\"")
                + ",\"backlog\":"
                + (1 - confirmed.get())
                + "}";
    }

    private Event event(long sequence) {
        Payload payload =
                new Payload(
                        new Employee("1", "exact-subject", "ACTIVE", List.of(), List.of()),
                        null,
                        null);
        return new Event(
                1,
                UUID.randomUUID().toString(),
                "oa",
                "test",
                "41",
                sequence,
                DirectoryAggregateType.EMPLOYEE,
                "1",
                sequence,
                Instant.parse("2026-09-28T00:00:00Z").toString(),
                null,
                payload,
                DirectoryEvents.payloadHash(DirectoryAggregateType.EMPLOYEE, payload));
    }
}
