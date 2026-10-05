package com.lrj.authz.admin.security.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** admin 安全配置 (authz.security.*):校验 Casdoor 签发的 JWT + webhook 密钥。 */
@ConfigurationProperties(prefix = "authz.security")
public class AdminSecurityProperties {

    /** Casdoor JWKS 端点(验签)。 */
    private String jwkSetUri = "http://localhost:8000/.well-known/jwks";

    /** 期望的 issuer(与 token iss 一致)。 */
    private String issuer = "http://localhost:8000";

    /** 期望的 audience(= console 的 Casdoor client_id);空则跳过 aud 校验。 */
    private String clientId = "";

    /** webhook 共享密钥(请求头 X-Webhook-Secret);空则不校验(仅本地)。 */
    private String webhookSecret = "";

    /** 返回本实例保存的配置密钥来源，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getJwkSetUri() {
        return jwkSetUri;
    }

    /** 由受治理配置绑定密钥来源，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    /** 返回本实例保存的配置身份发行方，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getIssuer() {
        return issuer;
    }

    /** 由受治理配置绑定身份发行方，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** 返回本实例保存的配置客户端标识，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getClientId() {
        return clientId;
    }

    /** 由受治理配置绑定客户端标识，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /** 返回本实例保存的配置回调秘密，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public String getWebhookSecret() {
        return webhookSecret;
    }

    /** 由受治理配置绑定回调秘密，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}
