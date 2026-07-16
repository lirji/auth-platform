package com.lrj.authz.server;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import com.lrj.authz.server.AuthzDtos.CheckRequest;
import com.lrj.authz.server.AuthzDtos.ConsistencyDto;
import com.lrj.authz.server.AuthzDtos.WriteRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ZedToken 水位：at_least_as_fresh 无 token → 无水位回退 full；写后代入水位；调用方 token 恒优先；开关可关。 */
class AuthzControllerWatermarkTest {

    private static final SubjectRef SUB = SubjectRef.user("u1");
    private static final ResourceRef RES = ResourceRef.of("document", "d1");

    private static Consistency checkedWith(AuthzEngine engine) {
        ArgumentCaptor<Consistency> captor = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).check(any(), anyString(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void freshWithoutToken_noWatermark_fallsBackToFull() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        controller.check(new CheckRequest(SUB, "view", RES, new ConsistencyDto("at_least_as_fresh", null)));

        assertThat(checkedWith(engine).mode()).isEqualTo(Consistency.Mode.FULLY_CONSISTENT);
    }

    @Test
    void freshWithoutToken_afterWrite_usesWatermark() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-42"));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        controller.write(new WriteRequest(List.of()));
        controller.check(new CheckRequest(SUB, "view", RES, new ConsistencyDto("at_least_as_fresh", null)));

        Consistency c = checkedWith(engine);
        assertThat(c.mode()).isEqualTo(Consistency.Mode.AT_LEAST_AS_FRESH);
        assertThat(c.zedToken()).isEqualTo("zed-42");
    }

    @Test
    void callerToken_alwaysWinsOverWatermark() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-42"));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        controller.write(new WriteRequest(List.of()));
        controller.check(new CheckRequest(SUB, "view", RES, new ConsistencyDto("at_least_as_fresh", "caller-token")));

        assertThat(checkedWith(engine).zedToken()).isEqualTo("caller-token");
    }

    @Test
    void disabled_ignoresWatermark_fallsBackToFull() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-42"));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), false);

        controller.write(new WriteRequest(List.of()));
        controller.check(new CheckRequest(SUB, "view", RES, new ConsistencyDto("at_least_as_fresh", null)));

        assertThat(checkedWith(engine).mode()).isEqualTo(Consistency.Mode.FULLY_CONSISTENT);
    }
}
