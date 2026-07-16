package com.lrj.authz.admin;

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

    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public void setPersistenceEnabled(boolean persistenceEnabled) { this.persistenceEnabled = persistenceEnabled; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getRetentionMaxRows() { return retentionMaxRows; }
    public void setRetentionMaxRows(int retentionMaxRows) { this.retentionMaxRows = retentionMaxRows; }
    public int getInMemoryCapacity() { return inMemoryCapacity; }
    public void setInMemoryCapacity(int inMemoryCapacity) { this.inMemoryCapacity = inMemoryCapacity; }
}
