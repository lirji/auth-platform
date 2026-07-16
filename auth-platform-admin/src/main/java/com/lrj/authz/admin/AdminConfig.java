package com.lrj.authz.admin;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminConfig {

    @Bean
    public AuthzEngine authzEngine(AdminSpiceDbProperties props) {
        return new SpiceDbAuthzEngine(props.getEndpoint(), props.getToken(),
                props.getConnectTimeout(), props.getReadTimeout());
    }
}
