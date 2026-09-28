package com.lrj.authz.server.governance;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.TokenAuthority;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** 独立治理检查配置：默认关闭，不把旧context调用权限隐式升级为业务判权。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"authz.governance.enabled","authz.governance.access.enabled"},havingValue="true")
public class GovernanceAccessConfiguration {
    /** 显式服务允许名单和专属图凭据；缺配置不启动新路径。 */
    @Bean AccessSettings governanceAccessSettings(Environment environment){
        var p=GovernanceConfigurationFile.read(environment.getProperty("authz.governance.configuration"));
        String http=p.getProperty("graph.http"),key=p.getProperty("graph.key"),callers=p.getProperty("access.check.callers");
        if(http==null||key==null||key.length()<32||callers==null)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        TokenAuthority.validateUri(URI.create(http));Set<String> allowed=new HashSet<>(List.of(callers.split(",",-1)));
        if(allowed.isEmpty()||allowed.size()>32)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        allowed.forEach(CatalogManifest::code);
        return new AccessSettings(new SpiceDbAuthzEngine(http,key,Duration.ofSeconds(1),Duration.ofSeconds(3)),Set.copyOf(allowed));
    }
    /** 图为派生投影；每次判权仍查询权威SQL当前资格。 */
    @Bean AccessAuthorization governanceAccess(GovernanceRuntime runtime,AccessSettings settings){return runtime.authorization(settings.graph());}
    /** 只供服务内部装配，不序列化图客户端或服务身份。 */
    record AccessSettings(com.lrj.authz.protocol.AuthzEngine graph,Set<String> callers){}
}
