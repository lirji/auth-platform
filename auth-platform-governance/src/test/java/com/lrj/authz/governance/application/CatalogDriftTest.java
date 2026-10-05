package com.lrj.authz.governance.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.domain.CatalogDriftModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;

import org.junit.jupiter.api.Test;

import java.util.Properties;

/** 漂移优先级、配置隔离和未知运行不受调用方声明影响。 */
class CatalogDriftTest {
    private final Source source = new Source("a".repeat(40), "b".repeat(64));

    private Release row(Source s, String display) {
        return new Release(
                "commerce",
                2,
                "c".repeat(64),
                display,
                "owner",
                "now",
                s,
                null,
                null,
                null,
                1L,
                null,
                null,
                null);
    }

    @Test
    void classifiesFixedHashesVersionSourceAndUnknownInOrder() {
        String content = "c".repeat(64), display = "d".repeat(64);
        var published = row(source, display);
        assertThat(CatalogDrift.compare(2, content, display, source, null))
                .isEqualTo(State.NOT_PUBLISHED);
        assertThat(CatalogDrift.compare(1, content, display, source, published))
                .isEqualTo(State.SOURCE_MISMATCH);
        assertThat(CatalogDrift.compare(2, "e".repeat(64), display, source, published))
                .isEqualTo(State.SOURCE_MISMATCH);
        assertThat(CatalogDrift.compare(2, content, "e".repeat(64), null, published))
                .isEqualTo(State.DISPLAY_MISMATCH);
        assertThat(CatalogDrift.compare(2, content, display, null, published))
                .isEqualTo(State.UNKNOWN);
        assertThat(CatalogDrift.compare(2, content, display, source, row(null, display)))
                .isEqualTo(State.UNKNOWN);
        assertThat(
                        CatalogDrift.compare(
                                2,
                                content,
                                display,
                                new Source("e".repeat(40), source.artifactHash()),
                                published))
                .isEqualTo(State.SOURCE_MISMATCH);
        assertThat(CatalogDrift.compare(2, content, display, source, published))
                .isEqualTo(State.MATCHED_DECLARATION);
    }

    private Properties valid() {
        Properties p = new Properties();
        p.setProperty("catalog.deployment.count", "1");
        var fields =
                java.util.Map.of(
                        "application-id",
                        "commerce",
                        "manifest-version",
                        "2",
                        "content-hash",
                        "c".repeat(64),
                        "presentation-hash",
                        "d".repeat(64),
                        "commit",
                        source.commit(),
                        "artifact-hash",
                        source.artifactHash(),
                        "declared-at",
                        "2026-10-03T00:00:00Z",
                        "evidence-ref",
                        "isolated-build:1");
        fields.forEach((k, v) -> p.setProperty("catalog.deployment.1." + k, v));
        return p;
    }

    @Test
    void privateDeclarationConfigurationIsCompleteBoundedUniqueAndNoArbitraryUrl() {
        assertThat(CatalogDrift.from(new Properties())).isEmpty();
        assertThat(CatalogDrift.from(valid())).hasSize(1);
        for (String key :
                java.util.List.of(
                        "manifest-version",
                        "commit",
                        "artifact-hash",
                        "declared-at",
                        "evidence-ref")) {
            var missing = valid();
            missing.remove("catalog.deployment.1." + key);
            assertThatThrownBy(() -> CatalogDrift.from(missing))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var unknown = valid();
        unknown.setProperty("catalog.deployment.1.url", "http://internal");
        assertThatThrownBy(() -> CatalogDrift.from(unknown))
                .isInstanceOf(IllegalArgumentException.class);
        var uncounted = valid();
        uncounted.remove("catalog.deployment.count");
        assertThatThrownBy(() -> CatalogDrift.from(uncounted))
                .isInstanceOf(IllegalArgumentException.class);
        var overflow = valid();
        overflow.setProperty("catalog.deployment.count", "101");
        assertThatThrownBy(() -> CatalogDrift.from(overflow))
                .isInstanceOf(IllegalArgumentException.class);
        var duplicate = valid();
        duplicate.setProperty("catalog.deployment.count", "2");
        for (String k : valid().stringPropertyNames())
            if (k.startsWith("catalog.deployment.1."))
                duplicate.setProperty(k.replace(".1.", ".2."), valid().getProperty(k));
        assertThatThrownBy(() -> CatalogDrift.from(duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
