package com.lrj.authz.server.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.protocol.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

/** 后台端点不接受客户端身份，也不把普通scope服务自动升级为执行调用方。 */
class GovernanceExecutionControllerTest {
    @Test
    void forbiddenCallerAndInjectedActorStopBeforePersistence() {
        var contexts = mock(InternalContextService.class);
        var executions = mock(ExecutionAuthorization.class);
        var caller = mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(caller.callerServiceId()).thenReturn("other");
        when(caller.applicationId()).thenReturn("commerce");
        var controller =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"), Set.of("store")));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.check(request)).hasMessage("ACCESS_DENIED");
        when(caller.callerServiceId()).thenReturn("commerce-p6");
        request.setContent(
                "{\"principal_id\":\"injected\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.check(request)).hasMessage("INVALID_ARGUMENT");
        verifyNoInteractions(executions);
    }

    @Test
    void collectionScopeRequiresRegisteredCallerAndExplicitResourceOwner() throws Exception {
        var contexts = mock(InternalContextService.class);
        var executions = mock(ExecutionAuthorization.class);
        var caller = mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(caller.callerServiceId()).thenReturn("commerce-p6");
        when(caller.applicationId()).thenReturn("commerce");
        var settings =
                new GovernanceExecutionConfiguration.ExecutionSettings(
                        Set.of("commerce-p6"), Set.of("store"));
        var controller = new GovernanceExecutionController(contexts, executions, settings);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        var check =
                new CentralAccessDtos.Check(
                        UUID.randomUUID().toString(),
                        1L,
                        UUID.randomUUID().toString(),
                        "commerce.merchant.read",
                        "merchant");
        var input = new ExecutionAccessDtos.ScopeCheck(UUID.randomUUID().toString(), check);
        request.setContent(
                com.lrj.authz.governance.web.GovernanceWeb.body(input)
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.scope(request)).hasMessage("ACCESS_DENIED");
        verifyNoInteractions(executions);
        var enabled =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"), Set.of("store", "merchant")));
        request.setContent(
                com.lrj.authz.governance.web.GovernanceWeb.body(input)
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enabled.scope(request);
        verify(executions).scope(eq(caller), eq(input));
        when(caller.callerServiceId()).thenReturn("other");
        assertThatThrownBy(() -> enabled.scope(request)).hasMessage("ACCESS_DENIED");
    }

    /** 新政策类型必须单独登记Owner，会员类型登记不能授予政策权限。 */
    @Test
    void policyCollectionRequiresItsOwnExplicitOwner() throws Exception {
        var contexts = mock(InternalContextService.class);
        var executions = mock(ExecutionAuthorization.class);
        var caller = mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(caller.callerServiceId()).thenReturn("commerce-p6");
        when(caller.applicationId()).thenReturn("commerce");
        var check =
                new CentralAccessDtos.Check(
                        UUID.randomUUID().toString(),
                        1L,
                        UUID.randomUUID().toString(),
                        "commerce.growth.policy.publish",
                        ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE);
        var input = new ExecutionAccessDtos.ScopeCheck(UUID.randomUUID().toString(), check);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        byte[] body =
                com.lrj.authz.governance.web.GovernanceWeb.body(input)
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.setContent(body);
        var restricted =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"),
                                Set.of(ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE)));
        assertThatThrownBy(() -> restricted.scope(request)).hasMessage("ACCESS_DENIED");
        verifyNoInteractions(executions);
        var enabled =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"),
                                Set.of(ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE)));
        request.setContent(body);
        enabled.scope(request);
        verify(executions).scope(eq(caller), eq(input));
    }

    @Test
    void resourceExecutionRequiresExplicitMemberOwnerBeforeEvaluatingReference() throws Exception {
        var contexts = mock(InternalContextService.class);
        var executions = mock(ExecutionAuthorization.class);
        var caller = mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(caller.callerServiceId()).thenReturn("commerce-p6");
        when(caller.applicationId()).thenReturn("commerce");
        var check =
                new CentralAccessDtos.Check(
                        UUID.randomUUID().toString(),
                        1L,
                        UUID.randomUUID().toString(),
                        "commerce.member.read",
                        ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE);
        var facts =
                new ScopeDtos.Facts(
                        check.tenantId(),
                        check.resourceType(),
                        "member-1",
                        3,
                        null,
                        null,
                        List.of(),
                        null,
                        null);
        var input =
                new ExecutionAccessDtos.Check(
                        UUID.randomUUID().toString(),
                        new ScopeAccessDtos.ResourceCheck(check, facts));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        request.setContent(
                com.lrj.authz.governance.web.GovernanceWeb.body(input)
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var restricted =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"), Set.of("store")));
        assertThatThrownBy(() -> restricted.check(request)).hasMessage("ACCESS_DENIED");
        verifyNoInteractions(executions);
        var enabled =
                new GovernanceExecutionController(
                        contexts,
                        executions,
                        new GovernanceExecutionConfiguration.ExecutionSettings(
                                Set.of("commerce-p6"),
                                Set.of("store", ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE)));
        request.setContent(
                com.lrj.authz.governance.web.GovernanceWeb.body(input)
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enabled.check(request);
        verify(executions).check(eq(caller), eq(input));
    }
}
