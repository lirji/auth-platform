package com.lrj.authz.governance.shared.web;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.CapabilityLifecycleDtos.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** HTTP字段严格解析与弃用稳定错误，身份不能从body替换。 */
class CapabilityLifecycleWebTest {
    private String body =
            "{\"application_id\":\"commerce\",\"capability\":\"commerce.read\",\"state\":\"DEPRECATED\",\"expected_version\":0,\"reason\":\"停止新增\",\"command_id\":\"00000000-0000-0000-0000-000000000001\"}";

    private Change read(String json) {
        return AccessWeb.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), Change.class);
    }

    @Test
    void strictInputRejectsUnknownStateActorDuplicateTrailingOversizeAndCoercion() {
        assertThat(read(body).state()).isEqualTo(State.DEPRECATED);
        for (String value :
                new String[] {
                    body.replace("DEPRECATED", "RETIRED"),
                    body.replace("DEPRECATED", "deprecated"),
                    body.replace("0,", "0.5,"),
                    body.replace("0,", "\"0\","),
                    body.replace("{", "{\"actor\":\"spoof\","),
                    body.replace("{", "{\"state\":\"ACTIVE\","),
                    body + " {}",
                    " ".repeat(16385) + body
                }) assertThatThrownBy(() -> read(value)).hasMessage("INVALID_ARGUMENT");
        assertThat(read(body.replace("\"expected_version\":0,", "")).expectedVersion()).isNull();
    }

    @Test
    void deprecatedConflictHasStableSanitizedResponse() {
        var response =
                GovernanceWeb.error(
                        new GovernanceException(GovernanceException.Code.CAPABILITY_DEPRECATED));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().path("code").asText()).isEqualTo("CAPABILITY_DEPRECATED");
        assertThat(response.getBody().size()).isEqualTo(2);
    }
}
