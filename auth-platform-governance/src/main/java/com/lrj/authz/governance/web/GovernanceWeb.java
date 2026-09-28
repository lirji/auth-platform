package com.lrj.authz.governance.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.application.GovernanceException;
import com.lrj.authz.protocol.GovernanceDtos;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.*;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 新治理路由独立 JSON/错误边界；不修改宿主的全局 Jackson 或旧响应协议。 */
public final class GovernanceWeb {
    private static final int MAX_REQUEST_BYTES = 4_096;
    private static final Set<String> REQUEST_FIELDS = Set.of("tenant_id", "expected_membership_generation");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private GovernanceWeb() {}

    /** Advice 只作用于显式标记的新治理 Controller，不能截获旧接口错误。 */
    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
    public @interface Endpoint {}

    /** 用字节上限读取，拒绝未知/重复字段、尾随 JSON、浮点/文本代际，避免伪造主体/app。 */
    public static GovernanceDtos.ResolveRequest readResolve(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) { throw new GovernanceException(INVALID_ARGUMENT); }
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || !node.path("tenant_id").isTextual()) { throw new GovernanceException(INVALID_ARGUMENT); }
            var fields = node.fieldNames();
            while (fields.hasNext()) { if (!REQUEST_FIELDS.contains(fields.next())) { throw new GovernanceException(INVALID_ARGUMENT); } }
            JsonNode generation = node.get("expected_membership_generation");
            if (generation != null && !generation.isNull() && (!generation.isIntegralNumber() || !generation.canConvertToLong() || generation.longValue() < 1)) {
                throw new GovernanceException(INVALID_ARGUMENT);
            }
            return new GovernanceDtos.ResolveRequest(node.path("tenant_id").textValue(), generation == null || generation.isNull() ? null : generation.longValue());
        } catch (IOException failure) { throw new GovernanceException(INVALID_ARGUMENT); }
    }

    /** 邀请接受只接收指定邀请与原文令牌，主体永远来自已验证的登录身份。 */
    public static GovernanceDtos.AcceptInvitationRequest readInvitation(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) { throw new GovernanceException(INVALID_ARGUMENT); }
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.path("invitation_id").isTextual() || !node.path("token").isTextual()) { throw new GovernanceException(INVALID_ARGUMENT); }
            return new GovernanceDtos.AcceptInvitationRequest(node.path("invitation_id").textValue(), node.path("token").textValue());
        } catch (IOException failure) { throw new GovernanceException(INVALID_ARGUMENT); }
    }

    /** Header 不能重复，防止代理/宿主对同名凭据采用不同值。 */
    public static String singleHeader(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) { throw new GovernanceException(INVALID_CREDENTIAL); }
        String result = headers.nextElement();
        if (headers.hasMoreElements() || result == null || result.isBlank()) { throw new GovernanceException(INVALID_CREDENTIAL); }
        return result;
    }

    /** 服务 Bearer 与用户 Header 走各自入口，不能把一种身份当另一种身份。 */
    public static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) { throw new GovernanceException(INVALID_CREDENTIAL); }
        return header.substring("Bearer ".length());
    }

    /** 局部 snake_case 序列化确保 protocol 仍为零运行依赖。 */
    public static JsonNode body(Object value) { return JSON.valueToTree(value); }

    /** Filter 与 Controller 共用相同脱敏错误协议，不泄露底层异常消息。 */
    public static ResponseEntity<JsonNode> error(Exception failure) {
        GovernanceException.Code code = failure instanceof GovernanceException governance ? governance.code()
                : failure instanceof IllegalArgumentException || failure instanceof IOException ? INVALID_ARGUMENT : DEPENDENCY_UNAVAILABLE;
        int status = switch (code) {
            case INVALID_ARGUMENT -> 400;
            case INVALID_CREDENTIAL -> 401;
            case IDENTITY_NOT_BOUND, MEMBERSHIP_UNAVAILABLE, GENERATION_MISMATCH -> 403;
            case BINDING_CONFLICT, COMMAND_CONFLICT, VERSION_CONFLICT -> 409;
            case DEPENDENCY_UNAVAILABLE -> 503;
        };
        String trace = UUID.randomUUID().toString();
        LogFactory.getLog(Errors.class).warn("治理请求失败 code=" + code.value() + " trace_id=" + trace);
        return ResponseEntity.status(status).header("X-Trace-Id", trace).body(body(new GovernanceDtos.ErrorResponse(code.value(), trace)));
    }

    /** 新 API 错误统一脱敏；日志只含稳定错误码与服务端关联 ID。 */
    @RestControllerAdvice(annotations = Endpoint.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Errors {
        /** 只处理标记 Controller 的异常；错误响应/日志不包含原始异常消息。 */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<JsonNode> handle(Exception failure) {
            return error(failure);
        }
    }
}
