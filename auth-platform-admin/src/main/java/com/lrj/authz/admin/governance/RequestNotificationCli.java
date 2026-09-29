package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.GovernanceConfigurationFile;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;

/** 明确配置的站内通知批次；不会自动开启无限循环或绕过Inbox直接授予。 */
public final class RequestNotificationCli {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RequestNotificationCli.class);
    private RequestNotificationCli() {}
    /** 单轮最多20条；邮件和短信不在本首版交付范围。 */
    public static void main(String[] args) {
        if(args.length!=1) throw new IllegalArgumentException("APPROVAL_ARGUMENT_INVALID");
        var p=GovernanceConfigurationFile.read(args[0]);
        var partition=new Partition(p.getProperty("access.tenant"),p.getProperty("access.application"),p.getProperty("access.environment"));
        try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(p),false)) {
            int count=0;while(count<20 && runtime.requestNotifications().step(partition))count++;
            LOG.info("REQUEST_NOTIFICATIONS_PROCESSED count={}",count);
        }
    }
}
