package com.lrj.authz.governance.identity.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.shared.application.GovernanceException;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class BootstrapCommandTest {
    private final String tenant = UUID.randomUUID().toString();
    private final String principal = UUID.randomUUID().toString();
    private final String member = UUID.randomUUID().toString();
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void differentInputsCannotReuseDigest() {
        BootstrapCommand command = input("https://id.example", "alice", from, null);
        assertThat(command.payloadHash()).isEqualTo(command.payloadHash()).hasSize(64);
        assertThat(input("https://id.example", "other", from, null).payloadHash())
                .isNotEqualTo(command.payloadHash());
        assertThat(input("https://id.example", "alice", from, from.plusSeconds(60)).payloadHash())
                .isNotEqualTo(command.payloadHash());
    }

    @Test
    void rejectsInvalidIssuerAndTimeWithoutNormalization() {
        assertThatThrownBy(() -> input("http://id.example", "alice", from, null))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> input("https://id.example", " alice", from, null))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> input("https://id.example", "alice", from, from))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> input("https://id.example", "alice", from.plusNanos(1), null))
                .isInstanceOf(GovernanceException.class);
    }

    private BootstrapCommand input(String issuer, String subject, Instant from, Instant to) {
        return new BootstrapCommand(
                UUID.randomUUID().toString(),
                "operator",
                tenant,
                "tenant",
                principal,
                issuer,
                subject,
                member,
                from,
                to,
                "fixture",
                "legacy-tenant",
                "legacy-person");
    }
}
