package com.lrj.authz.admin.configuration;

import com.lrj.authz.admin.workspace.application.WorkspaceRegistry;
import com.lrj.authz.admin.workspace.application.WorkspaceRoutingEngine;
import com.lrj.authz.protocol.AuthzEngine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 管理端引擎组合根；默认目标与工作区路由分开绑定，避免工作区请求写入错误图目标。 */
@Configuration
public class AdminConfig {

    /** 固定提供未绑定工作区时的既有默认引擎，路由不能另造隐式目标。 */
    @Bean
    @Qualifier("defaultAuthzEngine")
    public AuthzEngine defaultAuthzEngine(WorkspaceRegistry registry) {
        return registry.defaultEngine();
    }

    /** 管控台读写走请求绑定的工作区引擎；无头时回退默认 SpiceDB。 */
    @Bean
    @Primary
    public AuthzEngine authzEngine(WorkspaceRegistry registry) {
        return new WorkspaceRoutingEngine(registry);
    }
}
