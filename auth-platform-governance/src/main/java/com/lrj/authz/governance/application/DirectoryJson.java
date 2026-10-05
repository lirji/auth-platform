package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lrj.authz.protocol.DirectoryEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 目录导入独立严格 JSON 边界，不依赖宿主允许强制转换的默认配置。 */
public final class DirectoryJson {
    private static final int MAX_BYTES = 65_536;
    private static final ObjectMapper JSON =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .build();

    private DirectoryJson() {}

    /** 拒绝有歧义或过大的事件，原始异常与正文不对外暴露。 */
    public static DirectoryEvents.Event event(byte[] input) {
        if (input == null || input.length > MAX_BYTES) {
            throw invalid();
        }
        try {
            DirectoryEvents.Event event = JSON.readValue(input, DirectoryEvents.Event.class);
            if (event == null) {
                throw invalid();
            }
            return event;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** 持久化字段是经过协议构造校验的最小事实，未引入 OA 私有实体。 */
    public static String payload(DirectoryEvents.Payload payload) {
        return encode(payload);
    }

    /** 读取已校验持久投影形成真实前值，损坏时拒绝而不伪造历史。 */
    public static DirectoryEvents.Payload readPayload(String value) {
        try {
            return JSON.readValue(value, DirectoryEvents.Payload.class);
        } catch (IOException | IllegalArgumentException corrupt) {
            throw invalid();
        }
    }

    /** 源端协议事件可使用相同字段命名，原文不作为日志内容。 */
    public static String event(DirectoryEvents.Event event) {
        return encode(event);
    }

    private static String encode(Object value) {
        try {
            String json = JSON.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw invalid();
            }
            return json;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static GovernanceException invalid() {
        return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
    }
}
