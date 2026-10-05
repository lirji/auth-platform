package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import com.lrj.authz.governance.domain.IdentityModels.MemberKind;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** 受控配置与命令分开建模，令牌原文只在边界短暂使用，不进入持久化模型。 */
public final class InvitationCommands {
    private InvitationCommands() {}

    /** 操作范围来自受控配置，不能从接受请求或创建命令中选择企业/负责人。 */
    public record Authority(String operatorRef, String tenantId, String sponsorMembershipId) {
        public Authority {
            BootstrapCommand.bounded(operatorRef, 160);
            BootstrapCommand.uuid(tenantId);
            BootstrapCommand.uuid(sponsorMembershipId);
        }
    }

    /** 创建命令含所有绑定事实和摘要，任何字段变化都不能重放原命令。 */
    public record Issue(
            String commandId,
            String invitationId,
            Authority authority,
            String issuer,
            String subject,
            MemberKind memberKind,
            String tokenHash,
            Instant expiresAt,
            Instant membershipValidTo,
            String reason) {
        public Issue {
            BootstrapCommand.uuid(commandId);
            BootstrapCommand.uuid(invitationId);
            BootstrapCommand.bounded(issuer, 500);
            BootstrapCommand.bounded(subject, 500);
            BootstrapCommand.bounded(reason, 1000);
            if (authority == null
                    || (memberKind != MemberKind.PARTNER && memberKind != MemberKind.GUEST)
                    || tokenHash == null
                    || !tokenHash.matches("[a-f0-9]{64}")
                    || expiresAt == null
                    || membershipValidTo == null
                    || !membershipValidTo.isAfter(expiresAt)
                    || expiresAt.getNano() % 1000 != 0
                    || membershipValidTo.getNano() % 1000 != 0) {
                throw invalid();
            }
            com.lrj.authz.governance.authentication.TokenAuthority.validateUri(URI.create(issuer));
        }

        /** 命令摘要包括权限范围和令牌摘要，不包含令牌原文。 */
        public String payloadHash() {
            return hashFields(
                    authority.operatorRef(),
                    authority.tenantId(),
                    authority.sponsorMembershipId(),
                    invitationId,
                    issuer,
                    subject,
                    memberKind.code(),
                    tokenHash,
                    expiresAt.toString(),
                    membershipValidTo.toString(),
                    reason);
        }

        @Override
        public String toString() {
            return "InvitationIssue[proof=redacted]";
        }
    }

    /** 撤销必须是原受控范围，已接受的成员不通过此命令停用。 */
    public record Revoke(
            String commandId,
            String invitationId,
            Authority authority,
            long expectedVersion,
            String reason) {
        public Revoke {
            BootstrapCommand.uuid(commandId);
            BootstrapCommand.uuid(invitationId);
            BootstrapCommand.bounded(reason, 1000);
            if (authority == null || expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
                throw invalid();
            }
        }

        /** 原因和预期版本参与幂等冲突检测。 */
        public String payloadHash() {
            return hashFields(
                    authority.operatorRef(),
                    authority.tenantId(),
                    authority.sponsorMembershipId(),
                    invitationId,
                    Long.toString(expectedVersion),
                    reason);
        }
    }

    /** 至少 256 位安全随机熵，base64url 无填充；原文仅进入 0600 文件和接受请求。 */
    public static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 只接受规范编码，避免多个文本表示同一随机字节序列。 */
    public static String tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException failure) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        if (raw.length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(token)) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        return digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hashFields(String... fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                for (String field : fields) {
                    output.writeUTF(field);
                }
            }
            return digest(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("邀请摘要不可用", failure);
        }
    }

    private static String digest(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM 缺少 SHA-256", failure);
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(INVALID_ARGUMENT);
    }
}
