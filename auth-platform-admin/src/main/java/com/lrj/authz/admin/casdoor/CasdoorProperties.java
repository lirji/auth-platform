package com.lrj.authz.admin.casdoor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Casdoor 同步配置 (authz.casdoor.*)。enabled=false 时不启用同步。 */
@ConfigurationProperties(prefix = "authz.casdoor")
public class CasdoorProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private String clientId = "";
    private String clientSecret = "";
    private String organization = "built-in";
    /** SpiceDB subject id 取 Casdoor 用户的哪个字段: id(默认, =OIDC sub) 或 name。 */
    private String subjectField = "id";
    private boolean reconcileEnabled = false;
    private long reconcileIntervalMs = 300_000;
    /** 是否同步部门树到 SpiceDB {@code department}（部门层级授权模型）；默认 false（引入即安全，不动旧 group 同步）。 */
    private boolean departmentSyncEnabled = false;
    /** 一轮组同步允许的最大 DELETE 数; 超过则中止整轮不写 (防 Casdoor 拉取不全导致大面积撤权)。<0=不限制。 */
    private int deleteThreshold = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getSubjectField() { return subjectField; }
    public void setSubjectField(String subjectField) { this.subjectField = subjectField; }
    public boolean isReconcileEnabled() { return reconcileEnabled; }
    public void setReconcileEnabled(boolean reconcileEnabled) { this.reconcileEnabled = reconcileEnabled; }
    public boolean isDepartmentSyncEnabled() { return departmentSyncEnabled; }
    public void setDepartmentSyncEnabled(boolean departmentSyncEnabled) { this.departmentSyncEnabled = departmentSyncEnabled; }
    public long getReconcileIntervalMs() { return reconcileIntervalMs; }
    public void setReconcileIntervalMs(long reconcileIntervalMs) { this.reconcileIntervalMs = reconcileIntervalMs; }
    public int getDeleteThreshold() { return deleteThreshold; }
    public void setDeleteThreshold(int deleteThreshold) { this.deleteThreshold = deleteThreshold; }
}
