package com.lrj.authz.governance.web;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.application.GovernanceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** 协议边界拒绝身份注入与歧义 JSON，不依赖宿主宽松 Jackson 默认值。 */
class GovernanceWebTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "{\"invitation_id\":\"x\",\"token\":1}",
                "{\"invitation_id\":\"x\",\"token\":\"a\",\"subject\":\"victim\"}",
                "{\"invitation_id\":\"x\",\"token\":\"a\",\"token\":\"b\"}",
                "{\"invitation_id\":\"x\",\"token\":\"a\"} {}"
            })
    void rejectsAmbiguousInvitationProof(String value) {
        assertThatThrownBy(
                        () ->
                                GovernanceWeb.readInvitation(
                                        new ByteArrayInputStream(
                                                value.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void invitationIsBoundedAndStrict() {
        var request =
                GovernanceWeb.readInvitation(
                        new ByteArrayInputStream(
                                "{\"invitation_id\":\"x\",\"token\":\"proof\"}"
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(request.invitationId()).isEqualTo("x");
        assertThat(request.token()).isEqualTo("proof");
        assertThatThrownBy(
                        () ->
                                GovernanceWeb.readInvitation(
                                        new ByteArrayInputStream(new byte[4097])))
                .isInstanceOf(GovernanceException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[]",
                "null",
                "{\"tenant_id\":1}",
                "{\"tenant_id\":\"a\",\"principal_id\":\"victim\"}",
                "{\"tenant_id\":\"a\",\"application_id\":\"admin\"}",
                "{\"tenant_id\":\"a\",\"tenant_id\":\"b\"}",
                "{\"tenant_id\":\"a\"} {}",
                "{\"tenant_id\":\"a\",\"expected_membership_generation\":1.0}",
                "{\"tenant_id\":\"a\",\"expected_membership_generation\":\"1\"}",
                "{\"tenant_id\":\"a\",\"expected_membership_generation\":0}",
                "{\"tenant_id\":\"a\",\"expected_membership_generation\":9223372036854775808}"
            })
    void rejectsAmbiguousOrInjectedContext(String value) {
        assertThatThrownBy(() -> parse(value))
                .isInstanceOfSatisfying(
                        GovernanceException.class,
                        ex ->
                                assertThat(ex.code())
                                        .isEqualTo(GovernanceException.Code.INVALID_ARGUMENT));
    }

    @Test
    void acceptsOnlyTenantAndOptionalIntegerGeneration() {
        assertThat(
                        parse("{\"tenant_id\":\"a\",\"expected_membership_generation\":2}")
                                .expectedMembershipGeneration())
                .isEqualTo(2);
        assertThat(parse("{\"tenant_id\":\"a\"}").expectedMembershipGeneration()).isNull();
    }

    @Test
    void boundsBytesAndRejectsRepeatedCredentials() {
        assertThatThrownBy(() -> parse(" ".repeat(4097))).isInstanceOf(GovernanceException.class);
        assertThatThrownBy(
                        () ->
                                GovernanceWeb.singleHeader(
                                        Collections.enumeration(List.of("a", "b"))))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> GovernanceWeb.singleHeader(Collections.emptyEnumeration()))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void sanitizesErrorsAndGeneratesServerTrace() {
        var result = GovernanceWeb.error(new RuntimeException("secret-token-and-dsn"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().toString()).doesNotContain("secret-token", "dsn");
        assertThat(result.getBody().path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(result.getBody().path("trace_id").asText())
                .isEqualTo(result.getHeaders().getFirst("X-Trace-Id"));
    }

    private static com.lrj.authz.protocol.GovernanceDtos.ResolveRequest parse(String json) {
        return GovernanceWeb.readResolve(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
