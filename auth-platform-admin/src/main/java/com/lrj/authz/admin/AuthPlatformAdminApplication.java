package com.lrj.authz.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 授权管理服务入口 (:8201)。管控台 (auth-console) 的后端 + Casdoor 同步。
 * 排除 DataSourceAutoConfiguration：admin 无业务 DB，唯一的 DataSource 是审计持久化的专用池，
 * 由 {@link AuditConfig} 按 authz.audit.persistence-enabled 显式装配（默认关时不能因缺 datasource 配置而启动失败）。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({AdminSpiceDbProperties.class, AdminSecurityProperties.class})
public class AuthPlatformAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthPlatformAdminApplication.class, args);
    }
}
