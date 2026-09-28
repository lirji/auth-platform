package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.GovernanceException;
import com.lrj.authz.governance.domain.IdentityModels.Membership;
import com.lrj.authz.governance.persistence.GovernanceDatabase;
import com.lrj.authz.governance.persistence.GovernanceRuntime;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;

/** 离线受控治理入口，秘密只从权限受限文件读取，不提供匿名 HTTP 初始化。 */
public final class GovernanceCli {
    private GovernanceCli() {}

    /** bootstrap/lookup 显式指定配置文件及操作文件；失败只输出稳定码。 */
    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        PrintWriter error = new PrintWriter(System.err, true);
        int exit = run(args, output, error);
        if (exit != 0) { System.exit(exit); }
    }

    /** 可验证的 CLI 边界；不打印配置、命令原文、Token 或数据库异常。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 3 || !("bootstrap".equals(args[0]) || "lookup".equals(args[0]))) {
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            }
            Properties database = privateProperties(Path.of(args[1]));
            Properties input = privateProperties(Path.of(args[2]));
            try (GovernanceRuntime runtime = GovernanceRuntime.open(GovernanceDatabase.from(database), "bootstrap".equals(args[0]))) {
                if ("bootstrap".equals(args[0])) {
                    writeMember(output, runtime.identity().bootstrapEmployee(command(input)));
                } else {
                    for (Membership member : runtime.identity().membershipsForLogin(input.getProperty("issuer"), input.getProperty("subject"))) {
                        writeMember(output, member);
                    }
                }
            }
            return 0;
        } catch (GovernanceException failure) {
            error.println(failure.code().name());
            return 2;
        } catch (java.time.DateTimeException | IllegalArgumentException failure) {
            error.println(GovernanceException.Code.INVALID_ARGUMENT.name());
            return 2;
        } catch (Exception failure) {
            // 包括文件、迁移与连接失败；不能以原始异常文本泄露凭据。
            error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.name());
            return 3;
        }
    }

    private static BootstrapCommand command(Properties input) {
        String until = input.getProperty("valid.to", "");
        return new BootstrapCommand(input.getProperty("command.id"), input.getProperty("operator.ref"),
                input.getProperty("tenant.id"), input.getProperty("tenant.code"), input.getProperty("principal.id"),
                input.getProperty("issuer"), input.getProperty("subject"), input.getProperty("membership.id"),
                Instant.parse(input.getProperty("valid.from")), until.isBlank() ? null : Instant.parse(until),
                input.getProperty("source.system"), input.getProperty("source.tenant.ref"), input.getProperty("source.subject.ref"));
    }

    private static Properties privateProperties(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || !Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        Properties input = new Properties();
        try (var reader = Files.newBufferedReader(path)) { input.load(reader); }
        return input;
    }

    private static void writeMember(PrintWriter output, Membership member) {
        output.printf("%s %s %s %s generation=%d version=%d%n", member.id(), member.tenantId(),
                member.memberKind(), member.status(), member.generation(), member.version());
        output.flush();
    }
}
