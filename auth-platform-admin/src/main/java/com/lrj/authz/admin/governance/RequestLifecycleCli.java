package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.GovernanceConfigurationFile;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;

/** 明确配置的申请到期/离职回收批次；不会自动开启无限循环或绕过Inbox直接授予。 */
public final class RequestLifecycleCli {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RequestLifecycleCli.class);

    private RequestLifecycleCli() {}

    /** 单轮有界100条，实时授权有效期不依赖本任务运行。 */
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("APPROVAL_ARGUMENT_INVALID");
        var p = GovernanceConfigurationFile.read(args[0]);
        var partition =
                new Partition(
                        p.getProperty("access.tenant"),
                        p.getProperty("access.application"),
                        p.getProperty("access.environment"));
        try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(p), false)) {
            LOG.info("REQUESTS_SETTLED count={}", runtime.requests().settle(partition));
        }
    }
}
