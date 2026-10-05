package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.CasdoorAccessTokenVerifier;

import java.util.HexFormat;

/** 固定的应用后端绑定；只支持本片 context.resolve，不能从请求体覆盖。 */
public record CallerService(
        String callerServiceId,
        String applicationId,
        String environment,
        byte[] credentialHash,
        CasdoorAccessTokenVerifier userTokens) {
    /** 服务只存 SHA-256 摘要；数组防御复制，避免运行中的配置被调用方改写。 */
    public CallerService {
        BootstrapCommand.bounded(callerServiceId, 100);
        BootstrapCommand.bounded(applicationId, 100);
        BootstrapCommand.bounded(environment, 40);
        if (!callerServiceId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                || !applicationId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                || !environment.matches("[a-z0-9][a-z0-9-]*")
                || credentialHash == null
                || credentialHash.length != 32
                || userTokens == null) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        credentialHash = credentialHash.clone();
    }

    /** 解析受控部署文件的摘要，格式失败不回显 secret/文件原文。 */
    public static byte[] parseHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        return HexFormat.of().parseHex(value);
    }

    /** 摘要也不能成为共享可变配置。 */
    @Override
    public byte[] credentialHash() {
        return credentialHash.clone();
    }

    /** 运维/错误日志只显示脱敏标识，不显示凭据或内部 verifier。 */
    @Override
    public String toString() {
        return "CallerService[credentials=redacted]";
    }
}
