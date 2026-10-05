package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.GovernanceConfigurationFile;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;

/** 明确配置的审批消费批次；不会自动开启无限循环或绕过Inbox直接授予。 */
public final class ApprovalDecisionCli {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ApprovalDecisionCli.class);

    private ApprovalDecisionCli() {}

    /** 单轮有界20条，失败事务保留事件以待恢复。 */
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("APPROVAL_ARGUMENT_INVALID");
        var p = GovernanceConfigurationFile.read(args[0]);
        var partition =
                new Partition(
                        p.getProperty("access.tenant"),
                        p.getProperty("access.application"),
                        p.getProperty("access.environment"));
        try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(p), false)) {
            LOG.info(
                    "APPROVAL_DECISIONS_CONSUMED count={}",
                    runtime.approvalDecisions().step(partition));
        }
    }
}
