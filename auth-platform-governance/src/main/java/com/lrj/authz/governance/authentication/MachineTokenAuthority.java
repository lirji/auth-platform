package com.lrj.authz.governance.authentication;

import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.CatalogManifest;
import com.lrj.authz.governance.application.GovernanceException;
import static com.lrj.authz.governance.application.GovernanceException.Code.INVALID_ARGUMENT;

/** 受控发布slot，所有身份及目标来自后端私密配置，不从请求中构造发行方。 */
public record MachineTokenAuthority(String publisherId, String servicePrincipal, String applicationId,
                                    String targetInstanceId, String environment, String subject,
                                    TokenAuthority tokenAuthority) {
    /** 不共享受众，固定SERVICE及目标；生产禁止回环HTTP开发例外。 */
    public MachineTokenAuthority {
        BootstrapCommand.uuid(publisherId); BootstrapCommand.uuid(servicePrincipal);
        BootstrapCommand.uuid(targetInstanceId); CatalogManifest.code(applicationId); CatalogManifest.code(environment);
        BootstrapCommand.bounded(subject, 500);
        if (tokenAuthority == null || !tokenAuthority.audience().equals(tokenAuthority.clientId())
                || (("prod".equals(environment) || "production".equals(environment))
                && (!tokenAuthority.issuer().startsWith("https://") || !"https".equals(tokenAuthority.jwksUri().getScheme())))) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
    }
    /** record默认会打印完整配置，明确只暴露非敏感定位信息。 */
    @Override public String toString() { return "MachineTokenAuthority[publisherId=" + publisherId + ", credentials=redacted]"; }
}
