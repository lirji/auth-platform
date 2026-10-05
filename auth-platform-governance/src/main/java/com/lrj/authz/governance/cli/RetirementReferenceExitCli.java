package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;

import java.io.PrintWriter;

/** 平台运维0600配置固定分区和对象，不允许Owner网页通过此工具改任意人员权限。 */
public final class RetirementReferenceExitCli {
    private RetirementReferenceExitCli() {}

    /** inspect只读固定摘要，exit单向退出；任何失败均返回非零及脱敏code。 */
    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) System.exit(code);
    }

    /** 不接收Token／密码命令行参数，不隐式迁移数据库或启用服务。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 2 || (!"inspect".equals(args[0]) && !"exit".equals(args[0])))
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            var c = GovernanceConfigurationFile.read(args[1]);
            var p =
                    new Partition(
                            c.getProperty("retirement.exit.tenant-id"),
                            c.getProperty("retirement.exit.application-id"),
                            c.getProperty("retirement.exit.environment"));
            var kind = RetirementReferenceExit.Kind.valueOf(c.getProperty("retirement.exit.kind"));
            String id = c.getProperty("retirement.exit.target-id");
            try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(c), false)) {
                if ("inspect".equals(args[0]))
                    output.println(runtime.retirementExit().inspect(p, kind, id));
                else {
                    runtime.retirementExit()
                            .exit(
                                    p,
                                    kind,
                                    id,
                                    c.getProperty("retirement.exit.expected-hash"),
                                    c.getProperty("retirement.exit.operator"),
                                    c.getProperty("retirement.exit.command-id"),
                                    c.getProperty("retirement.exit.reason"));
                    output.println("REFERENCE_DISABLED");
                }
            }
            return 0;
        } catch (GovernanceException failure) {
            error.println(failure.code().value());
            return 2;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            error.println("INVALID_ARGUMENT");
            return 2;
        } catch (Exception unavailable) {
            error.println("DEPENDENCY_UNAVAILABLE");
            return 3;
        }
    }
}
