package com.lrj.authz.server.legacy.configuration;

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

    /** 返回本实例保存的配置图目标地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getEndpoint() {
        return endpoint;
    }

    /** 由受治理配置绑定图目标地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
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
