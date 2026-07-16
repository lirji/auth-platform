package com.lrj.authz.admin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计存储装配：按 authz.audit.persistence-enabled 在 {@link InMemoryAuditStore} 与 {@link JdbcAuditStore}
 * 之间二选一（互斥条件，无装配顺序依赖）。审计库独立于 SpiceDB 数据面——admin 应用本身仍无业务 DB，
 * 因此主应用排除了 DataSourceAutoConfiguration，这里自建专用小连接池。
 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfig {

    /** 持久化开启：专用 Hikari 池（小池即可——审计只有 grant/revoke 写 + 控制台读）。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "authz.audit", name = "persistence-enabled", havingValue = "true")
    public HikariDataSource auditDataSource(AuditProperties props) {
        if (props.getJdbcUrl() == null || props.getJdbcUrl().isBlank()) {
            throw new IllegalStateException(
                    "authz.audit.persistence-enabled=true 但 authz.audit.jdbc-url 为空 —— 审计持久化拒绝静默降级,请配置审计库(建议独立库 authz_admin)");
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("authz-audit");
        cfg.setJdbcUrl(props.getJdbcUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        cfg.setMaximumPoolSize(4);
        return new HikariDataSource(cfg);
    }

    @Bean
    @ConditionalOnProperty(prefix = "authz.audit", name = "persistence-enabled", havingValue = "true")
    public AuditStore jdbcAuditStore(HikariDataSource auditDataSource, AuditProperties props) {
        return new JdbcAuditStore(new JdbcTemplate(auditDataSource), props.getRetentionMaxRows());
    }

    @Bean
    @ConditionalOnProperty(prefix = "authz.audit", name = "persistence-enabled",
            havingValue = "false", matchIfMissing = true)
    public AuditStore inMemoryAuditStore(AuditProperties props) {
        return new InMemoryAuditStore(props.getInMemoryCapacity());
    }
}
