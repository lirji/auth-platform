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

    /** 返回本实例保存的配置启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 由受治理配置绑定启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 返回本实例保存的配置判权服务地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getServerUrl() {
        return serverUrl;
    }

    /** 由受治理配置绑定判权服务地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /** 返回本实例保存的配置服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public String getToken() {
        return token;
    }

    /** 由受治理配置绑定服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public void setToken(String token) {
        this.token = token;
    }

    /** 返回本实例保存的配置连接期限，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    /** 由受治理配置绑定连接期限，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** 返回本实例保存的配置读取期限，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public java.time.Duration getReadTimeout() {
        return readTimeout;
    }

    /** 由受治理配置绑定读取期限，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setReadTimeout(java.time.Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
