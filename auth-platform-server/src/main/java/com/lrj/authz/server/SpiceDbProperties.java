package com.lrj.authz.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SpiceDB 连接配置 (authz.spicedb.*)。 */
@ConfigurationProperties(prefix = "authz.spicedb")
public class SpiceDbProperties {

    /** SpiceDB HTTP API 端点。 */
    private String endpoint = "http://localhost:8543";

    /** SpiceDB preshared key (Bearer)。 */
    private String token = "authz_dev_key";

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
}
