package com.lrj.authz.governance.web;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 真实边界解析不能让操作者字段、重复JSON或强制转换绕过固定命令。 */
class RoleMigrationTaskWebTest {
    private <T> T parse(String value, Class<T> type) {
        return AccessWeb.read(
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), type);
    }

    @Test
    void strictCreatePreservesNullableScopeHash() {
        var input =
                parse(
                        "{\"tenant_id\":\"t\",\"application_id\":\"a\",\"environment\":\"test\",\"command_id\":\"c\",\"old_role_id\":\"o\",\"new_role_id\":\"n\",\"grants\":[{\"grant_id\":\"g\",\"expected_version\":1,\"valid_to\":\"2026-10-03T00:00:00Z\",\"scope_hash\":null}]}",
                        Create.class);
        assertThat(input.grants().getFirst().expectedVersion()).isEqualTo(1);
        assertThat(input.grants().getFirst().scopeHash()).isNull();
    }

    @Test
    void forgedActorExtraFieldsDuplicateAndTrailingJsonReject() {
        for (String value :
                new String[] {
                    "{\"actor\":\"owner\"}", "{\"command_id\":\"a\",\"command_id\":\"b\"}", "{} {}"
                })
            assertThatThrownBy(() -> parse(value, Create.class)).hasMessage("INVALID_ARGUMENT");
    }

    @Test
    void stepVersionCannotCoerceStringOrFraction() {
        for (String value :
                new String[] {"{\"expected_version\":\"1\"}", "{\"expected_version\":1.2}"})
            assertThatThrownBy(() -> parse(value, Advance.class)).hasMessage("INVALID_ARGUMENT");
    }

    @Test
    void largeOrNullBodyRejectBeforeCommands() {
        assertThatThrownBy(() -> parse(" ".repeat(16385), Cancel.class))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> parse("null", Cancel.class)).hasMessage("INVALID_ARGUMENT");
    }
}
