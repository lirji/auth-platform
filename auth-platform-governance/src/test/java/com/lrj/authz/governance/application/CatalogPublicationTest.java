package com.lrj.authz.governance.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 来源信封不能注入主体、枚举序号或自动授予，旧清单另存。 */
class CatalogPublicationTest {
    private static final String MANIFEST="{\"schema_version\":\"1\",\"application\":\"app\",\"manifest_version\":1,\"capabilities\":[{\"code\":\"app.read\",\"resource_type\":\"store\",\"risk_level\":\"NORMAL\"}],\"menus\":[]}";
    private static final String SOURCE="{\"commit\":\""+"a".repeat(40)+"\",\"artifact_hash\":\""+"b".repeat(64)+"\"}";
    private com.lrj.authz.governance.domain.CatalogReleaseModels.Candidate read(String s) { return CatalogPublication.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8))); }
    @Test void acceptsExplicitFixedSourceOrUnknown() {
        assertThat(read("{\"manifest\":"+MANIFEST+",\"source\":"+SOURCE+"}").source().commit()).hasSize(40);
        assertThat(read("{\"manifest\":"+MANIFEST+"}").source()).isNull();
    }
    @Test void rejectsUnknownDuplicateCoercionOrdinalAndMalformedSource() {
        String valid="{\"manifest\":"+MANIFEST+",\"source\":"+SOURCE+"}";
        for(String value:new String[]{valid+"{}",valid.replace("{\"manifest\":","{\"subject\":\"victim\",\"manifest\":"),valid.replace("{\"manifest\":","{\"decision\":0,\"manifest\":"),
                valid.replace("\"schema_version\":\"1\"","\"schema_version\":1"), valid.replace("\"NORMAL\"","0"),valid.replace("\"source\":","\"source\":null,\"source\":"),valid.replace("b".repeat(64),"short"),valid.replace(SOURCE,"{}")})
            assertThatThrownBy(()->read(value)).hasMessage("INVALID_ARGUMENT");
    }
    @Test void boundsBytesAndRequiresReasonWithDecision() {
        assertThatThrownBy(()->CatalogPublication.read(new ByteArrayInputStream(new byte[141313]))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(()->read("{\"manifest\":"+MANIFEST+",\"decision\":\"KEEP_CURRENT_GRANTS\"}")).hasMessage("INVALID_ARGUMENT");
    }
}
