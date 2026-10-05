package com.lrj.authz.admin;

import com.lrj.authz.admin.configuration.AdminSpiceDbProperties;
import com.lrj.authz.admin.security.configuration.AdminSecurityProperties;
import com.lrj.authz.admin.workspace.configuration.WorkspaceProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 授权管理服务入口 (:8201)。管控台 (auth-console) 的后端 + Casdoor 同步。
 * 排除默认 DataSource/Flyway 自动装配：审计池与治理 Runtime 分别按显式开关装配，
 * 不能让新增治理依赖接管审计库或使默认关闭时因缺 datasource 配置而启动失败。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableConfigurationProperties({
    AdminSpiceDbProperties.class,
    AdminSecurityProperties.class,
    WorkspaceProperties.class
})
public class AuthPlatformAdminApplication {
    /** 启动当前应用组合根，治理与旧接口按已有配置启用，不能隐式迁移其他运行目标。 */
    public static void main(String[] args) {
        SpringApplication.run(AuthPlatformAdminApplication.class, args);
    }
}
