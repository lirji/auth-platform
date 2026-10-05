package com.lrj.authz.governance.context.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.identity.application.IdentityGovernance;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.GovernanceDtos.ResolveRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 双身份上下文用例；服务先证明固定应用，再验证该应用的最终用户身份。 */
public final class InternalContextService {
    private static final int MAX_CALLERS = 32;
    private static final int MAX_SERVICE_CREDENTIAL_LENGTH = 512;
    private final List<CallerService> callers;
    private final IdentityGovernance identity;

    /** 配置唯一且有界；相同凭据不能绑定多个 app/environment，避免选择歧义。 */
    public InternalContextService(List<CallerService> callers, IdentityGovernance identity) {
        if (callers == null
                || callers.isEmpty()
                || callers.size() > MAX_CALLERS
                || identity == null) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
        HashSet<String> ids = new HashSet<>();
        HashSet<String> hashes = new HashSet<>();
        for (CallerService caller : callers) {
            if (!ids.add(caller.callerServiceId())
                    || !hashes.add(HexFormat.of().formatHex(caller.credentialHash()))) {
                throw new GovernanceException(INVALID_ARGUMENT);
            }
        }
        this.callers = List.copyOf(callers);
        this.identity = identity;
    }

    /** 请求只有租户和可选代际；解析成功仅是身份快照，P2 再执行应用准入。 */
    public AccessContext resolve(
            String serviceCredential, String userAccessToken, ResolveRequest request) {
        CallerService caller = authenticateCaller(serviceCredential);
        VerifiedLogin login = caller.userTokens().verify(userAccessToken);
        if (request == null) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
        var current =
                identity.contextForLogin(
                        login.issuer(),
                        login.subject(),
                        request.tenantId(),
                        request.expectedMembershipGeneration());
        return new AccessContext(
                current.principalId(),
                current.membershipId(),
                current.membershipGeneration(),
                current.membershipVersion(),
                current.principalVersion(),
                current.tenantId(),
                caller.applicationId(),
                caller.environment(),
                caller.callerServiceId(),
                com.lrj.authz.governance.identity.domain.IdentityModels.PrincipalKind.HUMAN.code(),
                UUID.randomUUID().toString());
    }

    /** HTTP 适配器在读取 body 前调用，无服务身份不能占用有界 body 读取或远端校验。 */
    public CallerService authenticateCaller(String value) {
        if (value == null
                || value.length() < 32
                || value.length() > MAX_SERVICE_CREDENTIAL_LENGTH
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        byte[] digest = digest(value);
        CallerService result = null;
        // 所有条目都做固定长度摘要比较；不按原文长度或匹配位置暴露比较进度。
        for (CallerService caller : callers) {
            if (MessageDigest.isEqual(digest, caller.credentialHash())) {
                result = caller;
            }
        }
        if (result == null) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        return result;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256");
        }
    }
}
