package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.ApprovalDtos.*;
import com.lrj.authz.protocol.ApprovalSignature;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Optional;

/** 唯一OA HTTP适配；限制目标和响应体，不跟随重定向，不把超时当作不存在。 */
public final class HttpApprovalGateway implements ApprovalGateway {
    private static final String PREFIX = "/internal/iam-approval/v1/";
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI base;
    private final String key;
    private final String environment;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    /** 非TLS只允许显式loopback隔离测试；凭据不可放URL。 */
    public HttpApprovalGateway(String baseUrl, String key, String environment) {
        try {
            base = URI.create(baseUrl);
            if (base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null
                    || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                    || !("https".equals(base.getScheme())
                            || "http".equals(base.getScheme())
                                    && "127.0.0.1".equals(base.getHost())))
                throw new IllegalArgumentException();
            ApprovalSignature.validateKey(key);
            if (environment == null || !environment.matches("[a-z0-9][a-z0-9-]{0,39}"))
                throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
        this.key = key;
        this.environment = environment;
    }

    /** 只有404代表业务键尚未存在；401/冲突/5xx均保持未知结果。 */
    public Optional<Instance> find(Lookup lookup) {
        return exchange("lookup", lookup, true);
    }

    /** 持久化业务键负责远端幂等，HTTP自动重试保持关闭。 */
    public Instance start(Start command) {
        return exchange("start", command, false)
                .orElseThrow(() -> new GovernanceException(DEPENDENCY_UNAVAILABLE));
    }

    private Optional<Instance> exchange(String operation, Object command, boolean allowAbsent) {
        try {
            byte[] body = JSON.writeValueAsBytes(command);
            String path = PREFIX + operation;
            var request =
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header(
                                    ApprovalSignature.HEADER,
                                    ApprovalSignature.sign(
                                            key,
                                            "auth-platform",
                                            environment,
                                            path,
                                            body,
                                            Instant.now()))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(4097);
                if (bytes.length > 4096) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                if (allowAbsent && response.statusCode() == 404) return Optional.empty();
                if (response.statusCode() != 200)
                    throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                return Optional.of(JSON.readValue(bytes, Instance.class));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        } catch (java.io.IOException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 防止错误日志暴露传输密钥。 */
    @Override
    public String toString() {
        return "HttpApprovalGateway[credentials=redacted]";
    }
}
