package com.lrj.authz.governance.authentication;

import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.GovernanceException;

import java.net.URI;
import java.util.Properties;

/** 后端受控发行方配置；应用/受众固定，绝不从用户请求中切换。 */
public record TokenAuthority(
        String issuer,
        URI jwksUri,
        String audience,
        String clientId,
        String clientSecret,
        String versionProbeClientId,
        String versionProbeClientSecret,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maximumConcurrent) {
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_CONCURRENT_REQUESTS = 32;

    /** 凭据仅在后端配置，版本探针与最终用户应用客户端分开。 */
    public TokenAuthority {
        BootstrapCommand.bounded(issuer, 500);
        BootstrapCommand.bounded(audience, 160);
        BootstrapCommand.bounded(clientId, 160);
        BootstrapCommand.bounded(versionProbeClientId, 160);
        if (clientSecret == null
                || clientSecret.isBlank()
                || versionProbeClientSecret == null
                || versionProbeClientSecret.isBlank()
                || connectTimeoutMillis < MIN_TIMEOUT_MILLIS
                || connectTimeoutMillis > MAX_TIMEOUT_MILLIS
                || readTimeoutMillis < MIN_TIMEOUT_MILLIS
                || readTimeoutMillis > MAX_TIMEOUT_MILLIS
                || maximumConcurrent < 1
                || maximumConcurrent > MAX_CONCURRENT_REQUESTS) {
            throw invalid();
        }
        validateUri(URI.create(issuer));
        validateUri(jwksUri);
        if (issuer.endsWith("/")) {
            throw invalid();
        }
    }

    /** 显式装配专用受众/发行方，缺失不复用旧管理接口可选 audience。 */
    public static TokenAuthority from(Properties properties) {
        try {
            return new TokenAuthority(
                    properties.getProperty("issuer"),
                    URI.create(properties.getProperty("jwks.uri")),
                    properties.getProperty("audience"),
                    properties.getProperty("client.id"),
                    properties.getProperty("client.secret"),
                    properties.getProperty("version-probe.client.id"),
                    properties.getProperty("version-probe.client.secret"),
                    Integer.parseInt(properties.getProperty("connect-timeout-ms", "2000")),
                    Integer.parseInt(properties.getProperty("read-timeout-ms", "2000")),
                    Integer.parseInt(properties.getProperty("maximum-concurrent", "8")));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    /** 仅 HTTPS，回环 HTTP 是隔离开发例外；配置也不能带 URL 凭据或跳转片段。 */
    public static void validateUri(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || !("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme())
                                && ("localhost".equals(uri.getHost())
                                        || "127.0.0.1".equals(uri.getHost()))))) {
            throw invalid();
        }
    }

    /** 默认 record 输出会泄露两个 secret，必须仅返回脱敏说明。 */
    @Override
    public String toString() {
        return "TokenAuthority[credentials=redacted]";
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
