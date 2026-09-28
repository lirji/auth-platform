package com.lrj.authz.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.*;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.lrj.authz.protocol.ProjectionGraph.Failure.Code.*;

/** P3专用有界HTTP适配；marker前置条件和关系变更在SpiceDB同一事务提交。 */
public final class SpiceDbProjectionGraph implements ProjectionGraph {
    private static final int MAX_RESPONSE_BYTES = 524288;
    private static final int RPC_FAILED_PRECONDITION = 9;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final URI base;
    private final String key;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore inFlight = new Semaphore(16);

    /** 只接受HTTPS或回环HTTP，不跟随重定向；总超时覆盖响应体而非只有响应头。 */
    public SpiceDbProjectionGraph(String baseUrl, String key, Duration timeout) {
        base = URI.create(baseUrl);
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || (!base.getPath().isEmpty() && !base.getPath().equals("/"))
                || !(base.getScheme().equals("https") || (base.getScheme().equals("http") && Set.of("localhost", "127.0.0.1").contains(base.getHost()))))
            throw new IllegalArgumentException("invalid graph endpoint");
        if (key == null || key.isBlank() || key.contains("\n") || key.contains("\r") || timeout == null
                || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(10)) > 0) throw new IllegalArgumentException("invalid graph configuration");
        this.key = key; this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 完全一致读取及恢复token，不接受部分流、多marker、caveat或错误对象。 */
    @Override public Optional<Marker> readMarker(String partitionId) {
        uuid(partitionId);
        byte[] response = post("/v1/relationships/read", Map.of("consistency", Map.of("fullyConsistent", true),
                "relationshipFilter", filter(partitionId, null), "optionalLimit", 2));
        List<Marker> markers = new ArrayList<>();
        try (var parser = json.getFactory().createParser(response)) {
            Iterator<JsonNode> items = json.readValues(parser, JsonNode.class);
            while (items.hasNext()) {
                JsonNode message = items.next();
                if (message.hasNonNull("error")) throw new Failure(PROTOCOL_INVALID);
                JsonNode result = message.path("result"), rel = result.path("relationship");
                if (!PARTITION_TYPE.equals(rel.path("resource").path("objectType").asText())
                        || !partitionId.equals(rel.path("resource").path("objectId").asText())
                        || !HEAD_RELATION.equals(rel.path("relation").asText())
                        || !MARKER_TYPE.equals(rel.path("subject").path("object").path("objectType").asText())
                        || !rel.path("subject").path("optionalRelation").asText("").isEmpty()
                        || rel.hasNonNull("optionalCaveat")) throw new Failure(PROTOCOL_INVALID);
                String operation = rel.path("subject").path("object").path("objectId").asText();
                uuid(operation);
                markers.add(new Marker(operation, token(result, "readAt")));
                if (markers.size() > 1) throw new Failure(MARKER_CONFLICT);
            }
        } catch (IOException failure) { throw new Failure(PROTOCOL_INVALID); }
        return markers.stream().findFirst();
    }

    /** 一次有界批次；失败后调用者必须核对权威operation，禁止给旧payload替换expected。 */
    @Override public String compareAndWrite(String partitionId, String expectedMarker, String newMarker, List<RelationshipUpdate> updates) {
        uuid(partitionId); uuid(newMarker);
        if (expectedMarker != null) uuid(expectedMarker);
        if (newMarker.equals(expectedMarker) || updates == null || updates.size() > 100) throw new Failure(PROTOCOL_INVALID);
        List<Map<String,Object>> writes = new ArrayList<>();
        for (var update : updates) {
            if (update == null || update.resource() == null || update.subject() == null || update.operation() == null
                    || !Set.of(GRANT_TYPE, GROUP_TYPE).contains(update.resource().type())) throw new Failure(PROTOCOL_INVALID);
            writes.add(update(update));
        }
        var partition = ResourceRef.of(PARTITION_TYPE, partitionId);
        if (expectedMarker != null) writes.add(update(RelationshipUpdate.delete(partition, HEAD_RELATION, SubjectRef.of(MARKER_TYPE, expectedMarker))));
        writes.add(update(RelationshipUpdate.create(partition, HEAD_RELATION, SubjectRef.of(MARKER_TYPE, newMarker))));
        String precondition = expectedMarker == null ? "OPERATION_MUST_NOT_MATCH" : "OPERATION_MUST_MATCH";
        byte[] response = post("/v1/relationships/write", Map.of("updates", writes,
                "optionalPreconditions", List.of(Map.of("operation", precondition, "filter", filter(partitionId, expectedMarker)))));
        return token(single(response), "writtenAt");
    }

    private Map<String,Object> filter(String partition, String marker) {
        Map<String,Object> filter = new LinkedHashMap<>();
        filter.put("resourceType", PARTITION_TYPE); filter.put("optionalResourceId", partition); filter.put("optionalRelation", HEAD_RELATION);
        if (marker != null) filter.put("optionalSubjectFilter", Map.of("subjectType", MARKER_TYPE, "optionalSubjectId", marker));
        return filter;
    }
    private Map<String,Object> update(RelationshipUpdate u) {
        Map<String,Object> subject = new LinkedHashMap<>();
        subject.put("object", Map.of("objectType", u.subject().type(), "objectId", u.subject().id()));
        if (u.subject().relation() != null) subject.put("optionalRelation", u.subject().relation());
        return Map.of("operation", "OPERATION_" + u.operation().name(), "relationship", Map.of(
                "resource", Map.of("objectType", u.resource().type(), "objectId", u.resource().id()), "relation", u.relation(), "subject", subject));
    }
    private JsonNode single(byte[] response) {
        try (var parser = json.getFactory().createParser(response)) {
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw new Failure(PROTOCOL_INVALID);
            return node;
        } catch (IOException failure) { throw new Failure(PROTOCOL_INVALID); }
    }
    private String token(JsonNode node, String field) {
        JsonNode value = node.path(field).path("token");
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2048) throw new Failure(PROTOCOL_INVALID);
        return value.asText();
    }
    private void uuid(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw new Failure(PROTOCOL_INVALID); }
        catch (IllegalArgumentException failure) { throw new Failure(PROTOCOL_INVALID); }
    }
    private byte[] post(String path, Object body) {
        if (!inFlight.tryAcquire()) throw new Failure(DEPENDENCY_UNAVAILABLE);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            pending = http.sendAsync(request, info -> new BoundedBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != HttpStatus.OK.value()) {
                // gRPC FailedPrecondition由HTTP网关映射400；其他4xx不能误当可重规划CAS失败。
                if (response.statusCode() == HttpStatus.BAD_REQUEST.value()
                        && single(response.body()).path("code").asInt() == RPC_FAILED_PRECONDITION) throw new Failure(PRECONDITION_FAILED);
                throw new Failure(DEPENDENCY_UNAVAILABLE);
            }
            return response.body();
        } catch (Failure failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new Failure(DEPENDENCY_UNAVAILABLE); }
        catch (IOException | ExecutionException | TimeoutException failure) { throw new Failure(DEPENDENCY_UNAVAILABLE); }
        finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            inFlight.release();
        }
    }

    /** 同时限制整个响应字节和并发请求；错误响应也不能无限缓冲。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > MAX_RESPONSE_BYTES) { subscription.cancel(); delegate.onError(new Failure(PROTOCOL_INVALID)); return; }
            }
            delegate.onNext(items);
        }
        public void onError(Throwable failure) { delegate.onError(failure); }
        public void onComplete() { delegate.onComplete(); }
    }
}
