package com.lrj.authz.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SpiceDB 连接 (authz.spicedb.*)。admin 作为可信后端直连 SpiceDB HTTP API。 */
@ConfigurationProperties(prefix = "authz.spicedb")
public class AdminSpiceDbProperties {

    private String endpoint = "http://localhost:8543";
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
