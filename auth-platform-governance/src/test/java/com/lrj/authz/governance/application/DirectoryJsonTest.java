package com.lrj.authz.governance.application;

import com.lrj.authz.protocol.DirectoryEvents;
import com.lrj.authz.protocol.DirectoryEvents.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** HTTP 目录事实的严格解析必须与源协议一致，不允许 JSON 强制转换隐藏正文变化。 */
class DirectoryJsonTest {
    @Test void roundTripsExplicitNullsWithoutLosingProof() {
        Event event = event();
        String json = DirectoryJson.event(event);
        assertThat(parse(json)).isEqualTo(event);
        assertThat(json).contains("\"snapshot_id\":null", "\"user_id\":null", "\"organization\":null");
    }
    @Test void rejectsScalarCoercionDuplicateKeysAndUnknownFields() {
        String json = DirectoryJson.event(event());
        for (String invalid : List.of(
                json.replace("\"partition_sequence\":1", "\"partition_sequence\":\"1\""),
                json.replace("\"partition_sequence\":1", "\"partition_sequence\":1.0"),
                json.replace("\"partition_sequence\":1", "\"partition_sequence\":null"),
                json.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
                json.substring(0, json.length() - 1) + ",\"issuer\":\"forged\"}",
                json + " {}", "null", "[]", " ".repeat(65537))) {
            assertThatThrownBy(() -> parse(invalid)).isInstanceOf(GovernanceException.class);
        }
    }
    @Test void rejectsOmittedFieldInsteadOfInventingDefaults() {
        String json = DirectoryJson.event(event());
        assertThatThrownBy(() -> parse(json.replace("\"snapshot_id\":null,", ""))).isInstanceOf(GovernanceException.class);
    }
    private static Event parse(String json) { return DirectoryJson.event(json.getBytes(StandardCharsets.UTF_8)); }
    private static Event event() {
        var payload = new Payload(new Employee("1", null, "ACTIVE", List.of(), List.of()), null, null);
        return new Event(1, "00000000-0000-4000-8000-000000000001", "oa", "isolated", "1", 1, DirectoryAggregateType.EMPLOYEE,
                "1", 1, "2026-09-28T00:00:00Z", null, payload, DirectoryEvents.payloadHash(DirectoryAggregateType.EMPLOYEE, payload));
    }
}
