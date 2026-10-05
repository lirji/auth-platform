package com.lrj.authz.governance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.governance.authentication.*;
import com.lrj.authz.governance.domain.IdentityModels.CurrentContext;
import com.lrj.authz.protocol.GovernanceDtos.ResolveRequest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 验证双身份顺序和不可覆盖的调用方绑定；真实数据库语义由 PG IT 证明。 */
class InternalContextServiceTest {
    private static final String SERVICE = "isolated-fixture-service-credential-32-bytes";

    @Test
    void rejectsServiceBeforeTouchingUserOrDatabase() throws Exception {
        var tokens = mock(CasdoorAccessTokenVerifier.class);
        var identity = mock(IdentityGovernance.class);
        var service = new InternalContextService(List.of(caller(tokens)), identity);
        assertThatThrownBy(
                        () ->
                                service.resolve(
                                        "invalid",
                                        "user-token",
                                        new ResolveRequest("tenant", null)))
                .isInstanceOfSatisfying(
                        GovernanceException.class,
                        ex ->
                                assertThat(ex.code())
                                        .isEqualTo(GovernanceException.Code.INVALID_CREDENTIAL));
        verifyNoInteractions(tokens, identity);
    }

    @Test
    void resolvesOnlyVerifiedUserAndFixedCallerBinding() throws Exception {
        var tokens = mock(CasdoorAccessTokenVerifier.class);
        var identity = mock(IdentityGovernance.class);
        when(tokens.verify("user-token"))
                .thenReturn(new VerifiedLogin("https://issuer.example", "subject"));
        when(identity.contextForLogin("https://issuer.example", "subject", "tenant", 2L))
                .thenReturn(new CurrentContext("principal", 3, "membership", 2, 4, "tenant"));
        var service = new InternalContextService(List.of(caller(tokens)), identity);
        var result = service.resolve(SERVICE, "user-token", new ResolveRequest("tenant", 2L));
        assertThat(result.applicationId()).isEqualTo("registered-app");
        assertThat(result.environment()).isEqualTo("test");
        assertThat(result.callerServiceId()).isEqualTo("registered-service");
        assertThat(result.principalId()).isEqualTo("principal");
        assertThat(result.membershipVersion()).isEqualTo(4);
        assertThat(result.principalVersion()).isEqualTo(3);
        assertThat(result.actorType()).isEqualTo("HUMAN");
        verify(identity).contextForLogin("https://issuer.example", "subject", "tenant", 2L);
    }

    @Test
    void duplicateCredentialsCannotSelectMultipleApplications() throws Exception {
        var tokens = mock(CasdoorAccessTokenVerifier.class);
        var caller = caller(tokens);
        var other =
                new CallerService("other", "other-app", "test", caller.credentialHash(), tokens);
        assertThatThrownBy(
                        () ->
                                new InternalContextService(
                                        List.of(caller, other), mock(IdentityGovernance.class)))
                .isInstanceOf(GovernanceException.class);
        var copy = caller.credentialHash();
        copy[0] ^= 1;
        assertThat(caller.credentialHash()).isNotEqualTo(copy);
        assertThat(caller.toString()).doesNotContain(SERVICE);
    }

    private static CallerService caller(CasdoorAccessTokenVerifier tokens) throws Exception {
        return new CallerService(
                "registered-service",
                "registered-app",
                "test",
                MessageDigest.getInstance("SHA-256")
                        .digest(SERVICE.getBytes(StandardCharsets.UTF_8)),
                tokens);
    }
}
