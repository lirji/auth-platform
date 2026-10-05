package com.lrj.authz.governance.authentication;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.application.GovernanceException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 本机 HTTP 边界和真实 RSA 签名验证失败语义；不能替代安装版 Casdoor 集成证据。 */
class CasdoorAccessTokenVerifierTest {
    private static final String CLIENT = "isolated-test-client";
    private final ObjectMapper json = new ObjectMapper();
    private RSAKey key;
    private HttpServer server;
    private ExecutorService httpThreads;
    private String issuer;
    private volatile String version = CasdoorAccessTokenVerifier.VERIFIED_SERVER_VERSION;
    private volatile boolean active = true;
    private volatile String introspectionOverride;
    private volatile String changedField;
    private volatile boolean brokenMetadata;
    private volatile boolean oversize;
    private volatile boolean redirect;
    private volatile boolean jwksUnavailable;
    private volatile long delayMillis;
    private volatile CountDownLatch introspectionEntered;
    private volatile CountDownLatch introspectionRelease;
    private final AtomicInteger introspections = new AtomicInteger();

    @BeforeEach
    void startBoundedHttpFixture() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        httpThreads = Executors.newFixedThreadPool(4);
        server.setExecutor(httpThreads);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(
                "/api/get-version-info",
                exchange -> {
                    if (brokenMetadata) {
                        respond(exchange, "[]", 200);
                        return;
                    }
                    respond(
                            exchange,
                            json.writeValueAsString(
                                    Map.of("status", "ok", "data", Map.of("version", version))),
                            200);
                });
        server.createContext(
                "/jwks",
                exchange ->
                        respond(
                                exchange,
                                new JWKSet(key.toPublicJWK()).toString(),
                                jwksUnavailable ? 503 : 200));
        server.createContext("/api/login/oauth/introspect", this::introspect);
        server.start();
    }

    @AfterEach
    void closeFixture() {
        if (introspectionRelease != null) {
            introspectionRelease.countDown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (httpThreads != null) {
            httpThreads.shutdownNow();
        }
    }

    @Test
    void validatesSignaturePurposeAndCurrentFactsWithoutPositiveCache() throws Exception {
        var verifier = verifier(1_000, 2);
        String token = token(null);
        assertThat(verifier.verify(token)).isEqualTo(new VerifiedLogin(issuer, "fixture-subject"));
        assertThat(verifier.verify(token)).isEqualTo(new VerifiedLogin(issuer, "fixture-subject"));
        assertThat(introspections).hasValue(2);
        active = false;
        expect(INVALID_CREDENTIAL, () -> verifier.verify(token));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "issuer",
                "audience",
                "expired",
                "not-before",
                "id-token",
                "missing-purpose",
                "missing-exp",
                "missing-iat",
                "missing-sub"
            })
    void rejectsInvalidSignedClaimsBeforeIntrospection(String defect) throws Exception {
        var verifier = verifier(1_000, 2);
        String token = token(defect);
        expect(INVALID_CREDENTIAL, () -> verifier.verify(token));
        assertThat(introspections).hasValue(0);
    }

    @Test
    void rejectsTamperedSignatureAndUnsupportedAlgorithm() throws Exception {
        var verifier = verifier(1_000, 2);
        RSAKey attacker = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        SignedJWT forged =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims(null));
        forged.sign(new RSASSASigner(attacker));
        expect(INVALID_CREDENTIAL, () -> verifier.verify(forged.serialize()));
        String encodedClaims =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(claims(null).toString().getBytes(StandardCharsets.UTF_8));
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        expect(INVALID_CREDENTIAL, () -> verifier.verify(header + "." + encodedClaims + "."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"client_id", "iss", "sub", "exp", "aud", "token_type"})
    void requiresStrictAgreementWithIntrospection(String field) throws Exception {
        var verifier = verifier(1_000, 2);
        changedField = field;
        String token = token(null);
        expect(INVALID_CREDENTIAL, () -> verifier.verify(token));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"active\":null}",
                "{\"active\":\"false\"}",
                "{\"status\":\"error\",\"msg\":\"private database failure\"}",
                "{\"active\":true,\"error\":\"server_error\"}",
                "{\"active\":true,\"status\":\"error\"}"
            })
    void malformedIntrospectionIsDependencyFailureWithoutLeakingDetails(String response)
            throws Exception {
        var verifier = verifier(1_000, 2);
        String token = token(null);
        introspectionOverride = response;
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
        assertThat(introspections).hasValue(1); // 故障不触发隐式重试或正向缓存。
        introspectionOverride = null;
        assertThat(verifier.verify(token)).isEqualTo(new VerifiedLogin(issuer, "fixture-subject"));
        active = false;
        expect(INVALID_CREDENTIAL, () -> verifier.verify(token));
        assertThat(introspections).hasValue(3);
    }

    @Test
    void rejectsLegacyIssuerAtStartupAndRuntimeDowngrade() throws Exception {
        version = "v4.3.0";
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier(1_000, 2));
        version = CasdoorAccessTokenVerifier.VERIFIED_SERVER_VERSION;
        var verifier = verifier(1_000, 2);
        String token = token(null);
        verifier.verify(token);
        version = "v4.3.0";
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
        assertThat(introspections).hasValue(1);
    }

    @Test
    void dependencyTimeoutMalformedAndOversizeResponsesFailClosed() throws Exception {
        var verifier = verifier(100, 2);
        String token = token(null);
        brokenMetadata = true;
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
        brokenMetadata = false;
        oversize = true;
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
        oversize = false;
        redirect = true;
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
        redirect = false;
        delayMillis = 350;
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
    }

    @Test
    void unavailableJwksIsReportedAsDependencyFailure() throws Exception {
        var verifier = verifier(1_000, 2);
        jwksUnavailable = true;
        String token = token(null);
        expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
    }

    @Test
    void boundedCapacityRejectsExcessRequestsAndReleasesAfterCompletion() throws Exception {
        var verifier = verifier(2_000, 1);
        String token = token(null);
        introspectionEntered = new CountDownLatch(1);
        introspectionRelease = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(1)) {
            Future<VerifiedLogin> first = executor.submit(() -> verifier.verify(token));
            assertThat(introspectionEntered.await(3, TimeUnit.SECONDS)).isTrue();
            expect(DEPENDENCY_UNAVAILABLE, () -> verifier.verify(token));
            introspectionRelease.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).subject()).isEqualTo("fixture-subject");
        }
        assertThat(verifier.verify(token).subject()).isEqualTo("fixture-subject");
    }

    @Test
    void credentialsAndAuthoritiesCannotBeSelectedByUntrustedInput() throws Exception {
        var authority = authority(1_000, 2);
        assertThat(authority.toString())
                .doesNotContain("private-introspection-secret", "private-version-secret");
        expect(
                INVALID_ARGUMENT,
                () ->
                        new TokenAuthority(
                                "http://remote.example",
                                URI.create(issuer + "/jwks"),
                                CLIENT,
                                CLIENT,
                                "secret",
                                "ops",
                                "secret",
                                1_000,
                                1_000,
                                2));
        var verifier = verifier(1_000, 2);
        for (String invalid : List.of("", "token with space", "a".repeat(32_769))) {
            expect(INVALID_CREDENTIAL, () -> verifier.verify(invalid));
        }
        expect(INVALID_CREDENTIAL, () -> verifier.verify(null));
    }

    @Test
    void machineIdentityIsSeparateAndLiveFactsAreRequiredEveryTime() throws Exception {
        var verifier = verifier(1_000, 2);
        String publisher = UUID.randomUUID().toString(), token = machineToken(null);
        var result = verifier.verifyMachine(token, publisher, "admin/isolated-publisher");
        assertThat(result.publisherId()).isEqualTo(publisher);
        assertThat(result.issuer()).isEqualTo(issuer);
        assertThat(result.subject()).isEqualTo("admin/isolated-publisher");
        assertThat(result.clientId()).isEqualTo(CLIENT);
        assertThat(result.expiresAt()).isAfter(result.issuedAt());
        assertThat(result.toString()).doesNotContain(token, "private-introspection-secret");
        assertThat(verifier.verifyMachine(token, publisher, "admin/isolated-publisher"))
                .isEqualTo(result);
        assertThat(introspections).hasValue(2);
        active = false;
        expect(
                INVALID_CREDENTIAL,
                () -> verifier.verifyMachine(token, publisher, "admin/isolated-publisher"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "human-kind",
                "subject",
                "azp",
                "extra-audience",
                "too-long",
                "future-iat",
                "id-token",
                "bad-type"
            })
    void machineRejectsSignedHumanOrOverbroadCredentialBeforeIntrospection(String defect)
            throws Exception {
        var verifier = verifier(1_000, 2);
        String token = machineToken(defect), publisher = UUID.randomUUID().toString();
        expect(
                INVALID_CREDENTIAL,
                () -> verifier.verifyMachine(token, publisher, "admin/isolated-publisher"));
        assertThat(introspections).hasValue(0);
    }

    @Test
    void machineRequiresIntrospectionIssuanceAgreementAndRejectsExpiryWhileWaiting()
            throws Exception {
        var verifier = verifier(2_500, 2);
        String publisher = UUID.randomUUID().toString();
        changedField = "iat";
        String invalidFacts = machineToken(null);
        expect(
                INVALID_CREDENTIAL,
                () -> verifier.verifyMachine(invalidFacts, publisher, "admin/isolated-publisher"));
        changedField = null;
        String shortToken = machineToken("short");
        Instant expiration =
                SignedJWT.parse(shortToken).getJWTClaimsSet().getExpirationTime().toInstant();
        introspectionEntered = new CountDownLatch(1);
        introspectionRelease = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(1)) {
            var result =
                    executor.submit(
                            () ->
                                    verifier.verifyMachine(
                                            shortToken, publisher, "admin/isolated-publisher"));
            assertThat(introspectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long remaining =
                    java.time.Duration.between(Instant.now(), expiration.plusMillis(50)).toMillis();
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
            introspectionRelease.countDown();
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(GovernanceException.class)
                    .satisfies(
                            error ->
                                    assertThat(((GovernanceException) error.getCause()).code())
                                            .isEqualTo(INVALID_CREDENTIAL));
        }
        assertThat(introspections).hasValue(2);
    }

    @Test
    void machineRegistryUsesHintsOnlyForFixedLookupAndStillVerifiesSignature() throws Exception {
        var slot = machineAuthority();
        assertThat(slot.toString())
                .doesNotContain("private-introspection-secret", "private-version-secret");
        var machines = new CasdoorMachineTokens(List.of(slot));
        assertThat(machines.verify(machineToken(null)).publisherId()).isEqualTo(slot.publisherId());
        String broad = machineToken("extra-audience");
        expect(INVALID_CREDENTIAL, () -> machines.verify(broad));
        String foreign = token("issuer");
        expect(INVALID_CREDENTIAL, () -> machines.verify(foreign));
        var attacker = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        var forged =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        machineClaims(null));
        forged.sign(new RSASSASigner(attacker));
        expect(INVALID_CREDENTIAL, () -> machines.verify(forged.serialize()));
        assertThat(introspections).hasValue(1);
        for (String invalid : List.of("", "a b", "a".repeat(32_769), "not-a-jwt")) {
            expect(INVALID_CREDENTIAL, () -> machines.verify(invalid));
        }
        expect(INVALID_CREDENTIAL, () -> machines.verify(null));
    }

    @Test
    void ambiguousOrUnboundedMachineConfigurationFailsBeforeExternalCalls() {
        var slot = machineAuthority();
        expect(INVALID_ARGUMENT, () -> new CasdoorMachineTokens(List.of()));
        expect(INVALID_ARGUMENT, () -> new CasdoorMachineTokens(Collections.nCopies(17, slot)));
        expect(INVALID_ARGUMENT, () -> new CasdoorMachineTokens(List.of(slot, slot)));
        var alias =
                new MachineTokenAuthority(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "commerce",
                        slot.targetInstanceId(),
                        "test",
                        slot.subject(),
                        slot.tokenAuthority());
        expect(INVALID_ARGUMENT, () -> new CasdoorMachineTokens(List.of(slot, alias)));
        expect(
                INVALID_ARGUMENT,
                () ->
                        new MachineTokenAuthority(
                                slot.publisherId(),
                                slot.servicePrincipal(),
                                "commerce",
                                slot.targetInstanceId(),
                                "production",
                                slot.subject(),
                                slot.tokenAuthority()));
        assertThat(introspections).hasValue(0);
    }

    @Test
    void machineIntrospectionCannotUseDuplicateAudienceToMatchASet() throws Exception {
        var machines = new CasdoorMachineTokens(List.of(machineAuthority()));
        String token = machineToken(null);
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        introspectionOverride =
                json.writeValueAsString(
                        Map.of(
                                "active",
                                true,
                                "client_id",
                                CLIENT,
                                "iss",
                                issuer,
                                "sub",
                                claims.getSubject(),
                                "iat",
                                claims.getIssueTime().toInstant().getEpochSecond(),
                                "exp",
                                claims.getExpirationTime().toInstant().getEpochSecond(),
                                "aud",
                                List.of(CLIENT, CLIENT),
                                "token_type",
                                "Bearer"));
        expect(INVALID_CREDENTIAL, () -> machines.verify(token));
    }

    private MachineTokenAuthority machineAuthority() {
        return new MachineTokenAuthority(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "commerce",
                UUID.randomUUID().toString(),
                "test",
                "admin/isolated-publisher",
                authority(1_000, 2));
    }

    private JWTClaimsSet machineClaims(String defect) {
        Instant base =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(5);
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(
                        "subject".equals(defect)
                                ? "admin/another-publisher"
                                : "admin/isolated-publisher")
                .audience(
                        "extra-audience".equals(defect)
                                ? List.of(CLIENT, "other-client")
                                : List.of(CLIENT))
                .issueTime(Date.from("future-iat".equals(defect) ? base.plusSeconds(60) : base))
                .expirationTime(
                        Date.from(
                                base.plusSeconds(
                                        "too-long".equals(defect)
                                                ? 301
                                                : "short".equals(defect) ? 7 : 300)))
                .notBeforeTime(Date.from(base))
                .claim("tokenType", "id-token".equals(defect) ? "id-token" : "access-token")
                .claim(
                        "type",
                        "human-kind".equals(defect)
                                ? "normal-user"
                                : "bad-type".equals(defect)
                                        ? List.of("application")
                                        : "application")
                .claim("azp", "azp".equals(defect) ? "other-client" : CLIENT)
                .build();
    }

    private String machineToken(String defect) throws Exception {
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        machineClaims(defect));
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private CasdoorAccessTokenVerifier verifier(int timeout, int maximum) {
        return new CasdoorAccessTokenVerifier(authority(timeout, maximum));
    }

    private TokenAuthority authority(int timeout, int maximum) {
        return new TokenAuthority(
                issuer,
                URI.create(issuer + "/jwks"),
                CLIENT,
                CLIENT,
                "private-introspection-secret",
                "version-ops",
                "private-version-secret",
                timeout,
                timeout,
                maximum);
    }

    private JWTClaimsSet claims(String defect) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("issuer".equals(defect) ? issuer + "/other" : issuer)
                .subject("missing-sub".equals(defect) ? null : "fixture-subject")
                .audience("audience".equals(defect) ? "other-client" : CLIENT)
                .issueTime("missing-iat".equals(defect) ? null : Date.from(now.minusSeconds(5)))
                .expirationTime(
                        "missing-exp".equals(defect)
                                ? null
                                : Date.from(now.plusSeconds("expired".equals(defect) ? -30 : 60)))
                .notBeforeTime(Date.from(now.plusSeconds("not-before".equals(defect) ? 60 : -5)))
                .claim(
                        "tokenType",
                        "missing-purpose".equals(defect)
                                ? null
                                : "id-token".equals(defect) ? "id-token" : "access-token")
                .build();
    }

    private String token(String defect) throws Exception {
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims(defect));
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private void introspect(HttpExchange exchange) throws java.io.IOException {
        introspections.incrementAndGet();
        if (introspectionOverride != null) {
            respond(exchange, introspectionOverride, 200);
            return;
        }
        try {
            if (introspectionEntered != null) {
                introspectionEntered.countDown();
            }
            if (introspectionRelease != null && !introspectionRelease.await(3, TimeUnit.SECONDS)) {
                respond(exchange, "{}", 503);
                return;
            }
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            if (redirect) {
                exchange.getResponseHeaders().set("Location", issuer + "/unexpected");
                respond(exchange, "{}", 302);
                return;
            }
            if (oversize) {
                respond(exchange, " ".repeat(65_537), 200);
                return;
            }
            String form =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = new HashMap<>();
            for (String item : form.split("&")) {
                String[] pair = item.split("=", 2);
                fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            if (!"access_token".equals(fields.get("token_type_hint"))) {
                respond(exchange, "{}", 400);
                return;
            }
            var jwt = SignedJWT.parse(fields.get("token")).getJWTClaimsSet();
            Map<String, Object> result =
                    new HashMap<>(
                            Map.of(
                                    "active",
                                    active,
                                    "client_id",
                                    CLIENT,
                                    "iss",
                                    jwt.getIssuer(),
                                    "sub",
                                    jwt.getSubject(),
                                    "exp",
                                    jwt.getExpirationTime().toInstant().getEpochSecond(),
                                    "aud",
                                    jwt.getAudience(),
                                    "token_type",
                                    "Bearer"));
            result.put("iat", jwt.getIssueTime().toInstant().getEpochSecond());
            if (changedField != null) {
                result.put(changedField, "invalid-value");
            }
            respond(exchange, json.writeValueAsString(result), 200);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (java.text.ParseException failure) {
            respond(exchange, "{}", 400);
        }
    }

    private static void respond(HttpExchange exchange, String body, int status)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void expect(
            GovernanceException.Code code,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(
                        GovernanceException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(code);
                            assertThat(error.getMessage()).isEqualTo(code.name());
                            assertThat(error.getCause()).isNull();
                        });
    }
}
