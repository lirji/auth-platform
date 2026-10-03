package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.MachineTokenAuthority;
import com.lrj.authz.governance.authentication.TokenAuthority;
import com.lrj.authz.governance.domain.CatalogPublisherModels.Target;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import static com.lrj.authz.governance.application.GovernanceException.Code.INVALID_ARGUMENT;

/** 机器发布配置一次装配；目标与slot独立于用户请求，关闭入口时不读取机器密钥。 */
public record CatalogPublisherSettings(Target target, List<MachineTokenAuthority> authorities) {
    /** 一个启动实例只能绑定一个目标；固定集合有界且受众不得含糊。 */
    public CatalogPublisherSettings {
        if (target==null || authorities==null || authorities.isEmpty() || authorities.size()>16) throw invalid();
        BootstrapCommand.uuid(target.targetInstanceId()); CatalogManifest.code(target.environment());
        if (authorities.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        authorities=List.copyOf(authorities); var ids=new HashSet<String>(); var clients=new HashSet<List<String>>();
        for (var authority : authorities) {
            if (authority==null || !target.targetInstanceId().equals(authority.targetInstanceId())
                    || !target.environment().equals(authority.environment()) || !ids.add(authority.publisherId())
                    || !clients.add(List.of(authority.tokenAuthority().issuer(),authority.tokenAuthority().clientId()))) throw invalid();
        }
    }
    /** 私密属性只映射显式列出的slot，不根据JWT、body或URL临时创建配置。 */
    public static CatalogPublisherSettings from(Properties properties) {
        if (properties==null) throw invalid();
        var target=new Target(properties.getProperty("publisher.target-instance-id"),properties.getProperty("publisher.environment"));
        String configured=properties.getProperty("publisher.ids");
        if (configured==null || configured.length()>16*37) throw invalid();
        String[] ids=configured.split(",",-1); if (ids.length<1 || ids.length>16) throw invalid();
        var authorities=new ArrayList<MachineTokenAuthority>();
        for (String id : ids) {
            BootstrapCommand.uuid(id); String prefix="publisher."+id+"."; var token=new Properties();
            properties.stringPropertyNames().stream().filter(key -> key.startsWith(prefix))
                    .forEach(key -> token.setProperty(key.substring(prefix.length()),properties.getProperty(key)));
            authorities.add(new MachineTokenAuthority(id,token.getProperty("service-principal-id"),token.getProperty("application"),
                    target.targetInstanceId(),target.environment(),token.getProperty("subject"),TokenAuthority.from(token)));
        }
        return new CatalogPublisherSettings(target,authorities);
    }
    /** 即使日志打印整个配置，也不得泄露认证密钥。 */
    @Override public String toString() { return "CatalogPublisherSettings[target="+target+", credentials=redacted]"; }
    private static GovernanceException invalid() { return new GovernanceException(INVALID_ARGUMENT); }
}
