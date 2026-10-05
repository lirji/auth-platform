package com.lrj.authz.server.legacy.configuration;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.protocol.AuthzEngine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 旧判权服务的图适配组合根；连接目标与预算由既有配置绑定，不从请求学习信任边界。 */
@Configuration
public class AuthzConfig {

    /** 在当前组合根绑定既有判权目标与超时预算，不能隐式回退其他引擎。 */
    @Bean
    public AuthzEngine authzEngine(SpiceDbProperties props) {
        return new SpiceDbAuthzEngine(
                props.getEndpoint(),
                props.getToken(),
                props.getConnectTimeout(),
                props.getReadTimeout());
    }
}
