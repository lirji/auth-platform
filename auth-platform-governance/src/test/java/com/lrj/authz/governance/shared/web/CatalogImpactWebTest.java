package com.lrj.authz.governance.shared.web;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.shared.application.GovernanceException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 新目录诊断仍使用严格JSON，不能借更大的清单预算注入主体或枚举序号。 */
class CatalogImpactWebTest {
    private static final String MANIFEST =
            "{\"schema_version\":\"1\",\"application\":\"app\",\"manifest_version\":1,\"capabilities\":[{\"code\":\"app.read\",\"resource_type\":\"store\",\"risk_level\":\"NORMAL\"}],\"menus\":[]}";
    private static final String VALID =
            "{\"tenant_id\":\"12345678-1234-1234-1234-123456789012\",\"application_id\":\"app\",\"environment\":\"test\",\"manifest\":"
                    + MANIFEST
                    + "}";

    private com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Input read(String value) {
        return CatalogImpactWeb.read(
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsOnlyTypedCandidateAndPartition() {
        var value = read(VALID);
        assertThat(value.manifest().application()).isEqualTo("app");
        assertThat(value.cursor()).isNull();
    }

    @Test
    void rejectsActorInjectionDuplicateFieldsAndUnknownManifestFields() {
        for (String value :
                new String[] {
                    "null",
                    VALID + "{}",
                    VALID.replace(
                            "\"environment\":\"test\"",
                            "\"environment\":\"test\",\"subject\":\"victim\""),
                    VALID.replace(
                            "\"environment\":\"test\"",
                            "\"environment\":\"test\",\"environment\":\"prod\""),
                    VALID.replace(
                            "\"manifest_version\":1",
                            "\"manifest_version\":1,\"operator\":\"victim\"")
                }) {
            assertThatThrownBy(() -> read(value))
                    .isInstanceOf(GovernanceException.class)
                    .hasMessage("INVALID_ARGUMENT");
        }
    }

    @Test
    void rejectsScalarCoercionUnknownRiskAndOrdinalRisk() {
        for (String value :
                new String[] {
                    VALID.replace("\"schema_version\":\"1\"", "\"schema_version\":1"),
                    VALID.replace("\"manifest_version\":1", "\"manifest_version\":1.1"),
                    VALID.replace("\"NORMAL\"", "\"UNKNOWN\""),
                    VALID.replace("\"NORMAL\"", "0")
                }) {
            assertThatThrownBy(() -> read(value)).as(value).hasMessage("INVALID_ARGUMENT");
        }
    }

    @Test
    void boundsEnvelopeBytesBeforeParsing() {
        assertThatThrownBy(() -> CatalogImpactWeb.read(new ByteArrayInputStream(new byte[141313])))
                .hasMessage("INVALID_ARGUMENT");
    }
}
