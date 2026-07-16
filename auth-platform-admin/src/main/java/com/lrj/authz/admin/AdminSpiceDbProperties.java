package com.lrj.authz.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SpiceDB 连接 (authz.spicedb.*)。admin 作为可信后端直连 SpiceDB HTTP API。 */
@ConfigurationProperties(prefix = "authz.spicedb")
public class AdminSpiceDbProperties {

    private String endpoint = "http://localhost:8543";
    private String token = "authz_dev_key";
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);
    /** 读超时（admin 读一律 full 一致性 + 同步批量写,给足）。 */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(30);

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
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
