package com.lrj.authz.admin.workspace;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WorkspaceWebConfig implements WebMvcConfigurer {

    private final WorkspaceRegistry registry;

    public WorkspaceWebConfig(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkspaceInterceptor(this.registry)).addPathPatterns("/admin/**");
    }
}
