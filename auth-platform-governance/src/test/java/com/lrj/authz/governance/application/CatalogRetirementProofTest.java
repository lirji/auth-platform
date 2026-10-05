package com.lrj.authz.governance.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.support.RetirementProofFixture;
import com.lrj.authz.protocol.CapabilityLifecycleDtos;
import com.lrj.authz.protocol.CapabilityRetirementDtos.Report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 独立验证签名信任边界；无需数据库也能覆盖损坏、伪造及非零API映射的拒绝。 */
class CatalogRetirementProofTest {
    @TempDir Path directory;
    private static final Report BASIS =
            new Report(
                    "shop",
                    "shop.write",
                    CapabilityLifecycleDtos.State.DEPRECATED,
                    1,
                    2,
                    "a".repeat(64),
                    "b".repeat(64),
                    true,
                    List.of(),
                    "UNPROVEN",
                    "NOT_CONFIGURED",
                    null,
                    null,
                    false,
                    "c".repeat(64),
                    "2026-10-03T00:00:00Z");

    private CatalogRetirementProof.Result verify(CatalogRetirementProof reader) {
        return reader.verify(
                BASIS.applicationId(),
                BASIS.capability(),
                BASIS.manifestVersion(),
                BASIS.contentHash(),
                BASIS.presentationHash(),
                BASIS.lifecycleVersion());
    }

    @Test
    void signedCurrentCompleteZeroMappingIsProvenAndFileInvalidationIsImmediate() throws Exception {
        var fixture = new RetirementProofFixture(directory, BASIS.applicationId());
        fixture.write(fixture.payload(BASIS));
        var reader = CatalogRetirementProof.from(fixture.properties());
        assertThat(verify(reader).proven()).isTrue();
        assertThat(verify(reader).hash()).matches("[a-f0-9]{64}");
        Files.delete(fixture.file);
        assertThat(verify(reader).reason()).isEqualTo("PROOF_UNAVAILABLE");
        assertThat(verify(new CatalogRetirementProof(List.of())).reason())
                .isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void trustedSignatureCannotHideRemainingReferencesIncompleteOrMissingDeployment()
            throws Exception {
        var fixture = new RetirementProofFixture(directory, BASIS.applicationId());
        var payload = fixture.payload(BASIS);
        var target =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "target_id",
                                "api-a",
                                "commit",
                                "a".repeat(40),
                                "artifact_hash",
                                "b".repeat(64),
                                "api_mapping_hash",
                                "c".repeat(64),
                                "complete",
                                true,
                                "capability_references",
                                1));
        payload.put("deployments", List.of(target));
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("API_REFERENCES_REMAIN");
        target.put("capability_references", 0);
        target.put("complete", false);
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INCOMPLETE_MAPPING");
        target.put("complete", true);
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("TARGET_MISMATCH");
        target.put("capability_references", "0");
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INVALID_PROOF");
    }

    @Test
    void signedStaleBasisAndOverlongFutureOrExpiredTimeRemainUnproven() throws Exception {
        var fixture = new RetirementProofFixture(directory, BASIS.applicationId());
        var payload = fixture.payload(BASIS);
        payload.put("lifecycle_version", 2);
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("BASIS_MISMATCH");
        payload = fixture.payload(BASIS);
        payload.put("valid_until", Instant.now().plusSeconds(600).toString());
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("EXPIRED_OR_INVALID_TIME");
        payload = fixture.payload(BASIS);
        payload.put("checked_at", Instant.now().plusSeconds(30).toString());
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("EXPIRED_OR_INVALID_TIME");
        payload = fixture.payload(BASIS);
        payload.put("valid_until", Instant.now().minusSeconds(5).toString());
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("EXPIRED_OR_INVALID_TIME");
    }

    @Test
    void unsignedTamperingUnknownFieldsDuplicateJsonAndOversizedEnvelopeFailClosed()
            throws Exception {
        var fixture = new RetirementProofFixture(directory, BASIS.applicationId());
        fixture.write(fixture.payload(BASIS));
        String raw = Files.readString(fixture.file);
        Files.writeString(fixture.file, raw.replace("signature\":\"", "signature\":\"AAAA"));
        assertThat(verify(fixture.reader()).proven()).isFalse();
        var payload = fixture.payload(BASIS);
        payload.put("owner_confirmed", true);
        fixture.write(payload);
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INVALID_PROOF");
        Files.writeString(
                fixture.file, "{\"payload\":\"AA==\",\"payload\":\"AA==\",\"signature\":\"AA==\"}");
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INVALID_PROOF");
        Files.writeString(fixture.file, raw + " {}");
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INVALID_PROOF");
        Files.writeString(fixture.file, " ".repeat(131073));
        assertThat(verify(fixture.reader()).reason()).isEqualTo("INVALID_PROOF");
    }

    @Test
    void controlledConfigurationRejectsMissingCountDuplicateTargetsAndRelativeFile()
            throws Exception {
        var fixture = new RetirementProofFixture(directory, BASIS.applicationId());
        var p = fixture.properties();
        p.remove("catalog.retirement.count");
        var missing = p;
        assertThatThrownBy(() -> CatalogRetirementProof.from(missing))
                .isInstanceOf(IllegalArgumentException.class);
        p = fixture.properties();
        p.setProperty("catalog.retirement.1.deployment-targets", "api-a,api-a");
        var duplicate = p;
        assertThatThrownBy(() -> CatalogRetirementProof.from(duplicate))
                .isInstanceOf(IllegalArgumentException.class);
        p = fixture.properties();
        p.setProperty("catalog.retirement.1.proof-file", "proof.json");
        var relative = p;
        assertThatThrownBy(() -> CatalogRetirementProof.from(relative))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
