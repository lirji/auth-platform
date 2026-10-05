package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.web.AccessWeb;

import java.io.PrintWriter;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** 离线受控导入只读取私有配置和有界映射文件，不接受公开HTTP任意主体输入。 */
public final class MigrationImportCli {
    private MigrationImportCli() {}

    /** 配置必须指定操作者已登记登录及固定commerce分区，失败不输出载荷或凭据。 */
    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) System.exit(code);
    }

    /** 返回每条持久回执摘要；中途中断重跑同一批即可恢复，非零不表示已提交记录被撤销。 */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        try {
            if (args.length != 2)
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            var config = GovernanceConfigurationFile.read(args[0]);
            var path = Path.of(args[1]);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(path)
                            .equals(PosixFilePermissions.fromString("rw-------")))
                throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            MigrationImport.Batch batch;
            try (var input = Files.newInputStream(path)) {
                batch = AccessWeb.read(input, MigrationImport.Batch.class);
            }
            if (!"commerce".equals(batch.partition().applicationId())
                    || !batch.partition().tenantId().equals(config.getProperty("migration.tenant"))
                    || !batch.partition()
                            .environment()
                            .equals(config.getProperty("migration.environment")))
                throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
            try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(config), false)) {
                var result =
                        runtime.migrationImport()
                                .apply(
                                        new VerifiedLogin(
                                                config.getProperty("migration.issuer"),
                                                config.getProperty("migration.subject")),
                                        batch);
                for (var row : result)
                    output.printf(
                            "%s %s %d %s%n",
                            row.unitId(), row.sourceId(), row.sequence(), row.disposition());
            }
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
