package com.lrj.authz.admin.casdoor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CasdoorSyncControllerTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void missingFeatureBeansReturnConflict() {
        CasdoorSyncController controller = new CasdoorSyncController(provider(null), provider(null), "hook");

        assertThat(controller.sync().getStatusCode().value()).isEqualTo(409);
        assertThat(controller.syncDepartments().getStatusCode().value()).isEqualTo(409);
        assertThat(controller.webhook("hook", "{}").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void manualSyncReturnsServiceSummary() {
        GroupSyncService group = mock(GroupSyncService.class);
        GroupSyncService.SyncSummary summary = new GroupSyncService.SyncSummary(2, 1, 0);
        when(group.sync()).thenReturn(summary);
        CasdoorSyncController controller = new CasdoorSyncController(provider(group), provider(null), "hook");

        var response = controller.sync();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(summary);
        verify(group).sync();
    }

    @Test
    void syncDepartmentsReturnsServiceSummary() {
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        DepartmentSyncService.SyncSummary summary = new DepartmentSyncService.SyncSummary(3, 2, 1);
        when(department.sync()).thenReturn(summary);
        CasdoorSyncController controller = new CasdoorSyncController(provider(null), provider(department), "hook");

        var response = controller.syncDepartments();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(summary);
        verify(department).sync();
    }

    @Test
    void wrongWebhookSecretReturns401WithoutSync() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        CasdoorSyncController controller = new CasdoorSyncController(
                provider(group), provider(department), "correct-hook");

        var response = controller.webhook("wrong", "ignored");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(group, never()).sync();
        verify(department, never()).sync();
    }

    @Test
    void validWebhookRunsGroupAndOptionalDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        when(group.sync()).thenReturn(new GroupSyncService.SyncSummary(1, 2, 0));
        when(department.sync()).thenReturn(new DepartmentSyncService.SyncSummary(1, 3, 0));
        CasdoorSyncController controller = new CasdoorSyncController(
                provider(group), provider(department), "correct-hook");

        var response = controller.webhook("correct-hook", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("synced", true)
                .containsEntry("summary", new GroupSyncService.SyncSummary(1, 2, 0))
                .containsEntry("departments", new DepartmentSyncService.SyncSummary(1, 3, 0));
        verify(group).sync();
        verify(department).sync();
    }

    @Test
    void blankConfiguredSecretCurrentlyAllowsMissingHeader() {
        GroupSyncService group = mock(GroupSyncService.class);
        when(group.sync()).thenReturn(new GroupSyncService.SyncSummary(0, 0, 0));
        CasdoorSyncController controller = new CasdoorSyncController(provider(group), provider(null), "");

        // 锁定"当前行为"：webhook-secret 未配置（空串）时不校验来源，无 header 也执行同步并返回 200。
        // TODO(issue-webhook-open): 空 secret ⇒ webhook 端点对外裸奔（任何人可触发全量同步）。application.yml
        //   注明"仅本地"，但仓库未见生产 profile 的 fail-fast；若生产必须配 secret 应加启动期强制校验，
        //   届时本用例应改为断言 401 或拒绝启动，而非放行 200。
        assertThat(controller.webhook(null, null).getStatusCode().value()).isEqualTo(200);
        verify(group).sync();
    }
}
