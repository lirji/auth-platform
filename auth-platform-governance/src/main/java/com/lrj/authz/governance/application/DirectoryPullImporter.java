package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.DirectoryEvents;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 有界 HTTP 拉取 → 本地逐事件事务 → 提交后确认；不在数据库事务中等待网络，不内嵌无限重试。 */
public final class DirectoryPullImporter implements AutoCloseable {
    private static final int MAX_PAGE_BYTES = 100 * 65_536 + 1024;
    private static final long MAX_RUN_NANOS = Duration.ofMinutes(2).toNanos();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> STATUS_FIELDS = Set.of("source", "environment", "source_tenant_ref", "last_sequence", "acked_sequence", "acked_fingerprint", "backlog");
    private final DirectoryGovernance directory;
    private final DirectoryPullConfiguration config;
    private final HttpClient http;

    /** 禁止重定向把服务凭据转给其他地址；连接与完整响应均有截止时间。 */
    public DirectoryPullImporter(DirectoryGovernance directory, DirectoryPullConfiguration config) {
        this.directory = Objects.requireNonNull(directory); this.config = Objects.requireNonNull(config);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMillis())).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 结果表示本次提交和连续水位；未读取最新来源状态时不宣称积压已全部清零。 */
    public record Result(int processed, long lastSequence) {}

    /** 稳定运维状态不等于任何应用访问许可；冲突优先于表面上的相等水位。 */
    public enum SyncState {
        PENDING("PENDING"), ACKNOWLEDGED("ACKNOWLEDGED"), CONFLICT("CONFLICT");
        private final String code;
        SyncState(String code) { this.code = code; }
        public String code() { return code; }
    }
    /** 冲突时无需依赖在线 OA；来源/确认未知使用 null，不能以 0 冒充读取结果。 */
    public record Status(SyncState state, long importedSequence, Long sourceSequence, Long confirmedSequence) {}

    /** 固定来源状态查询不清除冲突、改写游标或发送确认，可在隔离后用于诊断。 */
    public Status inspect() {
        var local = directory.inspect(config.authority());
        if (local.quarantined()) { return new Status(SyncState.CONFLICT, local.lastSequence(), null, null); }
        JsonNode source = status();
        long last = number(source, "last_sequence"), confirmed = number(source, "acked_sequence");
        SyncState state = confirmed > local.lastSequence() || last < local.lastSequence() ? SyncState.CONFLICT
                : confirmed == last && local.lastSequence() == last ? SyncState.ACKNOWLEDGED : SyncState.PENDING;
        return new Status(state, local.lastSequence(), last, confirmed);
    }

    /** 初次登记是显式管理操作；先核对 HTTP 来源，既有企业必须由上游预先建立。 */
    public void register() {
        status();
        directory.register(config.authority(), config.operatorRef());
    }

    /** 重跑先补确认已有检查点，覆盖提交成功但上一次确认响应丢失的窗口。 */
    public Result pull() {
        long started = System.nanoTime();
        var checkpoint = directory.checkpoint(config.authority());
        JsonNode source = status();
        if (number(source, "acked_sequence") > checkpoint.lastSequence() || number(source, "last_sequence") < checkpoint.lastSequence()) { throw failure(BINDING_CONFLICT); }
        if (checkpoint.lastSequence() > 0) { acknowledge(checkpoint); }
        int processed = 0;
        while (processed < config.maxEvents()) {
            withinDeadline(started);
            long after = checkpoint.lastSequence(); int limit = Math.min(100, config.maxEvents() - processed);
            JsonNode page = request("GET", "/events?after_sequence=" + after + "&limit=" + limit, null, MAX_PAGE_BYTES);
            if (!fields(page).equals(Set.of("events")) || !page.get("events").isArray() || page.get("events").size() > limit) { throw failure(INVALID_ARGUMENT); }
            if (page.get("events").isEmpty()) {
                if (after < number(source, "last_sequence")) { throw failure(DEPENDENCY_UNAVAILABLE); }
                break;
            }
            long expected = after;
            // 先验证整页结构与顺序，再开始本地事务；来源事件本身仍由共享协议严校验。
            List<DirectoryEvents.Event> events = new ArrayList<>();
            for (JsonNode value : page.get("events")) {
                DirectoryEvents.Event event = DirectoryJson.event(value.toString().getBytes(StandardCharsets.UTF_8));
                if (expected == Long.MAX_VALUE || event.partitionSequence() != ++expected) { throw failure(INVALID_ARGUMENT); }
                events.add(event);
            }
            for (var event : events) {
                withinDeadline(started);
                checkpoint = directory.accept(config.authority(), event);
                processed++;
            }
            if (checkpoint.lastSequence() < expected) { throw failure(BINDING_CONFLICT); }
            acknowledge(checkpoint);
        }
        return new Result(processed, checkpoint.lastSequence());
    }

    private JsonNode status() { return validateStatus(request("GET", "/status", null, 8192)); }
    private JsonNode validateStatus(JsonNode status) {
        if (!fields(status).equals(STATUS_FIELDS) || !config.authority().source().equals(text(status, "source"))
                || !config.authority().environment().equals(text(status, "environment"))
                || !config.authority().sourceTenantRef().equals(text(status, "source_tenant_ref"))) { throw failure(BINDING_CONFLICT); }
        long last = number(status, "last_sequence"), acked = number(status, "acked_sequence");
        if (acked > last || number(status, "backlog") != last - acked || (acked == 0 && !status.get("acked_fingerprint").isNull())
                || (acked > 0 && !text(status, "acked_fingerprint").matches("[a-f0-9]{64}"))) { throw failure(INVALID_ARGUMENT); }
        return status;
    }
    private void acknowledge(DirectoryGovernance.Receipt receipt) {
        if (receipt.lastSequence() < 1 || receipt.lastFingerprint() == null || !receipt.lastFingerprint().matches("[a-f0-9]{64}")) { throw failure(INVALID_ARGUMENT); }
        String body = "{\"sequence\":" + receipt.lastSequence() + ",\"fingerprint\":\"" + receipt.lastFingerprint() + "\"}";
        JsonNode response = validateStatus(request("POST", "/ack", body, 8192));
        // 同来源并发拉取器可确认更高水位；等水位必须精确匹配摘要。
        long confirmed = number(response, "acked_sequence");
        if (confirmed < receipt.lastSequence() || (confirmed == receipt.lastSequence() && !receipt.lastFingerprint().equals(text(response, "acked_fingerprint")))) { throw failure(BINDING_CONFLICT); }
    }

    private JsonNode request(String method, String suffix, String body, int maxBytes) {
        var builder = HttpRequest.newBuilder(URI.create(config.endpoint().toString() + suffix))
                .timeout(Duration.ofMillis(config.timeoutMillis())).header("Authorization", "Bearer " + config.credential())
                .header("Accept", "application/json");
        if (body == null) { builder.GET(); }
        else { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)); }
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(builder.build(), info -> new BoundedBody(maxBytes));
        try {
            var response = future.get(config.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 409) { throw failure(BINDING_CONFLICT); }
            if (response.statusCode() != 200) { throw failure(DEPENDENCY_UNAVAILABLE); }
            JsonNode parsed = JSON.readTree(response.body());
            if (parsed == null) { throw failure(INVALID_ARGUMENT); }
            return parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); future.cancel(true); throw failure(DEPENDENCY_UNAVAILABLE);
        } catch (ExecutionException | TimeoutException unavailable) {
            future.cancel(true); throw failure(DEPENDENCY_UNAVAILABLE);
        } catch (java.io.IOException invalid) { throw failure(INVALID_ARGUMENT); }
    }
    private static Set<String> fields(JsonNode value) {
        if (!value.isObject()) { throw failure(INVALID_ARGUMENT); }
        Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add); return fields;
    }
    private static long number(JsonNode value, String field) {
        JsonNode number = value.get(field);
        if (number == null || !number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < 0) { throw failure(INVALID_ARGUMENT); }
        return number.longValue();
    }
    private static String text(JsonNode value, String field) {
        JsonNode text = value.get(field);
        if (text == null || !text.isTextual()) { throw failure(INVALID_ARGUMENT); }
        return text.textValue();
    }
    private static void withinDeadline(long started) { if (System.nanoTime() - started > MAX_RUN_NANOS) { throw failure(DEPENDENCY_UNAVAILABLE); } }
    private static GovernanceException failure(GovernanceException.Code code) { return new GovernanceException(code); }
    /** 主动取消并关闭客户端资源，不留下常驻拉取线程。 */
    @Override public void close() { http.shutdownNow(); http.close(); }

    /** 完整正文有大小上限，不能靠 Content-Length 或无限缓存后才检查来限制对端响应。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maxBytes;
        private long received;
        private Flow.Subscription subscription;
        private boolean failed;
        BoundedBody(int maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) { return; }
            for (var buffer : buffers) { received += buffer.remaining(); }
            if (received > maxBytes) { failed = true; subscription.cancel(); delegate.onError(new java.io.IOException("目录响应超过上限")); return; }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) { failed = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!failed) { delegate.onComplete(); } }
    }
}
