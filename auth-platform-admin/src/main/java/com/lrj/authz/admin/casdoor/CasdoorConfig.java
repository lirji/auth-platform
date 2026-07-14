package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** authz.casdoor.enabled=true 时启用 Casdoor 组同步 (client + sync service + 定时对账)。 */
@Configuration
@ConditionalOnProperty(prefix = "authz.casdoor", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CasdoorProperties.class)
@EnableScheduling
public class CasdoorConfig {

    @Bean
    public CasdoorClient casdoorClient(CasdoorProperties props) {
        return new CasdoorClient(props);
    }

    @Bean
    public GroupSyncService groupSyncService(CasdoorClient client, AuthzEngine engine) {
        return new GroupSyncService(client, engine);
    }
}
