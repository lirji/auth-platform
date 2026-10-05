package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.TokenAuthority;

import java.net.URI;
import java.util.Properties;

/** 一次性拉取器的固定来源配置；正文不能选择企业、发行方、出口 URL 或服务凭据。 */
public record DirectoryPullConfiguration(
        DirectoryAuthority authority,
        URI endpoint,
        String credential,
        String operatorRef,
        int maxEvents,
        int timeoutMillis) {
    public DirectoryPullConfiguration {
        if (authority == null
                || endpoint == null
                || credential == null
                || !credential.matches("[A-Za-z0-9_-]{43,128}")
                || maxEvents < 1
                || maxEvents > 1000
                || timeoutMillis < 100
                || timeoutMillis > 10000
                || !"/internal/directory/v1".equals(endpoint.getPath())
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || endpoint.getUserInfo() != null) {
            throw invalid();
        }
        TokenAuthority.validateUri(endpoint);
        BootstrapCommand.bounded(operatorRef, 160);
    }

    /** 所有权限范围由同一私密配置读取，与已登记来源不一致时由消费用例拒绝。 */
    public static DirectoryPullConfiguration from(Properties config) {
        try {
            var authority =
                    new DirectoryAuthority(
                            config.getProperty("directory.id"),
                            config.getProperty("directory.source"),
                            config.getProperty("directory.environment"),
                            config.getProperty("directory.source-tenant-ref"),
                            config.getProperty("directory.tenant-id"),
                            config.getProperty("directory.issuer"));
            return new DirectoryPullConfiguration(
                    authority,
                    URI.create(config.getProperty("directory.endpoint")),
                    config.getProperty("directory.credential"),
                    config.getProperty("directory.operator-ref"),
                    Integer.parseInt(config.getProperty("directory.max-events", "1000")),
                    Integer.parseInt(config.getProperty("directory.timeout-ms", "3000")));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    /** 凭据不进入 record 默认字符串或错误报告。 */
    @Override
    public String toString() {
        return "DirectoryPullConfiguration[credentials=redacted]";
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
