package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.*;
import java.io.PrintWriter;
import java.util.Set;

/** 仅对持有专用数据库操作权及私密配置的运维开放，不提供无管理授权的 HTTP 写接口。 */
public final class LifecycleCli {
    private static final Set<String> COMMAND_FIELDS = Set.of("command.id", "target.id", "expected.version", "reason");
    private LifecycleCli() {}

    /** 显式子命令、0600 配置、0600 命令文件；凭据和原因不回显。 */
    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) { System.exit(code); }
    }

    /** 操作者和范围只来自配置；调用者提交的命令文件无身份或租户字段。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 3) { throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
            var operation = LifecycleCommand.Operation.fromCli(args[0]);
            var configuration = GovernanceConfigurationFile.read(args[1]);
            var input = GovernanceConfigurationFile.read(args[2]);
            if (!input.stringPropertyNames().equals(COMMAND_FIELDS)) { throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
            var command = new LifecycleCommand(input.getProperty("command.id"), configuration.getProperty("lifecycle.operator-ref"),
                    configuration.getProperty("lifecycle.scope"), operation, input.getProperty("target.id"),
                    Long.parseLong(input.getProperty("expected.version")), input.getProperty("reason"));
            // 停用操作不承担迁移，防止业务动作暗中成为 schema owner。
            try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(configuration), false)) {
                var receipt = runtime.lifecycle().apply(command);
                output.printf("%s %s version=%d%n", receipt.targetId(), receipt.status(), receipt.version());
                output.flush();
            }
            return 0;
        } catch (GovernanceException failure) {
            error.println(failure.code().value()); return 2;
        } catch (IllegalArgumentException failure) {
            error.println(GovernanceException.Code.INVALID_ARGUMENT.value()); return 2;
        } catch (Exception failure) {
            error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.value()); return 3;
        }
    }
}
