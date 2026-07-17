package com.lrj.authz.admin.casdoor;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileJobTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DepartmentSyncService> providerOf(DepartmentSyncService service) {
        ObjectProvider<DepartmentSyncService> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<DepartmentSyncService> consumer = invocation.getArgument(0);
            if (service != null) {
                consumer.accept(service);
            }
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    @Test
    void reconcilesGroupThenOptionalDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);

        new ReconcileJob(group, providerOf(department)).reconcile();

        InOrder order = inOrder(group, department);
        order.verify(group).sync();
        order.verify(department).sync();
    }

    @Test
    void noDepartmentBeanStillRunsGroupOnce() {
        GroupSyncService group = mock(GroupSyncService.class);

        new ReconcileJob(group, providerOf(null)).reconcile();

        verify(group).sync();
    }

    @Test
    void groupFailurePropagatesAndStopsBeforeDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        when(group.sync()).thenThrow(new IllegalStateException("group failed"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ReconcileJob(group, providerOf(department)).reconcile());

        assertThat(ex).hasMessageContaining("group failed");
        verify(department, never()).sync();
        // TODO(issue-R01): department failure after group success leaves partial state；需指标/重试语义。
    }
}
