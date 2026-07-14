package com.lrj.authz.admin.casdoor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时全量对账, 兜住 webhook 丢失。authz.casdoor.reconcile-enabled=true 且组同步已启用时生效。 */
@Component
@ConditionalOnBean(GroupSyncService.class)
@ConditionalOnProperty(prefix = "authz.casdoor", name = "reconcile-enabled", havingValue = "true")
public class ReconcileJob {

    private final GroupSyncService sync;

    public ReconcileJob(GroupSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(fixedDelayString = "${authz.casdoor.reconcile-interval-ms:300000}",
            initialDelayString = "${authz.casdoor.reconcile-interval-ms:300000}")
    public void reconcile() {
        sync.sync();
    }
}
