package com.lrj.authz.governance.authentication;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.GovernanceException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Casdoor 固定契约适配；签名/受众、凭据用途和当前发行方事实共同成立才产生身份。 */
public final class CasdoorAccessTokenVerifier {
    public static final String VERIFIED_SERVER_VERSION = "v4.11.0";
    private static final String ACCESS_TOKEN_PURPOSE = "access-token";
    private static final int MAX_TOKEN_BYTES = 32_768;
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final TokenAuthority authority;
    private final NimbusJwtDecoder decoder;
    private final Semaphore capacity;
    private final ObjectMapper json = new ObjectMapper();

    /** 仅采用当前 Boot BOM 的 RS256 验签；公钥缓存不缓存身份有效性或业务 ALLOW。 */
    public CasdoorAccessTokenVerifier(TokenAuthority authority) {
        this.authority = authority;
        this.capacity = new Semaphore(authority.maximumConcurrent());
        try {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            var keys =
                    new RemoteJWKSet<SecurityContext>(
                            authority.jwksUri().toURL(),
                            new DefaultResourceRetriever(
                                    authority.connectTimeoutMillis(),
                                    authority.readTimeoutMillis(),
                                    MAX_RESPONSE_BYTES));
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
            decoder = new NimbusJwtDecoder(processor);
            var claimConverter = MappedJwtClaimSetConverter.withDefaults(Map.of());
            decoder.setClaimSetConverter(
                    claims -> {
                        // Jwt builder 会给缺失 iat 的 Token 补推时间；必须先检查发行方原始声明。
                        if (claims.get("iat") == null
                                || claims.get("exp") == null
                                || !(claims.get("sub") instanceof String)) {
                            throw new BadJwtException("Required identity claims missing");
                        }
                        return claimConverter.convert(claims);
                    });
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(Duration.ZERO),
                            new JwtIssuerValidator(authority.issuer()),
                            this::validateClaims));
            // 旧版会给两个字段返回相同 Token；必须在启用之前拒绝这种发行方，不能靠 aud 自欺。
            verifyIssuerContract();
        } catch (IOException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 所有状态请求均实时校验发行方契约和 introspection；不存在跨请求正向认证缓存。 */
    public VerifiedLogin verify(String accessToken) {
        Jwt jwt = verifyToken(accessToken, null);
        return new VerifiedLogin(authority.issuer(), jwt.getSubject());
    }

    /** 机器身份另行检查应用主体和五分钟期限，不能作为 HUMAN Owner 登录证据。 */
    public VerifiedMachine verifyMachine(
            String accessToken, String publisherId, String expectedSubject) {
        BootstrapCommand.uuid(publisherId);
        BootstrapCommand.bounded(expectedSubject, 500);
        Jwt jwt = verifyToken(accessToken, expectedSubject);
        return new VerifiedMachine(
                publisherId,
                authority.issuer(),
                jwt.getSubject(),
                authority.clientId(),
                jwt.getIssuedAt(),
                jwt.getExpiresAt());
    }

    private Jwt verifyToken(String accessToken, String expectedMachineSubject) {
        if (accessToken == null
                || accessToken.isBlank()
                || accessToken.length() > MAX_TOKEN_BYTES
                || accessToken.chars().anyMatch(Character::isWhitespace)) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        if (!capacity.tryAcquire()) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
        try {
            // 即使发行方被原位降回旧版，相同 ID/Access Token 也不能被该入口接受。
            verifyIssuerContract();
            Jwt jwt;
            try {
                jwt = decoder.decode(accessToken);
            } catch (JwtException failure) {
                // 无法读取公钥是依赖故障，与凭据被确定拒绝分开；两者都不允许产生身份。
                Throwable cause = failure;
                while (cause != null) {
                    if (cause instanceof IOException) {
                        throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    }
                    cause = cause.getCause();
                }
                throw new GovernanceException(INVALID_CREDENTIAL);
            }
            if (expectedMachineSubject != null) {
                validateMachineClaims(jwt, expectedMachineSubject);
            }
            JsonNode facts =
                    fetch(
                            "/api/login/oauth/introspect",
                            authority.clientId(),
                            authority.clientSecret(),
                            "token=" + encode(accessToken) + "&token_type_hint=access_token");
            // HTTP 200 也可能是发行方数据库故障的错误对象；缺少明确认证事实不能归咎于用户凭据。
            // 不回显上游错误文本、不重试，也不把不可判定状态变成允许。
            if (!facts.path("active").isBoolean()
                    || facts.has("error")
                    || "error".equals(text(facts, "status"))) {
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            }
            if (!facts.path("active").booleanValue()
                    || !authority.clientId().equals(text(facts, "client_id"))
                    || !jwt.getIssuer().toString().equals(text(facts, "iss"))
                    || !jwt.getSubject().equals(text(facts, "sub"))
                    || !"Bearer".equalsIgnoreCase(text(facts, "token_type"))
                    || !facts.path("exp").isIntegralNumber()
                    || facts.path("exp").longValue() != jwt.getExpiresAt().getEpochSecond()
                    || !audience(facts.path("aud")).equals(new HashSet<>(jwt.getAudience()))) {
                throw new GovernanceException(INVALID_CREDENTIAL);
            }
            if (expectedMachineSubject != null) {
                if (!facts.path("iat").isIntegralNumber()
                        || !facts.path("iat").canConvertToLong()
                        || !facts.path("exp").canConvertToLong()
                        || (facts.path("aud").isArray() && facts.path("aud").size() != 1)
                        || facts.path("iat").longValue() != jwt.getIssuedAt().getEpochSecond()) {
                    throw new GovernanceException(INVALID_CREDENTIAL);
                }
                // introspection 会耗时；不能把发请求前尚有效的机器 Token 当作完成后仍有效。
                validateMachineClaims(jwt, expectedMachineSubject);
            }
            return jwt;
        } finally {
            capacity.release();
        }
    }

    private void validateMachineClaims(Jwt jwt, String expectedSubject) {
        Instant now = Instant.now();
        try {
            if (!authority.audience().equals(authority.clientId())
                    || !jwt.getAudience().equals(List.of(authority.clientId()))
                    || !expectedSubject.equals(jwt.getSubject())
                    || !"application".equals(jwt.getClaims().get("type"))
                    || !authority.clientId().equals(jwt.getClaims().get("azp"))
                    || jwt.getIssuedAt().isAfter(now)
                    || !jwt.getExpiresAt().isAfter(now)
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())
                                    .compareTo(Duration.ofSeconds(300))
                            > 0) {
                throw new GovernanceException(INVALID_CREDENTIAL);
            }
        } catch (RuntimeException failure) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            BootstrapCommand.bounded(jwt.getSubject(), 500);
            if (jwt.getExpiresAt() == null
                    || jwt.getIssuedAt() == null
                    || !jwt.getAudience().contains(authority.audience())
                    || !ACCESS_TOKEN_PURPOSE.equals(jwt.getClaimAsString("tokenType"))) {
                return invalidClaims();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException failure) {
            return invalidClaims();
        }
    }

    private void verifyIssuerContract() {
        JsonNode metadata =
                fetch(
                        "/api/get-version-info",
                        authority.versionProbeClientId(),
                        authority.versionProbeClientSecret(),
                        null);
        if (!"ok".equals(text(metadata, "status"))
                || !VERIFIED_SERVER_VERSION.equals(text(metadata.path("data"), "version"))) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private JsonNode fetch(String path, String clientId, String secret, String form) {
        HttpURLConnection connection = null;
        try {
            connection =
                    (HttpURLConnection)
                            URI.create(authority.issuer() + path).toURL().openConnection();
            connection.setConnectTimeout(authority.connectTimeoutMillis());
            connection.setReadTimeout(authority.readTimeoutMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (clientId + ":" + secret)
                                                    .getBytes(StandardCharsets.UTF_8)));
            if (form != null) {
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (var output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            if (connection.getResponseCode() != 200) {
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            }
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                }
                JsonNode result = json.readTree(body);
                if (result == null || !result.isObject()) {
                    throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                }
                return result;
            }
        } catch (IOException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Set<String> audience(JsonNode value) {
        Set<String> result = new HashSet<>();
        if (value.isTextual()) {
            result.add(value.textValue());
        } else if (value.isArray() && value.size() <= 16) {
            for (JsonNode item : value) {
                if (!item.isTextual()) {
                    throw new GovernanceException(INVALID_CREDENTIAL);
                }
                result.add(item.textValue());
            }
        } else {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).textValue() : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
