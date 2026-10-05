package com.lrj.authz.governance.shared.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Input;
import com.lrj.authz.governance.shared.application.GovernanceException;

import java.io.InputStream;

/** 清单加分区的严格边界有独立字节预算，不能复用16KiB授权命令边界截断正常目录。 */
public final class CatalogImpactWeb {
    private static final int MAX_REQUEST_BYTES = 141312, MAX_MANIFEST_BYTES = 131072;
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    static {
        // Jackson的Textual转换独立于标量开关，数字／布尔不能冒充字符串协议值。
        var text = JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape :
                java.util.List.of(
                        com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                        com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                        com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
    }

    private CatalogImpactWeb() {}

    /** 拒绝主体注入、未知键和宽松数值；结构／版本语义继续由原清单契约负责。 */
    public static Input read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw invalid();
            Input value = JSON.readValue(bytes, Input.class);
            if (value == null
                    || value.manifest() == null
                    || JSON.writeValueAsBytes(value.manifest()).length > MAX_MANIFEST_BYTES)
                throw invalid();
            return new Input(
                    value.tenantId(),
                    value.applicationId(),
                    value.environment(),
                    CatalogManifest.normalize(value.manifest()),
                    value.cursor());
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
