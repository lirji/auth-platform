package com.lrj.authz.admin.identity.casdoor.configuration;

import com.lrj.authz.admin.identity.casdoor.application.DepartmentSyncService;
import com.lrj.authz.admin.identity.casdoor.application.GroupSyncService;
import com.lrj.authz.admin.identity.casdoor.infrastructure.CasdoorClient;
import com.lrj.authz.protocol.AuthzEngine;

import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 用既有身份提供方配置装配客户端，HTTP输入不能替换机密或目标地址。 */
    @Bean
    public CasdoorClient casdoorClient(CasdoorProperties props) {
        return new CasdoorClient(props);
    }

    /** 将身份同步绑定既有默认图引擎和删除预算，避免跨工作区写入。 */
    @Bean
    public GroupSyncService groupSyncService(
            CasdoorClient client,
            @Qualifier("defaultAuthzEngine") AuthzEngine engine,
            CasdoorProperties props) {
        return new GroupSyncService(client, engine, props.getDeleteThreshold());
    }

    /** 部门树同步（部门层级授权模型）；仅 authz.casdoor.department-sync-enabled=true 时装配。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "authz.casdoor",
            name = "department-sync-enabled",
            havingValue = "true")
    public DepartmentSyncService departmentSyncService(
            CasdoorClient client,
            @Qualifier("defaultAuthzEngine") AuthzEngine engine,
            CasdoorProperties props) {
        return new DepartmentSyncService(client, engine, props.getDeleteThreshold());
    }
}
