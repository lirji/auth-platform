package com.lrj.authz.governance.identity.application;

import com.lrj.authz.governance.shared.application.GovernanceException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 离线受控初始化输入，显式绑定旧引用，不提供首次登录自动引导。 */
public record BootstrapCommand(
        String commandId,
        String operatorRef,
        String tenantId,
        String tenantCode,
        String principalId,
        String issuer,
        String subject,
        String membershipId,
        Instant validFrom,
        Instant validTo,
        String sourceSystem,
        String sourceTenantRef,
        String sourceSubjectRef) {
    /** 先验证结构和时间，避免事务内保存部分非法绑定。 */
    public BootstrapCommand {
        uuid(commandId);
        uuid(tenantId);
        uuid(principalId);
        uuid(membershipId);
        bounded(operatorRef, 160);
        bounded(tenantCode, 100);
        bounded(issuer, 500);
        bounded(subject, 500);
        bounded(sourceSystem, 100);
        bounded(sourceTenantRef, 160);
        bounded(sourceSubjectRef, 160);
        URI uri;
        try {
            uri = URI.create(issuer);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme())
                                && ("localhost".equals(uri.getHost())
                                        || "127.0.0.1".equals(uri.getHost()))))) {
            throw invalid();
        }
        if (validFrom == null
                || (validTo != null && !validTo.isAfter(validFrom))
                || validFrom.getNano() % 1_000 != 0
                || (validTo != null && validTo.getNano() % 1_000 != 0)) {
            throw invalid();
        }
    }

    /** 长度前缀编码防止字段分隔符产生相同摘要；时间按 Instant 规范表达。 */
    public String payloadHash() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                for (String value :
                        new String[] {
                            operatorRef,
                            tenantId,
                            tenantCode,
                            principalId,
                            issuer,
                            subject,
                            membershipId,
                            validFrom.toString(),
                            validTo == null ? "" : validTo.toString(),
                            sourceSystem,
                            sourceTenantRef,
                            sourceSubjectRef
                        }) {
                    data.writeUTF(value);
                }
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("规范命令摘要不可用", e);
        }
    }

    /** UUID 必须是规范字符串，防止同一标识的多种文本表示。 */
    public static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw invalid();
            }
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    /** 身份字段不裁剪或大小写转换，避免发行方标识被错误合并。 */
    public static void bounded(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
