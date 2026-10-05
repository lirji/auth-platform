package com.lrj.authz.sdk;

import com.lrj.authz.protocol.AuthzEngine;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** SDK 自动装配。消费方引入本 starter 即得 AuthzEngine bean; 提供 SubjectResolver bean 则额外启用 @CheckAccess。 */
@AutoConfiguration
@EnableConfigurationProperties(AuthzClientProperties.class)
@ConditionalOnProperty(
        prefix = "authz.client",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AuthzSdkAutoConfiguration {

    /** 在当前组合根绑定既有判权目标与超时预算，不能隐式回退其他引擎。 */
    @Bean
    @ConditionalOnMissingBean
    public AuthzEngine authzEngine(AuthzClientProperties props) {
        return new RemoteAuthzEngine(
                props.getServerUrl(),
                props.getToken(),
                props.getConnectTimeout(),
                props.getReadTimeout());
    }

    /** 装配已配置引擎的注解拦截器，使判权入口与宿主上下文保持一致。 */
    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    @ConditionalOnBean(SubjectResolver.class)
    @ConditionalOnMissingBean
    public CheckAccessAspect checkAccessAspect(AuthzEngine engine, SubjectResolver resolver) {
        return new CheckAccessAspect(engine, resolver);
    }
}
