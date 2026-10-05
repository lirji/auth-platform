package com.lrj.authz.server.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.governance.authorization.application.BusinessNavigation;
import com.lrj.authz.governance.context.application.CallerService;
import com.lrj.authz.governance.context.application.InternalContextService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

/** 未批准调用方及伪造主体在进入导航用例前被拒绝。 */
class GovernanceNavigationControllerTest {
    @Test
    void callerAllowlistAndStrictBodyCannotBeBypassed() {
        var contexts = mock(InternalContextService.class);
        var navigation = mock(BusinessNavigation.class);
        var caller = mock(CallerService.class);
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(caller.callerServiceId()).thenReturn("unregistered");
        var controller =
                new GovernanceNavigationController(
                        contexts,
                        navigation,
                        new GovernanceNavigationConfiguration.NavigationSettings(
                                Set.of("commerce-nav")));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        request.addHeader("X-User-Access-Token", "business-token");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.current(request)).hasMessage("ACCESS_DENIED");
        when(caller.callerServiceId()).thenReturn("commerce-nav");
        String valid =
                "{\"tenant_id\":\""
                        + UUID.randomUUID()
                        + "\",\"request_id\":\""
                        + UUID.randomUUID()
                        + "\",\"expected_membership_generation\":1}";
        for (String invalid :
                List.of(
                        valid.replace("}", ",\"principal_id\":\"forged\"}"),
                        valid.replace("}", ",\"environment\":\"prod\"}"),
                        valid.replace(":1}", ":\"1\"}"),
                        valid.replace(":1}", ":0}"))) {
            request.setContent(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> controller.current(request)).hasMessage("INVALID_ARGUMENT");
        }
        verifyNoInteractions(navigation);
        verify(contexts, never()).resolve(anyString(), anyString(), any());
    }

    @Test
    void duplicateServiceHeaderIsNotAccepted() {
        var contexts = mock(InternalContextService.class);
        var navigation = mock(BusinessNavigation.class);
        var controller =
                new GovernanceNavigationController(
                        contexts,
                        navigation,
                        new GovernanceNavigationConfiguration.NavigationSettings(
                                Set.of("commerce-nav")));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + "s".repeat(48));
        request.addHeader("Authorization", "Bearer " + "t".repeat(48));
        assertThatThrownBy(() -> controller.current(request)).hasMessage("INVALID_CREDENTIAL");
        verifyNoInteractions(contexts, navigation);
    }
}
