package com.lrj.authz.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/** 判权服务入口 (:8200)。业务方经 SDK 走 HTTP 到这里, 这里再调 SpiceDB。 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableConfigurationProperties({SpiceDbProperties.class, AuthzServerSecurityProperties.class})
public class AuthPlatformServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthPlatformServerApplication.class, args);
    }
}
