package com.lrj.authz.admin;

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

    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
