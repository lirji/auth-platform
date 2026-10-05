package com.lrj.authz.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 验证实际传输字节、目标用途和重试发送时间边界，不能靠双方重新序列化侥幸一致。 */
class ApprovalSignatureTest {
    private final String key = "k".repeat(43);
    private final Instant now = Instant.parse("2026-09-29T01:00:00Z");

    @Test
    void rawBodyAndRouteAndEnvironmentAreBound() {
        byte[] body = "{\"request\":1}".getBytes(StandardCharsets.UTF_8);
        String signature = ApprovalSignature.sign(key, "oa-platform", "test", "/event", body, now);
        assertNotNull(
                ApprovalSignature.verify(
                        key, "oa-platform", "test", "/event", body, signature, now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key, "oa-platform", "prod", "/event", body, signature, now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key, "oa-platform", "test", "/start", body, signature, now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key, "auth-platform", "test", "/event", body, signature, now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key,
                                "oa-platform",
                                "test",
                                "/event",
                                "{ \"request\":1}".getBytes(StandardCharsets.UTF_8),
                                signature,
                                now));
    }

    @Test
    void retryHasFreshNonceAndBoundedSendWindow() {
        byte[] body = "old business event".getBytes(StandardCharsets.UTF_8);
        String first = ApprovalSignature.sign(key, "oa-platform", "test", "/event", body, now);
        String retry =
                ApprovalSignature.sign(
                        key, "oa-platform", "test", "/event", body, now.plusSeconds(500));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key,
                                "oa-platform",
                                "test",
                                "/event",
                                body,
                                first,
                                now.plusSeconds(121)));
        assertNotNull(
                ApprovalSignature.verify(
                        key, "oa-platform", "test", "/event", body, retry, now.plusSeconds(500)));
        assertNotEquals(first, retry);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApprovalSignature.verify(
                                key,
                                "oa-platform",
                                "test",
                                "/event",
                                body,
                                first,
                                now.minusSeconds(121)));
    }
}
