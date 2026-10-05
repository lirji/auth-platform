package com.lrj.authz.protocol;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.protocol.DirectoryEvents.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 固定摘要向量由独立 Python struct 编码生成，避免源/目标共享错误算法仍自证一致。 */
class DirectoryEventsTest {
    private static final String VECTOR =
            "da5f260d37ee77ded69bf9405566e58678feb42960f467da493f84d0426f82e2";

    @Test
    void verifiesIndependentPayloadAndEnvelopeVectors() {
        Payload payload = payload("ACTIVE");
        assertThat(DirectoryEvents.payloadHash(DirectoryAggregateType.EMPLOYEE, payload))
                .isEqualTo(VECTOR);
        Event event = event(5, payload, VECTOR);
        assertThat(DirectoryEvents.eventFingerprint(event))
                .isEqualTo("b81231387fe9d9f9f6c9961f5e1062fa7775bdbe75d5ff5ba75f7ad974ffcd13");
        assertThat(DirectoryEvents.eventFingerprint(event(6, payload, VECTOR)))
                .isNotEqualTo(DirectoryEvents.eventFingerprint(event));
        assertThat(event(6, payload, VECTOR).payloadHash()).isEqualTo(event.payloadHash());
    }

    @Test
    void rejectsTamperingAndUnknownSourceStates() {
        assertThatThrownBy(() -> event(5, payload("LEFT"), VECTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payload("DELETED")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Organization("2", null, "FROZEN").status()).isEqualTo("FROZEN");
        assertThatThrownBy(() -> DirectoryAggregateType.fromCode("USER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Organization("2", "2", "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalOrderAndDefensiveCopiesPreserveDigest() {
        Assignment small = new Assignment("2", "2", "PRIMARY", false, "2026-01-01", null);
        Assignment large = new Assignment("10", "3", "CONCURRENT", true, "2026-01-01", null);
        var source = new ArrayList<>(List.of(large, small));
        var first = new Employee("1", null, "PROBATION", source, List.of());
        source.clear();
        assertThat(first.assignments()).containsExactly(small, large);
        assertThatThrownBy(() -> first.assignments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var second = new Employee("1", null, "PROBATION", List.of(small, large), List.of());
        assertThat(
                        DirectoryEvents.payloadHash(
                                DirectoryAggregateType.EMPLOYEE, new Payload(first, null, null)))
                .isEqualTo(
                        DirectoryEvents.payloadHash(
                                DirectoryAggregateType.EMPLOYEE, new Payload(second, null, null)));
    }

    @Test
    void boundsRelationsAndRejectsDuplicateFacts() {
        Assignment a = new Assignment("2", "2", "PRIMARY", false, "2026-01-01", null);
        assertThatThrownBy(() -> new Employee("1", null, "ACTIVE", List.of(a, a), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Employee(
                                        "1",
                                        null,
                                        "ACTIVE",
                                        java.util.Collections.nCopies(101, a),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Employee("1", "\uD800", "ACTIVE", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Organization("01", null, "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotBoundaryAndPayloadTypeCannotBeConfused() {
        var snapshot = new Snapshot(1, 3, 1, "0".repeat(64));
        var payload = new Payload(null, null, snapshot);
        String id = "00000000-0000-4000-8000-000000000002";
        new Event(
                1,
                id,
                "oa",
                "isolated",
                "1",
                1,
                DirectoryAggregateType.SNAPSHOT_BEGIN,
                id,
                1,
                "2026-09-28T00:00:00Z",
                id,
                payload,
                DirectoryEvents.payloadHash(DirectoryAggregateType.SNAPSHOT_BEGIN, payload));
        assertThatThrownBy(() -> new Snapshot(1, 3, 2, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Event(
                                        1,
                                        id,
                                        "oa",
                                        "isolated",
                                        "1",
                                        2,
                                        DirectoryAggregateType.SNAPSHOT_BEGIN,
                                        id,
                                        1,
                                        "2026-09-28T00:00:00Z",
                                        id,
                                        payload,
                                        DirectoryEvents.payloadHash(
                                                DirectoryAggregateType.SNAPSHOT_BEGIN, payload)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> DirectoryEvents.payloadHash(DirectoryAggregateType.EMPLOYEE, payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Payload payload(String status) {
        return new Payload(
                new Employee(
                        "1",
                        "exact-user",
                        status,
                        List.of(new Assignment("9", "2", "PRIMARY", false, "2026-01-01", null)),
                        List.of()),
                null,
                null);
    }

    private static Event event(long sequence, Payload payload, String hash) {
        return new Event(
                1,
                "00000000-0000-4000-8000-000000000001",
                "oa",
                "isolated",
                "1",
                sequence,
                DirectoryAggregateType.EMPLOYEE,
                "1",
                3,
                "2026-09-28T00:00:00Z",
                null,
                payload,
                hash);
    }
}
