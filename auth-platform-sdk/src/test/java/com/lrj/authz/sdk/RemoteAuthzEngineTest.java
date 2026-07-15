package com.lrj.authz.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.ResourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteAuthzEngine#parseCheckBulk} 严格校验单测：合法响应正确映射；错位/漏项/多项/缺字段
 * 一律抛协议异常（上游 enforce 据此 fail-closed）。纯解析，不起 HTTP。
 */
class RemoteAuthzEngineTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final ResourceRef D1 = ResourceRef.of("document", "acme_d1");
    private static final ResourceRef D2 = ResourceRef.of("document", "acme_d2");

    @Test
    void mapsEchoedResultsByResource() {
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":true},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        Map<ResourceRef, Boolean> out = RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2));
        assertEquals(2, out.size());
        assertTrue(out.get(D1));
        assertFalse(out.get(D2));
    }

    @Test
    void mapsCorrectlyEvenWhenServerReordersResults() {
        // 响应顺序与请求相反：仍按 resource 对齐，不按下标。
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":true},
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":false}
                ]}""");
        Map<ResourceRef, Boolean> out = RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2));
        assertFalse(out.get(D1));
        assertTrue(out.get(D2));
    }

    @Test
    void throwsOnTruncatedResults() {
        JsonNode root = json("""
                {"results":[{"resource":{"type":"document","id":"acme_d1"},"allowed":true}]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnEmptyBody() {
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(json("{}"), List.of(D1)));
    }

    @Test
    void throwsOnUnrequestedResource() {
        // 基数相等，但含未请求的资源 → 错位/污染，拒绝。
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":true},
                  {"resource":{"type":"document","id":"acme_dX"},"allowed":true}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnDuplicateResource() {
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":true},
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnMissingResourceField() {
        JsonNode root = json("""
                {"results":[
                  {"allowed":true},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    // --- F3：allowed 字段必须存在且为 JSON boolean，否则抛协议异常（旧实现会静默当 false=deny）。 ---
    // check(single) 与 parseCheckBulk(bulk) 共用同一 requireAllowed 严格校验，故经 bulk 路径即覆盖两者。

    @Test
    void throwsOnMissingAllowedField() {
        // 基数、资源都对，但某项缺 allowed → 不可信，抛异常（而非静默 deny）。
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"}},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnNullAllowed() {
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":null},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnStringAllowed() {
        // JSON 字符串 "true" 不是 boolean；旧 asBoolean() 会解析为 true/false，掩盖类型错误。
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":"true"},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }

    @Test
    void throwsOnNumberAllowed() {
        JsonNode root = json("""
                {"results":[
                  {"resource":{"type":"document","id":"acme_d1"},"allowed":1},
                  {"resource":{"type":"document","id":"acme_d2"},"allowed":false}
                ]}""");
        assertThrows(IllegalStateException.class, () -> RemoteAuthzEngine.parseCheckBulk(root, List.of(D1, D2)));
    }
}
