package com.lrj.authz.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SDK 配置 (authz.client.*)。 */
@ConfigurationProperties(prefix = "authz.client")
public class AuthzClientProperties {

    /** 是否启用授权 SDK。关时不注册 AuthzEngine (消费方可用自己的 Noop 兜底)。 */
    private boolean enabled = true;

    /** auth-platform-server 判权服务地址。 */
    private String serverUrl = "http://localhost:8200";

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
}
