package com.lrj.authz.server;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthzConfig {

    @Bean
    public AuthzEngine authzEngine(SpiceDbProperties props) {
        return new SpiceDbAuthzEngine(props.getEndpoint(), props.getToken(),
                props.getConnectTimeout(), props.getReadTimeout());
    }
}
