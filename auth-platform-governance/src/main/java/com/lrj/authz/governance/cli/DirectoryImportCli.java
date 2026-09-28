package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.persistence.*;
import java.io.PrintWriter;

/** 固定来源的有界一次性接入工具，仅持有治理数据库操作权与 0600 配置的运维可运行。 */
public final class DirectoryImportCli {
    private DirectoryImportCli() {}
    /** register 为显式首次登记，pull 为已登记来源增量恢复；任何失败返回非零，凭据不回显。 */
    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) { System.exit(code); }
    }
    /** 不自动建企业或迁移数据库，不能由消息声明提权到新的来源范围。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 2 || !("register".equals(args[0]) || "pull".equals(args[0]))) {
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            }
            var properties = GovernanceConfigurationFile.read(args[1]);
            var configuration = DirectoryPullConfiguration.from(properties);
            try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(properties), false);
                 var importer = new DirectoryPullImporter(runtime.directory(), configuration)) {
                if ("register".equals(args[0])) { importer.register(); output.println("PASS: directory source registered"); }
                else { var result = importer.pull(); output.printf("PASS: processed=%d last_sequence=%d%n", result.processed(), result.lastSequence()); }
                output.flush();
            }
            return 0;
        } catch (GovernanceException failure) { error.println(failure.code().value()); return 2; }
        catch (IllegalArgumentException failure) { error.println(GovernanceException.Code.INVALID_ARGUMENT.value()); return 2; }
        catch (Exception failure) { error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.value()); return 3; }
    }
}
