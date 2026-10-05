package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.application.InvitationCommands.*;
import com.lrj.authz.governance.domain.IdentityModels.MemberKind;
import com.lrj.authz.governance.persistence.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;

/** 邀请操作限于私密配置的企业/负责人，令牌原文只写显式指定的私密文件。 */
public final class InvitationCli {
    private static final Set<String> ISSUE_FIELDS =
            Set.of(
                    "command.id",
                    "invitation.id",
                    "issuer",
                    "subject",
                    "member.kind",
                    "expires.at",
                    "membership.valid.to",
                    "reason");
    private static final Set<String> REVOKE_FIELDS =
            Set.of("command.id", "invitation.id", "expected.version", "reason");

    private InvitationCli() {}

    /** issue 配置 命令 令牌文件；revoke 配置 命令，均不回显秘密或原因。 */
    public static void main(String[] args) {
        int result =
                run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (result != 0) {
            System.exit(result);
        }
    }

    /** 数据库失败仅输出稳定错误；初次令牌文件保留以支持安全重试，不改写旧原文。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length < 3) {
                throw invalid();
            }
            Action action = Action.parse(args[0]);
            if (args.length != (action == Action.ISSUE ? 4 : 3)) {
                throw invalid();
            }
            Properties configuration = GovernanceConfigurationFile.read(args[1]);
            Properties input = GovernanceConfigurationFile.read(args[2]);
            Authority authority =
                    new Authority(
                            configuration.getProperty("invitation.operator-ref"),
                            configuration.getProperty("invitation.tenant-id"),
                            configuration.getProperty("invitation.sponsor-membership-id"));
            if (!input.stringPropertyNames()
                    .equals(action == Action.ISSUE ? ISSUE_FIELDS : REVOKE_FIELDS)) {
                throw invalid();
            }
            String invitationId = input.getProperty("invitation.id");
            BootstrapCommand.uuid(invitationId);
            try (GovernanceRuntime runtime =
                    GovernanceRuntime.open(GovernanceDatabase.from(configuration), false)) {
                var result =
                        action == Action.ISSUE
                                ? runtime.invitations()
                                        .issue(
                                                new Issue(
                                                        input.getProperty("command.id"),
                                                        invitationId,
                                                        authority,
                                                        input.getProperty("issuer"),
                                                        input.getProperty("subject"),
                                                        kind(input.getProperty("member.kind")),
                                                        InvitationCommands.tokenHash(
                                                                tokenFile(
                                                                        Path.of(args[3]),
                                                                        invitationId)),
                                                        Instant.parse(
                                                                input.getProperty("expires.at")),
                                                        Instant.parse(
                                                                input.getProperty(
                                                                        "membership.valid.to")),
                                                        input.getProperty("reason")))
                                : runtime.invitations()
                                        .revoke(
                                                new Revoke(
                                                        input.getProperty("command.id"),
                                                        invitationId,
                                                        authority,
                                                        Long.parseLong(
                                                                input.getProperty(
                                                                        "expected.version")),
                                                        input.getProperty("reason")));
                output.printf(
                        "%s %s version=%d%n", result.id(), result.state().code(), result.version());
                output.flush();
            }
            return 0;
        } catch (GovernanceException failure) {
            error.println(failure.code().value());
            return 2;
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            error.println(GovernanceException.Code.INVALID_ARGUMENT.value());
            return 2;
        } catch (Exception failure) {
            // CLI 是脱敏边界；数据库/文件异常非零退出，不能回显可能包含凭据的原因链。
            error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.value());
            return 3;
        }
    }

    private static String tokenFile(Path path, String invitationId) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            String token = InvitationCommands.newToken();
            // CREATE_NEW 与 0600 一起生效，避免原文写入时出现公共可读窗口。
            try (var channel =
                            Files.newByteChannel(
                                    path,
                                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rw-------")));
                    var writer =
                            new OutputStreamWriter(
                                    java.nio.channels.Channels.newOutputStream(channel),
                                    java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write("invitation.id=" + invitationId + "\ntoken=" + token + "\n");
            }
        }
        Properties stored = GovernanceConfigurationFile.read(path.toString());
        if (!stored.stringPropertyNames().equals(Set.of("invitation.id", "token"))
                || !invitationId.equals(stored.getProperty("invitation.id"))) {
            throw invalid();
        }
        String token = stored.getProperty("token");
        InvitationCommands.tokenHash(token);
        return token;
    }

    private static MemberKind kind(String code) {
        for (MemberKind value : MemberKind.values()) {
            if (value.code().equals(code) && value != MemberKind.EMPLOYEE) {
                return value;
            }
        }
        throw invalid();
    }

    private enum Action {
        ISSUE("issue"),
        REVOKE("revoke");
        private final String code;

        Action(String code) {
            this.code = code;
        }

        private static Action parse(String text) {
            for (Action value : values()) {
                if (value.code.equals(text)) {
                    return value;
                }
            }
            throw invalid();
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
