package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.persistence.*;

import java.io.PrintWriter;
import java.util.List;

/** 首个管理者只允许受控配置引导，永远不开放匿名/首次登录提权接口。 */
public final class AccessBootstrapCli {
    private AccessBootstrapCli() {}

    /** 文件包含明确分区、成员代际、能力集合与最长时限，凭据不进命令行。 */
    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) System.exit(code);
    }

    /** 所有配置继承0600私密文件规则，重复引导不扩大既有委派。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 1)
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            var p = GovernanceConfigurationFile.read(args[0]);
            var partition =
                    new Partition(
                            p.getProperty("access.tenant"),
                            p.getProperty("access.application"),
                            p.getProperty("access.environment"));
            var delegation =
                    new Delegation(
                            p.getProperty("access.manager"),
                            Long.parseLong(p.getProperty("access.generation")),
                            AccessValues.json(
                                    List.of(p.getProperty("access.capabilities").split(",", -1))),
                            Long.parseLong(p.getProperty("access.max-duration-seconds")));
            try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(p), false)) {
                runtime.access()
                        .bootstrap(
                                partition,
                                delegation,
                                p.getProperty("access.operator"),
                                p.getProperty("access.command"));
            }
            output.println("ACCESS_BOOTSTRAPPED");
            return 0;
        } catch (GovernanceException e) {
            error.println(e.code().value());
            return 2;
        } catch (IllegalArgumentException | NullPointerException e) {
            error.println("INVALID_ARGUMENT");
            return 2;
        } catch (Exception e) {
            error.println("DEPENDENCY_UNAVAILABLE");
            return 3;
        }
    }
}
