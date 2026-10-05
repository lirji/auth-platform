package com.lrj.authz.server;

import com.lrj.authz.server.legacy.configuration.SpiceDbProperties;
import com.lrj.authz.server.security.AuthzServerSecurityProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 判权服务入口 (:8200)。业务方经 SDK 走 HTTP 到这里, 这里再调 SpiceDB。 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableConfigurationProperties({SpiceDbProperties.class, AuthzServerSecurityProperties.class})
public class AuthPlatformServerApplication {
    /** 启动当前应用组合根，治理与旧接口按已有配置启用，不能隐式迁移其他运行目标。 */
    public static void main(String[] args) {
        SpringApplication.run(AuthPlatformServerApplication.class, args);
    }
}
