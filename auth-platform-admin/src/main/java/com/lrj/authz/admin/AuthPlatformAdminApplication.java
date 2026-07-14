package com.lrj.authz.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 授权管理服务入口 (:8201)。管控台 (auth-console) 的后端 + Casdoor 同步。 */
@SpringBootApplication
@EnableConfigurationProperties({AdminSpiceDbProperties.class, AdminSecurityProperties.class})
public class AuthPlatformAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthPlatformAdminApplication.class, args);
    }
}
