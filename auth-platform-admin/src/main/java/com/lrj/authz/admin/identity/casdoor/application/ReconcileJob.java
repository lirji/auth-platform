package com.lrj.authz.admin.identity.casdoor.application;

import org.springframework.beans.factory.ObjectProvider;
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

    /** 部门树同步（仅 department-sync-enabled 时存在）；一并对账。 */
    private final ObjectProvider<DepartmentSyncService> departmentSync;

    /** 显式绑定 ReconcileJob 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public ReconcileJob(
            GroupSyncService sync, ObjectProvider<DepartmentSyncService> departmentSync) {
        this.sync = sync;
        this.departmentSync = departmentSync;
    }

    /** 按原显式开关执行身份对账，失败不能被误报为目录与图已经一致。 */
    @Scheduled(
            fixedDelayString = "${authz.casdoor.reconcile-interval-ms:300000}",
            initialDelayString = "${authz.casdoor.reconcile-interval-ms:300000}")
    public void reconcile() {
        sync.sync();
        departmentSync.ifAvailable(DepartmentSyncService::sync);
    }
}
