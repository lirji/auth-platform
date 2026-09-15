package com.lrj.authz.admin;

import com.lrj.authz.admin.workspace.WorkspaceRegistry;
import com.lrj.authz.admin.workspace.WorkspaceRoutingEngine;
import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AdminConfig {

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
