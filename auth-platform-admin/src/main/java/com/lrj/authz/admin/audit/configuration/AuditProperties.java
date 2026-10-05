package com.lrj.authz.admin.audit.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计存储配置 (authz.audit.*)。默认内存（引入即安全，行为与历史版本一致）；
 * persistence-enabled=true 时落 Postgres（jdbc-url 必填，独立于 SpiceDB 的库，建议 authz_admin）。
 */
@ConfigurationProperties(prefix = "authz.audit")
public class AuditProperties {

    /** true=JDBC 持久化（jdbc-url 必填）；false(默认)=内存环形缓冲。 */
    private boolean persistenceEnabled = false;

    private String jdbcUrl = "";
    private String username = "";
    private String password = "";

    /** 持久化保留的最大行数（写入后裁剪最旧；<=0 不裁剪）。 */
    private int retentionMaxRows = 100_000;

    /** 内存实现的环形缓冲容量。 */
    private int inMemoryCapacity = 500;

    /** 返回本实例保存的配置持久化开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /** 由受治理配置绑定持久化开关，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
    }

    /** 返回本实例保存的配置审计库地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /** 由受治理配置绑定审计库地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** 返回本实例保存的配置审计库身份，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public String getUsername() {
        return username;
    }

    /** 由受治理配置绑定审计库身份，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setUsername(String username) {
        this.username = username;
    }

    /** 返回本实例保存的配置数据库凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public String getPassword() {
        return password;
    }

    /** 由受治理配置绑定数据库凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
    public void setPassword(String password) {
        this.password = password;
    }

    /** 返回本实例保存的配置审计保留预算，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public int getRetentionMaxRows() {
        return retentionMaxRows;
    }

    /** 由受治理配置绑定审计保留预算，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setRetentionMaxRows(int retentionMaxRows) {
        this.retentionMaxRows = retentionMaxRows;
    }

    /** 返回本实例保存的配置易失审计容量，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public int getInMemoryCapacity() {
        return inMemoryCapacity;
    }

    /** 由受治理配置绑定易失审计容量，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setInMemoryCapacity(int inMemoryCapacity) {
        this.inMemoryCapacity = inMemoryCapacity;
    }
}
