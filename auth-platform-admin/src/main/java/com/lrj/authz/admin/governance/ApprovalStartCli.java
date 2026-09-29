package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;

/** 受控配置的单条启动投递器；外部调度调用，不在授权请求线程重试OA。 */
public final class ApprovalStartCli {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ApprovalStartCli.class);
    private ApprovalStartCli() {}
    /** 仅打印有无领取，不输出申请快照和密钥。 */
    public static void main(String[] args) {
        if(args.length!=1) throw new IllegalArgumentException("APPROVAL_ARGUMENT_INVALID");
        var p=GovernanceConfigurationFile.read(args[0]);
        var partition=new Partition(p.getProperty("access.tenant"),p.getProperty("access.application"),p.getProperty("access.environment"));
        var gateway=new HttpApprovalGateway(p.getProperty("approval.oa-url"),p.getProperty("approval.outbound-key"),partition.environment());
        try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(p),false)) {
            boolean claimed=runtime.approvalStarts(gateway).step(partition);
            LOG.info(claimed?"APPROVAL_ATTEMPT_RECORDED":"APPROVAL_NO_DUE_WORK");
        }
    }
}
