package com.lrj.authz.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SpiceDB 连接配置 (authz.spicedb.*)。 */
@ConfigurationProperties(prefix = "authz.spicedb")
public class SpiceDbProperties {

    /** SpiceDB HTTP API 端点。 */
    private String endpoint = "http://localhost:8543";

    /** SpiceDB preshared key (Bearer)。 */
    private String token = "authz_dev_key";

    /** 连接超时。 */
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);

    /** 读超时（lookup/expand 大结果需给足）。 */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(15);

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
