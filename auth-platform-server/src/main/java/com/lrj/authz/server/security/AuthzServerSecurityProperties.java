package com.lrj.authz.server.security;

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

    /** 返回本实例保存的配置启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 由受治理配置绑定启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 返回本实例保存的配置服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public String getToken() {
        return token;
    }

    /** 由受治理配置绑定服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public void setToken(String token) {
        this.token = token;
    }
}
