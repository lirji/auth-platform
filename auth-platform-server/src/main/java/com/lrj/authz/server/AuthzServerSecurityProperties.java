package com.lrj.authz.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 判权服务写/读端最小鉴权 (authz.server.security.*)。
 *
 * <p>enabled=true 时 {@code /v1/**} 需请求头 {@code Authorization: Bearer <token>}；health/info 放行。
 * 默认<strong>关</strong>（dev/smoke 兼容，引入即安全）；<strong>生产 profile 必须开并配置 token</strong>——
 * 否则 {@code /v1/relationships} 等写端裸奔＝任何可达 :8200 者皆可改授权。
 *
 * <p>说明：这是"共享凭证"级别；按 check/write 能力分级 + 可写 resource/relation allowlist 属里程碑 C。
 */
@ConfigurationProperties(prefix = "authz.server.security")
public class AuthzServerSecurityProperties {

    /** 是否校验 /v1/** 的 service credential。默认关。 */
    private boolean enabled = false;

    /** 期望的 Bearer token；enabled=true 时必须非空。 */
    private String token = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
