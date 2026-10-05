package com.lrj.authz.server.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.FenceModels.Stamp;
import com.lrj.authz.governance.domain.ScopeModels.Evaluation;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ScopeAccessDtos.*;
import com.lrj.authz.protocol.ScopeDtos.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.*;

/** HTTP边界单测只证明Owner和事实关联；真实双身份与图交互由P3跨进程验收证明。 */
class GovernanceScopeControllerTest {
    private final ObjectMapper json =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final InternalContextService contexts = mock(InternalContextService.class);
    private final ReliableAuthorization authorization = mock(ReliableAuthorization.class);
    private final String tenant = id();
    private final GovernanceDtos.AccessContext context =
            new GovernanceDtos.AccessContext(
                    id(), id(), 1, 1, 1, tenant, "commerce", "test", "consumer", "HUMAN", id());
    private final CentralAccessDtos.Check check =
            new CentralAccessDtos.Check(tenant, 1L, id(), "commerce.store.read", "store");
    private GovernanceScopeController controller;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void setup() {
        var caller = mock(CallerService.class);
        when(caller.callerServiceId()).thenReturn("consumer");
        when(contexts.authenticateCaller(anyString())).thenReturn(caller);
        when(contexts.resolve(anyString(), anyString(), any())).thenReturn(context);
        var stamp =
                new Stamp(
                        context.principalId(),
                        context.membershipId(),
                        1,
                        1,
                        1,
                        1,
                        1,
                        tenant,
                        "commerce",
                        "test",
                        id(),
                        1,
                        "private-policy-token",
                        id(),
                        1,
                        "private-directory-token");
        when(authorization.evaluate(any(), anyString(), anyString()))
                .thenReturn(
                        new Evaluation(
                                id(),
                                stamp,
                                List.of(
                                        new Alternative(
                                                id(),
                                                1,
                                                List.of(
                                                        new Clause(
                                                                Kind.SPECIFIED_STORES,
                                                                List.of("S001"),
                                                                false)))),
                                Instant.now().plusSeconds(25)));
        controller =
                new GovernanceScopeController(
                        contexts,
                        authorization,
                        new GovernanceScopeConfiguration.ScopeSettings(
                                null, Set.of("consumer"), Map.of("commerce", Set.of("store"))));
    }

    private MockHttpServletRequest request(Object body) throws Exception {
        var r = new MockHttpServletRequest();
        r.addHeader("Authorization", "Bearer " + "s".repeat(48));
        r.addHeader("X-User-Access-Token", "user-token");
        r.setContent(json.writeValueAsBytes(body));
        return r;
    }

    @Test
    void scopePlanBindsCurrentContextAndKeepsOpaqueTokensPrivate() throws Exception {
        var result = controller.plan(request(check));
        assertThat(result.path("decision").asText()).isEqualTo("ALLOW");
        assertThat(result.path("context").path("principal_id").asText())
                .isEqualTo(context.principalId());
        assertThat(result.toString())
                .doesNotContain("private-policy-token", "private-directory-token");
    }

    @Test
    void factsCannotChangeTenantOrUseAnotherStoreAsTheSameResource() throws Exception {
        var valid =
                new ResourceCheck(
                        check,
                        new Facts(tenant, "store", "S001", 7, null, null, List.of(), "S001", null));
        var allowed = controller.resource(request(valid));
        assertThat(allowed.path("resource_version").asLong()).isEqualTo(7);
        assertThat(allowed.path("decision").asText()).isEqualTo("ALLOW");
        var outside =
                new ResourceCheck(
                        check,
                        new Facts(tenant, "store", "S002", 7, null, null, List.of(), "S002", null));
        assertThat(controller.resource(request(outside)).path("decision").asText())
                .isEqualTo("DENY");
        for (var bad :
                List.of(
                        new Facts(id(), "store", "S001", 7, null, null, List.of(), "S001", null),
                        new Facts(
                                tenant, "store", "S002", 7, null, null, List.of(), "S001", null))) {
            assertThatThrownBy(() -> controller.resource(request(new ResourceCheck(check, bad))))
                    .hasMessage("INVALID_ARGUMENT");
        }
    }

    @Test
    void unregisteredOwnerAndInjectedIdentityNeverReachTheEvaluator() throws Exception {
        var other =
                new GovernanceScopeController(
                        contexts,
                        authorization,
                        new GovernanceScopeConfiguration.ScopeSettings(
                                null, Set.of("consumer"), Map.of("oa", Set.of("store"))));
        assertThatThrownBy(() -> other.plan(request(check))).hasMessage("ACCESS_DENIED");
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(check);
        node.put("principal_id", id());
        assertThatThrownBy(() -> controller.plan(request(node))).hasMessage("INVALID_ARGUMENT");
        verifyNoInteractions(authorization);
    }

    @Test
    void tenantOnlyFactsNeedExplicitOwnerAndRejectFakeStore() throws Exception {
        var member =
                new CentralAccessDtos.Check(
                        tenant, 1L, id(), "commerce.member.read", "commerce_member");
        var facts =
                new Facts(tenant, "commerce_member", "M1", 1, null, null, List.of(), null, null);
        assertThatThrownBy(() -> controller.resource(request(new ResourceCheck(member, facts))))
                .hasMessage("ACCESS_DENIED");
        verifyNoInteractions(authorization);
        var registered =
                new GovernanceScopeController(
                        contexts,
                        authorization,
                        new GovernanceScopeConfiguration.ScopeSettings(
                                null,
                                Set.of("consumer"),
                                Map.of("commerce", Set.of("commerce_member"))));
        when(authorization.evaluate(any(), anyString(), anyString()))
                .thenAnswer(
                        invocation ->
                                new Evaluation(
                                        id(),
                                        null,
                                        List.of(
                                                new Alternative(
                                                        id(),
                                                        1,
                                                        List.of(
                                                                new Clause(
                                                                        Kind.TENANT_ALL,
                                                                        List.of(),
                                                                        false)))),
                                        Instant.now().plusSeconds(25)));
        assertThat(
                        registered
                                .resource(request(new ResourceCheck(member, facts)))
                                .path("decision")
                                .asText())
                .isEqualTo("ALLOW");
        for (var invalid :
                List.of(
                        new Facts(
                                tenant,
                                "commerce_member",
                                "M1",
                                1,
                                null,
                                null,
                                List.of(),
                                "S1",
                                null),
                        new Facts(
                                id(),
                                "commerce_member",
                                "M1",
                                1,
                                null,
                                null,
                                List.of(),
                                null,
                                null))) {
            assertThatThrownBy(
                            () -> registered.resource(request(new ResourceCheck(member, invalid))))
                    .hasMessage("INVALID_ARGUMENT");
        }
    }
}
