package com.lrj.authz.admin.identity.casdoor.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Casdoor 同步配置 (authz.casdoor.*)。enabled=false 时不启用同步。 */
@ConfigurationProperties(prefix = "authz.casdoor")
public class CasdoorProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private String clientId = "";
    private String clientSecret = "";
    private String organization = "built-in";

    /** 多 org 同步列表；空(默认)=只同步单个 {@link #organization}。组/部门 id 天然带 org 前缀,多 org 合并无碰撞。 */
    private List<String> organizations = new ArrayList<>();

    /** SpiceDB subject id 取 Casdoor 用户的哪个字段: id(默认, =OIDC sub) 或 name。 */
    private String subjectField = "id";

    private boolean reconcileEnabled = false;
    private long reconcileIntervalMs = 300_000;

    /** 是否同步部门树到 SpiceDB {@code department}（部门层级授权模型）；默认 false（引入即安全，不动旧 group 同步）。 */
    private boolean departmentSyncEnabled = false;

    /** 一轮组同步允许的最大 DELETE 数; 超过则中止整轮不写 (防 Casdoor 拉取不全导致大面积撤权)。<0=不限制。 */
    private int deleteThreshold = 1000;

    /** 返回本实例保存的配置启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 由受治理配置绑定启用条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 返回本实例保存的配置身份提供方地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 由受治理配置绑定身份提供方地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 返回本实例保存的配置客户端标识，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getClientId() {
        return clientId;
    }

    /** 由受治理配置绑定客户端标识，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /** 返回本实例保存的配置客户端秘密，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public String getClientSecret() {
        return clientSecret;
    }

    /** 由受治理配置绑定客户端秘密，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /** 返回本实例保存的配置组织条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getOrganization() {
        return organization;
    }

    /** 由受治理配置绑定组织条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setOrganization(String organization) {
        this.organization = organization;
    }

    /** 返回本实例保存的配置组织集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public List<String> getOrganizations() {
        return organizations;
    }

    /** 由受治理配置绑定组织集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setOrganizations(List<String> organizations) {
        this.organizations = organizations;
    }

    /** 生效的同步 org 列表：organizations 非空用之，否则回退单 organization。 */
    public List<String> effectiveOrganizations() {
        return organizations != null && !organizations.isEmpty()
                ? organizations
                : List.of(organization);
    }

    /** 返回本实例保存的配置主体字段，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getSubjectField() {
        return subjectField;
    }

    /** 由受治理配置绑定主体字段，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setSubjectField(String subjectField) {
        this.subjectField = subjectField;
    }

    /** 返回本实例保存的配置对账开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    /** 由受治理配置绑定对账开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    /** 返回本实例保存的配置部门同步开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isDepartmentSyncEnabled() {
        return departmentSyncEnabled;
    }

    /** 由受治理配置绑定部门同步开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setDepartmentSyncEnabled(boolean departmentSyncEnabled) {
        this.departmentSyncEnabled = departmentSyncEnabled;
    }

    /** 返回本实例保存的配置对账周期，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    /** 由受治理配置绑定对账周期，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    /** 返回本实例保存的配置删除阈值，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public int getDeleteThreshold() {
        return deleteThreshold;
    }

    /** 由受治理配置绑定删除阈值，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setDeleteThreshold(int deleteThreshold) {
        this.deleteThreshold = deleteThreshold;
    }
}
