package com.lrj.authz.server.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.protocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 后台端点不接受客户端身份，也不把普通scope服务自动升级为执行调用方。 */
class GovernanceExecutionControllerTest {
    @Test void forbiddenCallerAndInjectedActorStopBeforePersistence() {
        var contexts=mock(InternalContextService.class);var executions=mock(ExecutionAuthorization.class);var caller=mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);when(caller.callerServiceId()).thenReturn("other");when(caller.applicationId()).thenReturn("commerce");
        var controller=new GovernanceExecutionController(contexts,executions,new GovernanceExecutionConfiguration.ExecutionSettings(Set.of("commerce-p6")));
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+"s".repeat(48));request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->controller.check(request)).hasMessage("ACCESS_DENIED");
        when(caller.callerServiceId()).thenReturn("commerce-p6");
        request.setContent("{\"principal_id\":\"injected\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->controller.check(request)).hasMessage("INVALID_ARGUMENT");verifyNoInteractions(executions);
    }
}
