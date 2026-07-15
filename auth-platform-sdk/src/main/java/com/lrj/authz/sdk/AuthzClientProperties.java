package com.lrj.authz.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SDK 配置 (authz.client.*)。 */
@ConfigurationProperties(prefix = "authz.client")
public class AuthzClientProperties {

    /** 是否启用授权 SDK。关时不注册 AuthzEngine (消费方可用自己的 Noop 兜底)。 */
    private boolean enabled = true;

    /** auth-platform-server 判权服务地址。 */
    private String serverUrl = "http://localhost:8200";

    /** 调 server 的 service credential (Bearer)。空则不带 Authorization 头（server 端未开鉴权时用）。 */
    private String token = "";

    /** 连接超时（防判权服务不可达时长时间占用请求线程）。 */
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);

    /** 读超时。 */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public java.time.Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(java.time.Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
