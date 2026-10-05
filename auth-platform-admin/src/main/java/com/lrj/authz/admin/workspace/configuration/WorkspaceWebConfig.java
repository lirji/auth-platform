package com.lrj.authz.admin.workspace.configuration;

import com.lrj.authz.admin.workspace.application.WorkspaceRegistry;
import com.lrj.authz.admin.workspace.web.WorkspaceInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 工作区HTTP边界装配；请求结束必须释放线程绑定，不能把前一请求目标带入下一请求。 */
@Configuration
public class WorkspaceWebConfig implements WebMvcConfigurer {

    private final WorkspaceRegistry registry;

    /** 显式绑定 WorkspaceWebConfig 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public WorkspaceWebConfig(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    /** 按既有管理路径安装工作区边界，使同一请求的引擎选择保持一致。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkspaceInterceptor(this.registry))
                .addPathPatterns("/admin/**");
    }
}
