# TEST_PLAN — auth-platform 核心链路测试蓝图

## 1. 目标与范围

本计划审计 Maven 五模块的授权主链：protocol 值对象 → core SpiceDB HTTP adapter → server REST facade/水位/安全 filter → SDK HTTP adapter/AOP → admin 管理、审计、Casdoor 对账。完整类/方法、现有测试和命令见 [01-scope.md](./01-scope.md)，逐格覆盖见 [02-coverage-matrix.md](./02-coverage-matrix.md)，疑似问题见 [03-suspected-issues.md](./03-suspected-issues.md)。

优先顺序为：P0 `SpiceDbAuthzEngine` 协议可信度与 AOP fail-closed；P1 server/admin facade、水位、安全、Casdoor；P2 protocol 值对象和 SDK 其余映射。测试默认均为纯单测；HTTP adapter 用进程内 JDK `HttpServer`，不连接 SpiceDB/Casdoor、不起 Spring context；controller 直接 `new` 并手动 Mockito mock。

## 2. 六视角综合策略

### test-strategist：分层与验收标准

- 值对象层：合法 factory/value semantics 必须稳定；非法值当前无约束，先报 issue，不锁定错误。
- adapter 边界层：校验 path/header/body/enum/token 与 2xx/4xx/畸形/流式边界。判权数据缺失必须可区别于合法 deny。
- orchestration 单元层：捕获传给 `AuthzEngine` 的真实对象，验证一致性、顺序、只调用一次和异常短路；不能只断返回 DTO。
- 租户与安全层：跨 org 同名隔离、完整路径跨 org 输入、Bearer 语法、filter URI；不虚构仓库中不存在的 `TenantContext`。
- 状态/并发层：水位 blank/visibility、audit capacity、同步幂等/熔断/分阶段失败。不能用 sleep 猜时序。
- 回归层：既有 38 tests 必须继续全绿；新增关键测试在修复 ISSUE-C01/C02/C03/C04/S01/A01 后闭合。

验收标准：正常响应的字段和交互 100% 有精确断言；check/checkBulk 的缺字段与 per-item error 全抛；所有 deny 场景证明目标业务方法未执行；full/fresh/token 跨 core→server→SDK 不丢；Casdoor 多 org 不串 id；测试无外网/真实时钟顺序断言/共享端口/静态状态；疑似 bug 不被断言成正确行为。

### coverage-analyst

详细矩阵见 `02-coverage-matrix.md`。本轮首要补齐：core 9 操作全空白、AOP 全空白、server 5 个非水位 facade 分支、admin controller/Casdoor/Reconcile/InMemory 全空白。已有 bulk parser、水位主路径、security 主路径、差量同步、编码和 JDBC audit 不重复堆同义测试，只补边界。

### interaction-logic-reviewer

P0 摘要：lookup 缺 permissionship 可能放权（C01）；流式 error 返回部分结果（C02）；空 2xx 伪装正常（C03）；写 token 缺失仍成功（C04）；server bulk 漏项伪装 deny（S01）；AOP metadata/null id 失败不明确（A01）。P1 摘要：并发水位可能回退（S02）；Casdoor 缺 data 可撤权、删除实体遗留 tuple、跨 org 引用（CAS01–03）；两阶段 reconcile 半同步（R01）；写成功审计失败（ADM01）；record 无输入约束（ADM02）；内存 ring 并发过裁（AUD01）。复现与建议逐条见 `03-suspected-issues.md`。

### edge-case-hunter

- null：bulk resources、consistency、AOP 参数、resolver、filter、token、Casdoor data、JWT/name、audit actor。
- 空/blank：permission/type/id/relation/token/body/schema；blank 与 null 在 JSON 中不同。
- 越界/组合：bulk 0/1/N、少/多/重复 pair，limit≤0，capacity≤0，delete threshold `-1/0/equal/exceed`。
- 注入/租户：organization query 元字符、`other/group`、role user 的 org 段、object id 分隔符。
- 异常：HTTP 4xx/5xx、非法 JSON、合法 JSON 缺字段、流中 error、mock collaborator 在各阶段抛错。
- 并发/泄漏：水位只断原子可见；audit 用 barrier/future，不用 sleep；所有 HttpServer 在 `@AfterEach` stop、executor shutdown。仓库无 ThreadLocal/TenantContext。

### flaky-risk-reviewer

- loopback server 绑定端口 `0`，绝不写死端口；每测试新实例并停止。
- 不断言 audit 时间先后或精确 Instant，只验证可解析、deque 位置与内容。
- 不用 `UUID` 以外共享 H2 名；本计划没有新增 DB 测试。若补 DB，按铁律用唯一 `jdbc:h2:mem:<name>;MODE=MySQL;DB_CLOSE_DELAY=-1`，让 `Jdbc*Store` 自建表。
- 不依赖 test method 顺序、全局系统属性、外部环境或随机调度；并发 Futures 必须 `get`，线程池在 finally shutdown。
- Mockito 全为方法内手动 mock，无 extension/annotation；无 Spring context。
- Casdoor response 路由按 path+query 确定匹配，测试结束清理 server。

### test-judge：审判准则

每个 happy-path 至少同时断返回和协作者实参；每个 fail-path 至少断异常类型和副作用为零。只断“非 null”“调用过”不足以验收。以下代码草案使用仓库实际签名；标为 TODO 的 issue 测试不激活，避免固化当前错误。唯一构建前置是 protocol 模块缺 test dependency（issue-BLD01）。

## 3. 完整测试代码草案

### 3.1 SpiceDbAuthzEngineTest

放置路径：`auth-platform-core/src/test/java/com/lrj/authz/core/SpiceDbAuthzEngineTest.java`

锁定行为：真实 HTTP 序列化/认证、九操作映射、三种一致性、NDJSON、严格 check/bulk 和错误传播。断言请求 JSON 而非仅断返回，能发现字段名/token/userset 丢失。

```java
package com.lrj.authz.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpiceDbAuthzEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private HttpServer server;
    private SpiceDbAuthzEngine engine;

    private record Captured(String path, String authorization, String contentType, String body) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        engine = new SpiceDbAuthzEngine(
                "http://127.0.0.1:" + server.getAddress().getPort(), "spice-secret",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        captured.set(new Captured(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(requestBytes, StandardCharsets.UTF_8)));
        requestCount.incrementAndGet();
        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respond(String body) {
        status.set(200);
        response.set(body);
    }

    private JsonNode requestJson() throws Exception {
        return mapper.readTree(captured.get().body());
    }

    @Test
    void checkSerializesUsersetAndFreshness() throws Exception {
        respond("{\"permissionship\":\"PERMISSIONSHIP_HAS_PERMISSION\"}");

        boolean allowed = engine.check(
                SubjectRef.ofRelation("group", "acme_eng", "member"), "view",
                ResourceRef.of("document", "d1"), Consistency.atLeastAsFresh("zed-7"));

        assertThat(allowed).isTrue();
        assertThat(captured.get().path()).isEqualTo("/v1/permissions/check");
        assertThat(captured.get().authorization()).isEqualTo("Bearer spice-secret");
        assertThat(captured.get().contentType()).startsWith("application/json");
        JsonNode body = requestJson();
        assertThat(body.at("/consistency/atLeastAsFresh/token").asText()).isEqualTo("zed-7");
        assertThat(body.at("/resource/objectType").asText()).isEqualTo("document");
        assertThat(body.at("/resource/objectId").asText()).isEqualTo("d1");
        assertThat(body.path("permission").asText()).isEqualTo("view");
        assertThat(body.at("/subject/object/objectType").asText()).isEqualTo("group");
        assertThat(body.at("/subject/optionalRelation").asText()).isEqualTo("member");
    }

    @Test
    void checkReturnsFalseForNoPermissionAndRejectsMissingPermissionship() {
        respond("{\"permissionship\":\"PERMISSIONSHIP_NO_PERMISSION\"}");
        assertThat(engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null)).isFalse();

        respond("{}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
        assertThat(ex).hasMessageContaining("permissionship");
    }

    @Test
    void serializesOtherConsistencyModes() throws Exception {
        respond("{\"permissionship\":\"PERMISSIONSHIP_NO_PERMISSION\"}");
        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), null);
        assertThat(requestJson().at("/consistency/minimizeLatency").asBoolean()).isTrue();

        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), Consistency.fullyConsistent());
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();

        engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), Consistency.minimizeLatency());
        assertThat(requestJson().at("/consistency/minimizeLatency").asBoolean()).isTrue();
    }

    @Test
    void emptyBulkDoesNotCallServer() {
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", null, null)).isEmpty();
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", List.of(), null)).isEmpty();
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void bulkMapsPairsInRequestOrder() throws Exception {
        respond("""
                {"pairs":[
                  {"item":{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}},
                  {"item":{"permissionship":"PERMISSIONSHIP_NO_PERMISSION"}}
                ]}
                """);
        ResourceRef d1 = ResourceRef.of("document", "d1");
        ResourceRef d2 = ResourceRef.of("document", "d2");

        var result = engine.checkBulk(SubjectRef.user("u1"), "view", List.of(d1, d2), Consistency.fullyConsistent());

        assertThat(result).containsEntry(d1, true).containsEntry(d2, false).hasSize(2);
        assertThat(result.keySet()).containsExactly(d1, d2);
        assertThat(requestJson().path("items").size()).isEqualTo(2);
        assertThat(requestJson().at("/items/1/resource/objectId").asText()).isEqualTo("d2");
    }

    @Test
    void bulkRejectsWrongCardinalityAndPerItemError() {
        ResourceRef d1 = ResourceRef.of("document", "d1");
        respond("{\"pairs\":[]}");
        assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null));

        respond("{\"pairs\":[{\"error\":{\"code\":\"INTERNAL\"}}]}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.checkBulk(SubjectRef.user("u"), "view", List.of(d1), null));
        assertThat(ex).hasMessageContaining("INTERNAL");
    }

    @Test
    void lookupResourcesParsesConcatenatedJson() {
        respond("""
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION","resourceObjectId":"d1"}}
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_CONDITIONAL_PERMISSION","resourceObjectId":"d2"}}
                {"result":{"permissionship":"LOOKUP_PERMISSIONSHIP_HAS_PERMISSION","resourceObjectId":"d3"}}
                """);

        assertThat(engine.lookupResources(SubjectRef.user("u"), "view", "document", null))
                .containsExactly("d1", "d3");
        // TODO(issue-C01): 缺 permissionship 应抛，不得把 resourceObjectId 加入结果。
    }

    @Test
    void lookupSubjectsParsesStream() {
        respond("""
                {"result":{"subject":{"subjectObjectId":"u1"}}}
                {"result":{"subject":{"subjectObjectId":"u2"}}}
                """);
        assertThat(engine.lookupSubjects(ResourceRef.of("document", "d"), "view", "user", null))
                .containsExactly(SubjectRef.user("u1"), SubjectRef.user("u2"));
        // TODO(issue-C02): 顶层 error 或 null/blank id 应使整次调用失败。
    }

    @Test
    void writeRelationshipsSerializesOperationsAndReturnsToken() throws Exception {
        respond("{\"writtenAt\":{\"token\":\"zed-write\"}}");
        var updates = List.of(
                RelationshipUpdate.create(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1")),
                RelationshipUpdate.touch(ResourceRef.of("document", "d1"), "viewer",
                        SubjectRef.ofRelation("group", "acme_eng", "member")),
                RelationshipUpdate.delete(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u2")));

        assertThat(engine.writeRelationships(updates).token()).isEqualTo("zed-write");
        JsonNode body = requestJson();
        assertThat(captured.get().path()).isEqualTo("/v1/relationships/write");
        assertThat(body.at("/updates/0/operation").asText()).isEqualTo("OPERATION_CREATE");
        assertThat(body.at("/updates/1/operation").asText()).isEqualTo("OPERATION_TOUCH");
        assertThat(body.at("/updates/1/relationship/subject/optionalRelation").asText()).isEqualTo("member");
        assertThat(body.at("/updates/2/operation").asText()).isEqualTo("OPERATION_DELETE");
        // TODO(issue-C04): 缺 writtenAt.token 应抛协议异常。
    }

    @Test
    void deleteRelationshipsHonorsOptionalFilterFields() throws Exception {
        respond("{\"deletedAt\":{\"token\":\"zed-delete\"}}");
        assertThat(engine.deleteRelationships(RelationshipFilter.of("document", "d1", "viewer")).token())
                .isEqualTo("zed-delete");
        JsonNode full = requestJson().path("relationshipFilter");
        assertThat(full.path("resourceType").asText()).isEqualTo("document");
        assertThat(full.path("optionalResourceId").asText()).isEqualTo("d1");
        assertThat(full.path("optionalRelation").asText()).isEqualTo("viewer");

        engine.deleteRelationships(RelationshipFilter.of("document", null, null));
        JsonNode wildcard = requestJson().path("relationshipFilter");
        assertThat(wildcard.has("optionalResourceId")).isFalse();
        assertThat(wildcard.has("optionalRelation")).isFalse();
    }

    @Test
    void readsSchemaAndExpandTree() throws Exception {
        respond("{\"schemaText\":\"definition user {}\"}");
        assertThat(engine.readSchema()).isEqualTo("definition user {}");
        assertThat(captured.get().path()).isEqualTo("/v1/schema/read");

        respond("{\"treeRoot\":{\"leaf\":{\"subjects\":[]}}}");
        String tree = engine.expand(ResourceRef.of("document", "d"), "view", Consistency.fullyConsistent());
        assertThat(mapper.readTree(tree).at("/treeRoot/leaf/subjects").isArray()).isTrue();
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();
    }

    @Test
    void readRelationshipsParsesDirectAndUsersetTuples() throws Exception {
        respond("""
                {"result":{"relationship":{"resource":{"objectType":"document","objectId":"d1"},
                  "relation":"viewer","subject":{"object":{"objectType":"user","objectId":"u1"}}}}}
                {"result":{"relationship":{"resource":{"objectType":"document","objectId":"d1"},
                  "relation":"viewer","subject":{"object":{"objectType":"group","objectId":"acme_eng"},
                  "optionalRelation":"member"}}}}
                """);

        List<Relationship> result = engine.readRelationships(RelationshipFilter.ofResource(ResourceRef.of("document", "d1")));

        assertThat(result).containsExactly(
                new Relationship(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1")),
                new Relationship(ResourceRef.of("document", "d1"), "viewer",
                        SubjectRef.ofRelation("group", "acme_eng", "member")));
        assertThat(requestJson().at("/consistency/fullyConsistent").asBoolean()).isTrue();
        // TODO(issue-C02): 内部 objectType/objectId/relation 缺失必须抛。
    }

    @Test
    void rejectsMalformedJsonAndHttpError() {
        respond("not-json");
        assertThrows(IllegalStateException.class, () -> engine.readSchema());

        status.set(503);
        response.set("{\"error\":\"unavailable\"}");
        assertThrows(RestClientResponseException.class, () ->
                engine.check(SubjectRef.user("u"), "view", ResourceRef.of("document", "d"), null));
    }
}
```

### 3.2 CasdoorSyncControllerTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/CasdoorSyncControllerTest.java`

锁定行为：feature bean 缺失的 409、手动同步、webhook secret fail-closed、可选部门调用。直接 new controller，不测 Spring Security matcher（那需要 context/IT）。

```java
package com.lrj.authz.admin.casdoor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CasdoorSyncControllerTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void missingFeatureBeansReturnConflict() {
        CasdoorSyncController controller = new CasdoorSyncController(provider(null), provider(null), "hook");

        assertThat(controller.sync().getStatusCode().value()).isEqualTo(409);
        assertThat(controller.syncDepartments().getStatusCode().value()).isEqualTo(409);
        assertThat(controller.webhook("hook", "{}").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void manualSyncReturnsServiceSummary() {
        GroupSyncService group = mock(GroupSyncService.class);
        GroupSyncService.SyncSummary summary = new GroupSyncService.SyncSummary(2, 1, 0);
        when(group.sync()).thenReturn(summary);
        CasdoorSyncController controller = new CasdoorSyncController(provider(group), provider(null), "hook");

        var response = controller.sync();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(summary);
        verify(group).sync();
    }

    @Test
    void wrongWebhookSecretReturns401WithoutSync() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        CasdoorSyncController controller = new CasdoorSyncController(
                provider(group), provider(department), "correct-hook");

        var response = controller.webhook("wrong", "ignored");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(group, never()).sync();
        verify(department, never()).sync();
    }

    @Test
    void validWebhookRunsGroupAndOptionalDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        when(group.sync()).thenReturn(new GroupSyncService.SyncSummary(1, 2, 0));
        when(department.sync()).thenReturn(new DepartmentSyncService.SyncSummary(1, 3, 0));
        CasdoorSyncController controller = new CasdoorSyncController(
                provider(group), provider(department), "correct-hook");

        var response = controller.webhook("correct-hook", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("synced", true).containsKeys("summary", "departments");
        verify(group).sync();
        verify(department).sync();
    }

    @Test
    void blankConfiguredSecretCurrentlyAllowsMissingHeader() {
        GroupSyncService group = mock(GroupSyncService.class);
        when(group.sync()).thenReturn(new GroupSyncService.SyncSummary(0, 0, 0));
        CasdoorSyncController controller = new CasdoorSyncController(provider(group), provider(null), "");

        assertThat(controller.webhook(null, null).getStatusCode().value()).isEqualTo(200);
        verify(group).sync();
    }
}
```

待验证：空 webhook secret 放行是 `application.yml` 注释中“仅本地”的当前明确配置语义，因此可作为 feature-off 分支锁定；生产 profile 是否有独立 fail-fast 校验，仓库当前未见，部署审计应另做。

### 3.3 SyncServiceBoundaryTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/SyncServiceBoundaryTest.java`

锁定行为：删除数恰等阈值允许；读取 filter 必须限定 type/id/relation；部门换父在一次 write batch 同时 TOUCH 新边与 DELETE 旧边。

```java
package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceBoundaryTest {

    @SuppressWarnings("unchecked")
    private static List<RelationshipUpdate> updates(AuthzEngine engine) {
        ArgumentCaptor<List<RelationshipUpdate>> captor = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(captor.capture());
        return captor.getValue();
    }

    @Test
    void groupDeleteAtThresholdIsAllowedAndFilterIsScoped() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.groupMembers()).thenReturn(Map.of());
        when(casdoor.groupNames()).thenReturn(Set.of("acme_eng"));
        when(engine.readRelationships(any())).thenReturn(List.of(new Relationship(
                ResourceRef.of("group", "acme_eng"), "member", SubjectRef.user("u1"))));

        GroupSyncService.SyncSummary result = new GroupSyncService(casdoor, engine, 1).sync();

        assertThat(result).isEqualTo(new GroupSyncService.SyncSummary(1, 0, 1));
        ArgumentCaptor<RelationshipFilter> filter = ArgumentCaptor.forClass(RelationshipFilter.class);
        verify(engine).readRelationships(filter.capture());
        assertThat(filter.getValue()).isEqualTo(RelationshipFilter.of("group", "acme_eng", "member"));
        assertThat(updates(engine)).containsExactly(RelationshipUpdate.delete(
                ResourceRef.of("group", "acme_eng"), "member", SubjectRef.user("u1")));
    }

    @Test
    void departmentParentReplacementIsAtomicInOneWrite() {
        CasdoorClient casdoor = mock(CasdoorClient.class);
        AuthzEngine engine = mock(AuthzEngine.class);
        when(casdoor.departmentSnapshot()).thenReturn(new CasdoorClient.DepartmentSnapshot(
                Set.of("acme_child"), Map.of(), Map.of("acme_child", "acme_new"), Map.of()));
        when(engine.readRelationships(any())).thenAnswer(invocation -> {
            RelationshipFilter filter = invocation.getArgument(0);
            if ("parent".equals(filter.relation())) {
                return List.of(new Relationship(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_old")));
            }
            return List.of();
        });

        DepartmentSyncService.SyncSummary result = new DepartmentSyncService(casdoor, engine, 10).sync();

        assertThat(result).isEqualTo(new DepartmentSyncService.SyncSummary(1, 1, 1));
        assertThat(updates(engine)).containsExactly(
                RelationshipUpdate.touch(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_new")),
                RelationshipUpdate.delete(ResourceRef.of("department", "acme_child"), "parent",
                        SubjectRef.of("department", "acme_old")));
        // 一次 writeRelationships 承载完整差量；不声称它与 Casdoor 快照读取构成事务。
    }

    // TODO(issue-CAS02): Casdoor 已删除实体当前不在遍历集合，无法写出“应清理旧 tuple”的通过测试。
}
```

### 3.4 ProtocolValueObjectsTest

放置路径：`auth-platform-protocol/src/test/java/com/lrj/authz/protocol/ProtocolValueObjectsTest.java`

锁定行为：只锁定合法 factory 和 record 值语义。非法 null/blank 当前是 issue-ADM02，不能断言为“允许”。

```java
package com.lrj.authz.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolValueObjectsTest {

    @Test
    void resourceFactoryAndRef() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        assertThat(resource.type()).isEqualTo("document");
        assertThat(resource.id()).isEqualTo("d1");
        assertThat(resource.ref()).isEqualTo("document:d1");
        assertThat(resource).isEqualTo(new ResourceRef("document", "d1"));
    }

    @Test
    void subjectFactoriesPreserveUserset() {
        assertThat(SubjectRef.user("u1")).isEqualTo(new SubjectRef("user", "u1", null));
        assertThat(SubjectRef.of("service", "s1")).isEqualTo(new SubjectRef("service", "s1", null));
        assertThat(SubjectRef.ofRelation("group", "acme_eng", "member"))
                .isEqualTo(new SubjectRef("group", "acme_eng", "member"));
    }

    @Test
    void relationshipHasValueSemantics() {
        Relationship relationship = new Relationship(
                ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1"));
        assertThat(relationship).isEqualTo(new Relationship(
                new ResourceRef("document", "d1"), "viewer", new SubjectRef("user", "u1", null)));
    }

    @Test
    void updateFactoriesSelectExactOperation() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        SubjectRef subject = SubjectRef.user("u1");
        assertThat(RelationshipUpdate.create(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.CREATE);
        assertThat(RelationshipUpdate.touch(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.TOUCH);
        assertThat(RelationshipUpdate.delete(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.DELETE);
    }

    @Test
    void filterFactoriesPreserveWildcards() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        assertThat(RelationshipFilter.ofResource(resource))
                .isEqualTo(new RelationshipFilter("document", "d1", null));
        assertThat(RelationshipFilter.of("document", null, "viewer"))
                .isEqualTo(new RelationshipFilter("document", null, "viewer"));
    }

    @Test
    void consistencyFactoriesEncodeModes() {
        assertThat(Consistency.minimizeLatency())
                .isEqualTo(new Consistency(Consistency.Mode.MINIMIZE_LATENCY, null));
        assertThat(Consistency.fullyConsistent())
                .isEqualTo(new Consistency(Consistency.Mode.FULLY_CONSISTENT, null));
        assertThat(Consistency.atLeastAsFresh("zed-1"))
                .isEqualTo(new Consistency(Consistency.Mode.AT_LEAST_AS_FRESH, "zed-1"));
    }

    @Test
    void zedTokenHasValueSemantics() {
        assertThat(new ZedTokenView("zed-1")).isEqualTo(new ZedTokenView("zed-1"));
    }

    // TODO(issue-ADM02): 修复 compact constructor 后，为每类补 null/blank/非法组合 assertThrows。
}
```

落地前置（待验证/需授权）：`auth-platform-protocol/pom.xml` 当前无测试依赖。应只增加如下 test-scope 依赖后再落地该类；本轮严格不改 POM。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

### 3.5 CasdoorClientTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/CasdoorClientTest.java`

锁定行为：loopback HTTP 下验证 Basic auth、实际 query、单/多 org、subjectField、去重、group owner、部门父子和管理员解析。缺 data 与跨 org 错误行为只 TODO。

```java
package com.lrj.authz.admin.casdoor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CasdoorClientTest {

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> seenPaths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private CasdoorProperties props;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        props = new CasdoorProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setClientId("client");
        props.setClientSecret("secret");
        props.setOrganization("acme");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        seenPaths.add(key);
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void effectiveOrganizationsUsesListOrFallback() {
        assertThat(props.effectiveOrganizations()).containsExactly("acme");
        props.setOrganizations(List.of("acme", "beta"));
        assertThat(props.effectiveOrganizations()).containsExactly("acme", "beta");
        props.setOrganizations(null);
        assertThat(props.effectiveOrganizations()).containsExactly("acme");
    }

    @Test
    void groupMembersScopesAndDeduplicatesAcrossOrganizations() {
        props.setOrganizations(List.of("acme", "beta"));
        responses.put("/api/get-users?owner=acme", """
                {"data":[
                  {"name":"alice","id":"sub-a","groups":["eng","acme/eng",""]},
                  {"name":"missing-id","groups":["eng"]}
                ]}
                """);
        responses.put("/api/get-users?owner=beta", """
                {"data":[{"name":"bob","id":"sub-b","groups":["eng"]}]}
                """);

        Map<String, Set<String>> result = new CasdoorClient(props).groupMembers();

        assertThat(result).containsEntry("acme_eng", Set.of("sub-a"))
                .containsEntry("beta_eng", Set.of("sub-b"));
        assertThat(result).hasSize(2);
        assertThat(seenPaths).containsExactly("/api/get-users?owner=acme", "/api/get-users?owner=beta");
        String expected = "Basic " + Base64.getEncoder().encodeToString("client:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders).allMatch(expected::equals);
        // TODO(issue-CAS03): acme 用户引用 beta/eng 应 fail-closed；当前会生成 beta_eng。
    }

    @Test
    void nameSubjectFieldUsesUserName() {
        props.setSubjectField("name");
        responses.put("/api/get-users?owner=acme", """
                {"data":[{"name":"alice","id":"opaque-id","groups":["eng"]}]}
                """);

        assertThat(new CasdoorClient(props).groupMembers())
                .containsEntry("acme_eng", Set.of("alice"));
    }

    @Test
    void groupNamesUsesOwnerAndSkipsBlankNames() {
        responses.put("/api/get-groups?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"eng"},
                  {"name":"ops"},
                  {"owner":"acme","name":" "}
                ]}
                """);

        assertThat(new CasdoorClient(props).groupNames()).containsExactly("acme_eng", "acme_ops");
    }

    @Test
    void departmentSnapshotBuildsTreeAndAdmins() {
        responses.put("/api/get-users?owner=acme", """
                {"data":[
                  {"name":"alice","id":"sub-a","groups":["child"]},
                  {"name":"bob","id":"sub-b","groups":[]}
                ]}
                """);
        responses.put("/api/get-groups?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"parent","parentId":""},
                  {"owner":"acme","name":"child","parentId":"acme/parent"}
                ]}
                """);
        responses.put("/api/get-roles?owner=acme", """
                {"data":[
                  {"owner":"acme","name":"child-admin","users":["acme/alice","acme/unknown"]},
                  {"owner":"acme","name":"unrelated","users":["acme/bob"]}
                ]}
                """);

        CasdoorClient.DepartmentSnapshot result = new CasdoorClient(props).departmentSnapshot();

        assertThat(result.deptIds()).containsExactly("acme_parent", "acme_child");
        assertThat(result.members()).containsEntry("acme_child", Set.of("sub-a"));
        assertThat(result.parents()).containsExactly(Map.entry("acme_child", "acme_parent"));
        assertThat(result.admins()).containsEntry("acme_child", Set.of("sub-a")).hasSize(1);
        assertThat(seenPaths).containsExactly(
                "/api/get-users?owner=acme", "/api/get-groups?owner=acme", "/api/get-roles?owner=acme");
    }

    @Test
    void malformedJsonFailsWithProtocolException() {
        responses.put("/api/get-users?owner=acme", "not-json");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CasdoorClient(props).groupMembers());
        assertThat(ex).hasMessageContaining("Casdoor").hasMessageContaining("解析失败");
    }

    // TODO(issue-CAS01): 200 {} / blank / data 非数组应失败，不能返回空快照。
    // TODO(issue-CAS04): 特殊 organization 必须 URI 编码或预校验；修复后检查服务端 rawQuery。
}
```

### 3.6 ReconcileJobTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/ReconcileJobTest.java`

锁定行为：组先执行、可选部门随后执行；无部门 bean 不报错；第一阶段异常后不误执行第二阶段。明确这是编排，不证明跨阶段事务。

```java
package com.lrj.authz.admin.casdoor;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileJobTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DepartmentSyncService> providerOf(DepartmentSyncService service) {
        ObjectProvider<DepartmentSyncService> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<DepartmentSyncService> consumer = invocation.getArgument(0);
            if (service != null) {
                consumer.accept(service);
            }
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    @Test
    void reconcilesGroupThenOptionalDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);

        new ReconcileJob(group, providerOf(department)).reconcile();

        InOrder order = inOrder(group, department);
        order.verify(group).sync();
        order.verify(department).sync();
    }

    @Test
    void noDepartmentBeanStillRunsGroupOnce() {
        GroupSyncService group = mock(GroupSyncService.class);

        new ReconcileJob(group, providerOf(null)).reconcile();

        verify(group).sync();
    }

    @Test
    void groupFailurePropagatesAndStopsBeforeDepartment() {
        GroupSyncService group = mock(GroupSyncService.class);
        DepartmentSyncService department = mock(DepartmentSyncService.class);
        when(group.sync()).thenThrow(new IllegalStateException("group failed"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ReconcileJob(group, providerOf(department)).reconcile());

        assertThat(ex).hasMessageContaining("group failed");
        verify(department, never()).sync();
        // TODO(issue-R01): department failure after group success leaves partial state；需指标/重试语义。
    }
}
```

### 3.7 AdminControllerTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/AdminControllerTest.java`

锁定行为：grant/revoke 的 operation、userset、actor 和 audit detail；所有管理读取强一致；engine 失败没有虚假审计。审计自身失败后的分布式部分成功只列 issue。

```java
package com.lrj.authz.admin;

import com.lrj.authz.admin.AdminDtos.CheckRequest;
import com.lrj.authz.admin.AdminDtos.GrantRequest;
import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private static final GrantRequest USERSET = new GrantRequest(
            "document", "d1", "viewer", "group", "acme_eng", "member");

    @SuppressWarnings("unchecked")
    private static List<RelationshipUpdate> capturedWrite(AuthzEngine engine) {
        ArgumentCaptor<List<RelationshipUpdate>> captor = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(captor.capture());
        return captor.getValue();
    }

    @Test
    void grantWritesTouchAndAuditsNamedActor() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("name")).thenReturn("Alice Admin");
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-1"));

        var response = new AdminController(engine, audit).grant(USERSET, jwt);

        assertThat(response.token()).isEqualTo("zed-1");
        assertThat(capturedWrite(engine)).containsExactly(RelationshipUpdate.touch(
                ResourceRef.of("document", "d1"), "viewer",
                SubjectRef.ofRelation("group", "acme_eng", "member")));
        verify(audit).record("Alice Admin", "grant", "document:d1#viewer@group:acme_eng#member");
    }

    @Test
    void revokeWritesDeleteAndFallsBackToJwtSubject() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("name")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("oidc-sub");
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-2"));
        GrantRequest direct = new GrantRequest("document", "d1", "owner", "user", "u1", " ");

        new AdminController(engine, audit).revoke(direct, jwt);

        assertThat(capturedWrite(engine)).containsExactly(RelationshipUpdate.delete(
                ResourceRef.of("document", "d1"), "owner", SubjectRef.user("u1")));
        verify(audit).record("oidc-sub", "revoke", "document:d1#owner@user:u1");
    }

    @Test
    void writeFailureIsNotAudited() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        when(engine.writeRelationships(anyList())).thenThrow(new IllegalStateException("SpiceDB down"));

        assertThrows(IllegalStateException.class, () -> new AdminController(engine, audit).grant(USERSET, null));

        verify(audit, never()).record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // TODO(issue-ADM01): audit 失败时 engine 已成功，需产品修复后定义可靠语义。
    }

    @Test
    void adminReadsAlwaysUseFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        ResourceRef resource = ResourceRef.of("document", "d1");
        when(engine.lookupSubjects(resource, "view", "user", Consistency.fullyConsistent()))
                .thenReturn(List.of(SubjectRef.user("u1")));
        when(engine.lookupResources(SubjectRef.of("user", "u1"), "view", "document", Consistency.fullyConsistent()))
                .thenReturn(List.of("d1"));

        AdminController controller = new AdminController(engine, audit);
        assertThat(controller.subjects("document", "d1", "view", "user").subjects())
                .extracting(s -> s.id()).containsExactly("u1");
        assertThat(controller.resources("user", "u1", "view", "document").resourceIds())
                .containsExactly("d1");
    }

    @Test
    void checkUsesFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(SubjectRef.of("user", "u1"), "edit", ResourceRef.of("document", "d1"),
                Consistency.fullyConsistent())).thenReturn(true);

        var result = new AdminController(engine, mock(AuditStore.class)).check(
                new CheckRequest("user", "u1", "edit", "document", "d1"));

        assertThat(result.allowed()).isTrue();
        verify(engine).check(SubjectRef.of("user", "u1"), "edit", ResourceRef.of("document", "d1"),
                Consistency.fullyConsistent());
    }

    @Test
    void schemaRelationshipsAndAuditDelegate() {
        AuthzEngine engine = mock(AuthzEngine.class);
        AuditStore audit = mock(AuditStore.class);
        RelationshipFilter filter = RelationshipFilter.of("document", "d1", null);
        Relationship tuple = new Relationship(ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1"));
        AuditStore.AuditRecord record = new AuditStore.AuditRecord("2026-07-17T00:00:00Z", "a", "grant", "x");
        when(engine.readSchema()).thenReturn("definition user {}");
        when(engine.readRelationships(filter)).thenReturn(List.of(tuple));
        when(audit.recent(7)).thenReturn(List.of(record));
        AdminController controller = new AdminController(engine, audit);

        assertThat(controller.schema()).containsEntry("schema", "definition user {}");
        assertThat(controller.relationships("document", "d1", null)).containsExactly(tuple);
        assertThat(controller.auditLog(7)).containsExactly(record);
        verify(engine).readRelationships(filter);
        verify(audit).recent(7);
    }

    @Test
    void expandParsesJsonAndRejectsMalformedTree() {
        AuthzEngine engine = mock(AuthzEngine.class);
        CheckRequest request = new CheckRequest("user", "ignored", "view", "document", "d1");
        when(engine.expand(ResourceRef.of("document", "d1"), "view", Consistency.fullyConsistent()))
                .thenReturn("{\"treeRoot\":{}}");
        AdminController controller = new AdminController(engine, mock(AuditStore.class));
        assertThat(controller.expand(request).has("treeRoot")).isTrue();

        when(engine.expand(ResourceRef.of("document", "d1"), "view", Consistency.fullyConsistent()))
                .thenReturn("broken");
        assertThrows(IllegalStateException.class, () -> controller.expand(request));
    }
}
```

### 3.8 InMemoryAuditStoreTest

放置路径：`auth-platform-admin/src/test/java/com/lrj/authz/admin/InMemoryAuditStoreTest.java`

锁定行为：确定性地验证容量/顺序/归一；并发仅锁定上限与记录完整性，不写容易抖动的“恰好保留哪 N 条”。

```java
package com.lrj.authz.admin;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditStoreTest {

    @Test
    void keepsNewestUpToCapacityAndNormalizesActor() {
        InMemoryAuditStore store = new InMemoryAuditStore(2);
        store.record(null, "grant", "one");
        store.record("bob", "revoke", "two");
        store.record("carol", "grant", "three");

        List<AuditStore.AuditRecord> result = store.recent(10);
        assertThat(result).extracting(AuditStore.AuditRecord::detail).containsExactly("three", "two");
        assertThat(result).extracting(AuditStore.AuditRecord::actor).containsExactly("carol", "bob");
        assertThat(result).allSatisfy(r -> assertThat(Instant.parse(r.at())).isNotNull());
    }

    @Test
    void floorsCapacityAndLimitAtOne() {
        InMemoryAuditStore store = new InMemoryAuditStore(0);
        store.record(null, "grant", "first");
        store.record(null, "grant", "second");

        assertThat(store.recent(0)).singleElement().satisfies(r -> {
            assertThat(r.actor()).isEqualTo("-");
            assertThat(r.detail()).isEqualTo("second");
        });
    }

    @Test
    void concurrentWritersNeverExposeMoreThanCapacity() throws Exception {
        int capacity = 25;
        InMemoryAuditStore store = new InMemoryAuditStore(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    store.record("u" + n, "grant", "detail-" + n);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<AuditStore.AuditRecord> result = store.recent(1000);
        assertThat(result).hasSizeLessThanOrEqualTo(capacity).isNotEmpty();
        assertThat(result).allSatisfy(r -> {
            assertThat(r.actor()).startsWith("u");
            assertThat(r.detail()).startsWith("detail-");
            assertThat(r.action()).isEqualTo("grant");
        });
        // TODO(issue-AUD01): 修复复合原子性后，应稳定断言并发结束恰好保留 capacity 条。
    }
}
```

### 3.9 AuthzControllerFacadeTest

放置路径：`auth-platform-server/src/test/java/com/lrj/authz/server/AuthzControllerFacadeTest.java`

锁定行为：直接 new controller，逐字段验证九个 facade 的端口调用和结果形状；特别验证请求顺序及 full/minimize 转换。bulk 漏项只留 TODO。

```java
package com.lrj.authz.server;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import com.lrj.authz.server.AuthzDtos.CheckBulkRequest;
import com.lrj.authz.server.AuthzDtos.CheckRequest;
import com.lrj.authz.server.AuthzDtos.ConsistencyDto;
import com.lrj.authz.server.AuthzDtos.DeleteRequest;
import com.lrj.authz.server.AuthzDtos.ExpandRequest;
import com.lrj.authz.server.AuthzDtos.LookupResourcesRequest;
import com.lrj.authz.server.AuthzDtos.LookupSubjectsRequest;
import com.lrj.authz.server.AuthzDtos.WriteRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthzControllerFacadeTest {

    private static final SubjectRef SUBJECT = SubjectRef.user("u1");
    private static final ResourceRef D1 = ResourceRef.of("document", "d1");
    private static final ResourceRef D2 = ResourceRef.of("document", "d2");

    @Test
    void checkMapsConsistencyModesAndArguments() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(true);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.check(new CheckRequest(SUBJECT, "view", D1, null)).allowed()).isTrue();
        controller.check(new CheckRequest(SUBJECT, "view", D1, new ConsistencyDto("FULLY_CONSISTENT", null)));
        controller.check(new CheckRequest(SUBJECT, "view", D1, new ConsistencyDto("unknown", "ignored")));

        ArgumentCaptor<Consistency> consistency = ArgumentCaptor.forClass(Consistency.class);
        verify(engine, times(3)).check(any(), any(), any(), consistency.capture());
        assertThat(consistency.getAllValues()).containsExactly(
                Consistency.minimizeLatency(), Consistency.fullyConsistent(), Consistency.minimizeLatency());
        verify(engine, times(2)).check(SUBJECT, "view", D1, Consistency.minimizeLatency());
    }

    @Test
    void bulkPreservesRequestOrder() {
        AuthzEngine engine = mock(AuthzEngine.class);
        LinkedHashMap<ResourceRef, Boolean> map = new LinkedHashMap<>();
        map.put(D2, false);
        map.put(D1, true);
        when(engine.checkBulk(SUBJECT, "view", List.of(D1, D2), Consistency.fullyConsistent())).thenReturn(map);
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        var response = controller.checkBulk(new CheckBulkRequest(
                SUBJECT, "view", List.of(D1, D2), new ConsistencyDto("full", null)));

        assertThat(response.results()).extracting(r -> r.resource()).containsExactly(D1, D2);
        assertThat(response.results()).extracting(r -> r.allowed()).containsExactly(true, false);
        // TODO(issue-S01): engine map 缺 D2 应抛，不能断言 D2=false。
    }

    @Test
    void delegatesLookupMethodsWithFullConsistency() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.lookupResources(SUBJECT, "view", "document", Consistency.fullyConsistent()))
                .thenReturn(List.of("d1", "d2"));
        when(engine.lookupSubjects(D1, "view", "user", Consistency.fullyConsistent()))
                .thenReturn(List.of(SubjectRef.user("u2")));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.lookupResources(new LookupResourcesRequest(
                SUBJECT, "view", "document", new ConsistencyDto("full", null))).resourceIds())
                .containsExactly("d1", "d2");
        assertThat(controller.lookupSubjects(new LookupSubjectsRequest(
                D1, "view", "user", new ConsistencyDto("full", null))).subjects())
                .containsExactly(SubjectRef.user("u2"));
    }

    @Test
    void writeAndDeleteAdvanceWatermark() {
        AuthzEngine engine = mock(AuthzEngine.class);
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        RelationshipUpdate update = RelationshipUpdate.touch(D1, "viewer", SUBJECT);
        RelationshipFilter filter = RelationshipFilter.ofResource(D1);
        when(engine.writeRelationships(List.of(update))).thenReturn(new ZedTokenView("write-token"));
        when(engine.deleteRelationships(filter)).thenReturn(new ZedTokenView("delete-token"));
        AuthzController controller = new AuthzController(engine, watermark, true);

        assertThat(controller.write(new WriteRequest(List.of(update))).token()).isEqualTo("write-token");
        assertThat(watermark.latest()).isEqualTo("write-token");
        assertThat(controller.delete(new DeleteRequest(filter)).token()).isEqualTo("delete-token");
        assertThat(watermark.latest()).isEqualTo("delete-token");
    }

    @Test
    void schemaExpandAndReadRelationshipsDelegate() {
        AuthzEngine engine = mock(AuthzEngine.class);
        RelationshipFilter filter = RelationshipFilter.ofResource(D1);
        Relationship relationship = new Relationship(D1, "viewer", SUBJECT);
        when(engine.readSchema()).thenReturn("definition user {}");
        when(engine.expand(D1, "view", Consistency.fullyConsistent())).thenReturn("{\"treeRoot\":{}}");
        when(engine.readRelationships(filter)).thenReturn(List.of(relationship));
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        assertThat(controller.schema()).containsEntry("schema", "definition user {}");
        assertThat(controller.expand(new ExpandRequest(D1, "view", new ConsistencyDto("full", null)))
                .has("treeRoot")).isTrue();
        assertThat(controller.readRelationships(new DeleteRequest(filter)).relationships())
                .containsExactly(relationship);
    }

    @Test
    void expandRejectsMalformedEngineJson() {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.expand(any(), any(), any())).thenReturn("not-json");
        AuthzController controller = new AuthzController(engine, new ZedTokenWatermark(), true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                controller.expand(new ExpandRequest(D1, "view", null)));
        assertThat(ex).hasMessageContaining("expand");
    }
}
```

### 3.10 ZedTokenWatermarkTest

放置路径：`auth-platform-server/src/test/java/com/lrj/authz/server/ZedTokenWatermarkTest.java`

锁定行为：空 token 不覆盖、并发读到的只能是完整提交 token。测试刻意不把“最后调度到的线程”解释成最新 revision。

```java
package com.lrj.authz.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ZedTokenWatermarkTest {

    @Test
    void ignoresBlankAndPublishesNonBlankToken() {
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        assertThat(watermark.latest()).isNull();
        watermark.advance(null);
        watermark.advance("");
        watermark.advance("   ");
        assertThat(watermark.latest()).isNull();

        watermark.advance("zed-1");
        watermark.advance(" ");
        assertThat(watermark.latest()).isEqualTo("zed-1");
    }

    @Test
    void concurrentAdvancePublishesACompleteSubmittedToken() throws Exception {
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        Set<String> submitted = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> "zed-" + i).collect(java.util.stream.Collectors.toSet());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String token : submitted) {
                futures.add(pool.submit(() -> {
                    start.await();
                    watermark.advance(token);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(watermark.latest()).isIn(submitted);
        // TODO(issue-S02): 不断言该值代表 SpiceDB 因果上最新 revision。
    }
}
```

### 3.11 AuthzServerSecurityFilterBoundaryTest

放置路径：`auth-platform-server/src/test/java/com/lrj/authz/server/AuthzServerSecurityFilterBoundaryTest.java`

锁定行为：401 不进入 chain，错误体稳定且不回显 token；仅精确 `Bearer ` 大小写/空格语法被接受。

```java
package com.lrj.authz.server;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthzServerSecurityFilterBoundaryTest {

    private static AuthzServerSecurityProperties enabled() {
        AuthzServerSecurityProperties props = new AuthzServerSecurityProperties();
        props.setEnabled(true);
        props.setToken("correct-secret");
        return props;
    }

    @Test
    void unauthorizedResponseDoesNotInvokeChainOrEchoCredential() throws Exception {
        AuthzServerSecurityFilter filter = new AuthzServerSecurityFilter(enabled());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/check");
        request.addHeader("Authorization", "Bearer wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("unauthorized").doesNotContain("wrong-secret");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsWrongAuthorizationSchemesAndWhitespace() throws Exception {
        for (String header : new String[]{"Basic correct-secret", "bearer correct-secret", "Bearer  correct-secret", "Bearer "}) {
            AuthzServerSecurityFilter filter = new AuthzServerSecurityFilter(enabled());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/relationships");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).as(header).isEqualTo(401);
            verify(chain, never()).doFilter(request, response);
        }
    }

    // TODO(issue-SEC01): 确认 /v1 根路径是否属于保护面；当前会放行，不能先固化。
    // 编码路径/路径规范化需 servlet container IT；MockHttpServletRequest 不代表 Tomcat 最终 requestURI。
}
```

### 3.12 CheckAccessAspectTest

放置路径：`auth-platform-sdk/src/test/java/com/lrj/authz/sdk/CheckAccessAspectTest.java`

锁定行为：使用真实注解实例、手工 mock join point/signature；验证参数按名字解析而非误用第一个参数，deny/依赖异常均不执行目标方法。

```java
package com.lrj.authz.sdk;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckAccessAspectTest {

    private static final SubjectRef SUBJECT = SubjectRef.user("u1");

    private static class Fixture {
        @CheckAccess(permission = "view", resourceType = "document", resourceIdParam = "docId")
        Object guarded(String ignored, String docId) { return null; }

        @CheckAccess(permission = "delete", resourceType = "document", resourceIdParam = "docId",
                fullyConsistent = true)
        Object sensitive(String docId) { return null; }
    }

    private static CheckAccess annotation(String method, Class<?>... types) throws Exception {
        return Fixture.class.getDeclaredMethod(method, types).getAnnotation(CheckAccess.class);
    }

    private static ProceedingJoinPoint joinPoint(String[] names, Object[] args, Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getParameterNames()).thenReturn(names);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    @Test
    void allowedProceedsWithResolvedNamedParameter() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        SubjectResolver resolver = mock(SubjectResolver.class);
        when(resolver.currentSubject()).thenReturn(SUBJECT);
        when(engine.check(SUBJECT, "view", ResourceRef.of("document", "42"), Consistency.minimizeLatency()))
                .thenReturn(true);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"ignored", "docId"}, new Object[]{"not-the-id", "42"}, "result");

        Object result = new CheckAccessAspect(engine, resolver).around(
                pjp, annotation("guarded", String.class, String.class));

        assertThat(result).isEqualTo("result");
        verify(resolver).currentSubject();
        verify(engine).check(SUBJECT, "view", ResourceRef.of("document", "42"), Consistency.minimizeLatency());
        verify(pjp).proceed();
    }

    @Test
    void fullyConsistentAnnotationUsesFullMode() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        SubjectResolver resolver = () -> SUBJECT;
        when(engine.check(any(), any(), any(), any())).thenReturn(true);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"docId"}, new Object[]{"d9"}, "ok");

        new CheckAccessAspect(engine, resolver).around(pjp, annotation("sensitive", String.class));

        ArgumentCaptor<Consistency> consistency = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).check(any(), any(), any(), consistency.capture());
        assertThat(consistency.getValue()).isEqualTo(Consistency.fullyConsistent());
    }

    @Test
    void deniedNeverInvokesTarget() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(false);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"ignored", "docId"}, new Object[]{"x", "d1"}, "must-not-run");

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("guarded", String.class, String.class)));

        assertThat(ex).hasMessageContaining("view").hasMessageContaining("document:d1");
        verify(pjp, never()).proceed();
    }

    @Test
    void missingParameterNameFailsBeforeCheck() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"other"}, new Object[]{"d1"}, "unused");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("sensitive", String.class)));

        assertThat(ex).hasMessageContaining("docId");
        verify(engine, never()).check(any(), any(), any(), any());
        verify(pjp, never()).proceed();
    }

    @Test
    void engineFailureNeverInvokesTarget() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenThrow(new IllegalStateException("dependency down"));
        ProceedingJoinPoint pjp = joinPoint(new String[]{"docId"}, new Object[]{"d1"}, "unused");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("sensitive", String.class)));

        assertThat(ex).hasMessageContaining("dependency down");
        verify(pjp, never()).proceed();
    }

    // TODO(issue-A01): names==null、args 长度不符、docId null/blank 应显式 fail-closed，修复后启用。
    // TODO(issue-A02): 当前没有 SpEL/下标契约，不得写成功解析 #request.id 的虚假测试。
}
```

### 3.13 RemoteAuthzEngineHttpTest

放置路径：`auth-platform-sdk/src/test/java/com/lrj/authz/sdk/RemoteAuthzEngineHttpTest.java`

锁定行为：补既有纯 parser 测试未触达的单 check 私有严格校验、Bearer 和 consistency JSON；空 bulk 必须零网络调用。

```java
package com.lrj.authz.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteAuthzEngineHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>("{\"allowed\":true}");
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private RemoteAuthzEngine engine;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        engine = new RemoteAuthzEngine("http://127.0.0.1:" + server.getAddress().getPort(), "service-token",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void singleCheckRequiresBooleanAllowedAndSendsFreshness() throws Exception {
        boolean allowed = engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"),
                Consistency.atLeastAsFresh("zed-1"));

        assertThat(allowed).isTrue();
        assertThat(authorization).hasValue("Bearer service-token");
        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.at("/consistency/mode").asText()).isEqualTo("at_least_as_fresh");
        assertThat(body.at("/consistency/zedToken").asText()).isEqualTo("zed-1");
        assertThat(body.at("/resource/id").asText()).isEqualTo("d1");

        response.set("{}");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
        assertThat(missing).hasMessageContaining("allowed");

        response.set("{\"allowed\":\"true\"}");
        assertThrows(IllegalStateException.class, () ->
                engine.check(SubjectRef.user("u1"), "view", ResourceRef.of("document", "d1"), null));
    }

    @Test
    void emptyBulkDoesNotPerformHttpRequest() {
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", null, null)).isEmpty();
        assertThat(engine.checkBulk(SubjectRef.user("u"), "view", List.of(), null)).isEmpty();
        assertThat(requests).hasValue(0);
    }
}
```

## 4. 覆盖矩阵摘要与落地批次

完整逐方法矩阵见 [02-coverage-matrix.md](./02-coverage-matrix.md)。建议不是一次把所有草案无脑粘入，而是按以下门禁落地：

1. 第一批（可立即落地且应全绿）：AOP 的合法/deny/异常短路；Remote 单 check；server/admin facade 正常交互；watermark 基础/可见性；security boundary；Casdoor 正常解析；Reconcile 编排；InMemory 顺序；同步阈值边界；protocol 合法值（先补 test dependency）。
2. 第二批（先修 issue，再启用 TODO）：core lookup/error/空 body/token 严格化、server bulk 完整性、AOP null metadata、Casdoor schema/跨 org、DTO 参数校验。
3. 第三批（需要架构决策）：并发水位单调、删除实体 manifest、可靠审计、两阶段 reconcile 原子或独立重试、SpEL/下标是否属于产品能力。

关键缺口关闭标准：P0 core 九操作至少各一条 happy path，判权相关每种 malformed path 一条 fail-closed；AOP 四条控制流全覆盖；core→server→SDK 的 `AT_LEAST_AS_FRESH(zedToken)` 各层请求体/实参都有断言；admin 写元组和 audit actor/detail 同时被验证；Casdoor 多 org、group tree 和删除风险均有测试或 issue 门禁。

## 5. 疑似问题摘要（不得固化为正确断言）

完整证据与复现见 [03-suspected-issues.md](./03-suspected-issues.md)。

| ID | 预期 vs 现状 | 最小复现 | 建议 |
|---|---|---|---|
| C01 | 缺 permissionship 应失败；当前 lookupResources 将空值当 allow | result 只有 `resourceObjectId=secret`，返回 `[secret]` | 严格枚举校验后启用 TODO |
| C02 | 流中 error/缺字段应整次失败；当前跳过或生成空 tuple | 合法 result 后拼 `{"error":...}`，返回部分列表 | 为每类 stream message 做结构校验 |
| C03 | 契约要求 body 时空 2xx 应失败；当前变 `{}`/`[]` | write/schema/lookup 返回 200 空 body | 按端口区分正常空流和截断 |
| C04 | write/delete 必须返回 token；当前可 `ZedTokenView(null)` 并 200 | `{"writtenAt":{}}` | core+server 双层校验非 blank |
| S01 | bulk map 必须完整；当前漏项被补成 deny | 请求 d1,d2，engine 仅 `{d1=true}` | 校验 key 集和值，异常而非 deny |
| S02 | 水位不应因并发回退；当前 last `set` 可写回旧 token | B 新写先 advance，A 旧写后 advance | 调用方 token 或外部单调 revision |
| A01 | 参数 metadata/null 应显式 fail-closed；当前 NPE/资源 `"null"` | names=null 或 args=[null] | 校验 names/args/id 后再 check |
| A02 | SpEL/下标须有明确契约；当前只支持参数名 | `resourceIdParam="#request.id"` | 产品确认；若扩展用受限解析器 |
| CAS01 | 缺 data 应中止；当前视为空，可能撤组成员 | users `{}` + groups 正常 + Spice 有 u1 | 严格 Casdoor response schema |
| CAS02 | 删除组/部门应清理旧 tuple；当前实体不进入遍历集合 | Casdoor 删除 g 后两 map 为空 | manifest/tombstone/租户枚举 |
| CAS03 | 默认不应跨 org 串写；当前信任完整引用 owner | acme 用户 groups=`beta/admin` | 同 org 校验或显式 allowlist |
| CAS04 | query 应编码；当前字符串拼 org | org=`acme&owner=beta` | URI builder + 配置 fail-fast |
| R01 | 分阶段失败语义应可观测；当前可能半同步 | group 成功、department 抛错 | 独立指标/重试，或合并单写 |
| ADM01 | 写变更与审计不应分裂；当前 audit 失败时 Spice 已写 | engine 返回 t、audit 抛错 | outbox/intent+完成态/补偿 |
| ADM02 | 值对象/DTO 应早校验；当前 null 深处 NPE/外发 | `atLeastAsFresh(null)` 后 core check | protocol compact constructor + HTTP validation |
| AUD01 | 并发 ring 不应过裁；当前 add/size/poll 非复合原子 | capacity 小、barrier 并发 record | 锁/有界结构/原子计数 |
| SEC01 | `/v1/**` 是否含 `/v1` 应明确；当前根路径放行 | enabled、URI `/v1`、无 token | 若属保护面，加 equals 分支 |
| BLD01 | protocol 应有 test classpath；当前无 JUnit/AssertJ | 落地 ProtocolValueObjectsTest testCompile | 增 test-scope starter-test（需授权） |

## 6. 运行与验证命令

基线已在 2026-07-17 执行：`mvn test`，38 tests、0 failures/errors，BUILD SUCCESS。落地后按模块执行：

```bash
mvn -pl auth-platform-protocol -am test
mvn -pl auth-platform-core -am test
mvn -pl auth-platform-sdk -am test
mvn -pl auth-platform-server -am test
mvn -pl auth-platform-admin -am test
mvn test
```

聚焦单类：

```bash
mvn -pl auth-platform-core -Dtest=SpiceDbAuthzEngineTest test
mvn -pl auth-platform-sdk -Dtest=CheckAccessAspectTest test
mvn -pl auth-platform-sdk -Dtest=RemoteAuthzEngineHttpTest test
mvn -pl auth-platform-server -Dtest=AuthzControllerFacadeTest test
mvn -pl auth-platform-server -Dtest=ZedTokenWatermarkTest test
mvn -pl auth-platform-server -Dtest=AuthzServerSecurityFilterBoundaryTest test
mvn -pl auth-platform-admin -Dtest=AdminControllerTest test
mvn -pl auth-platform-admin -Dtest=InMemoryAuditStoreTest test
mvn -pl auth-platform-admin -Dtest=CasdoorClientTest test
mvn -pl auth-platform-admin -Dtest=ReconcileJobTest test
mvn -pl auth-platform-admin -Dtest=CasdoorSyncControllerTest test
mvn -pl auth-platform-admin -Dtest=SyncServiceBoundaryTest test
mvn -pl auth-platform-protocol -Dtest=ProtocolValueObjectsTest test
```

这些测试不走应用启动/internal JWT，不要求 `INTERNAL_JWT_SECRET`。若后续加入 server/admin application smoke test，先设置 ≥32 字节：

```bash
export INTERNAL_JWT_SECRET='replace-with-at-least-32-bytes-secret'
```

loopback `HttpServer` 属单元级 contract stub，不命名 `*IT`；真正连接 SpiceDB/Casdoor 的测试必须命名 `*IT`、加 `@Tag` 与环境变量门控，并默认不进入 `mvn test`。

## 7. 待验证信息

- SpiceDB 当前部署版本的 LookupSubjects 是否返回 `permissionship`、`excludedSubjectIds`，以及空结果究竟是零字节流还是显式消息；在没有版本化 API fixture 前，不臆造字段断言。
- Casdoor 生产响应中 group/role `owner`、`parentId`、role `users` 的跨 org 合法性。当前代码注释称形状来自实测，但没有版本契约 fixture。
- 是否正式支持 AOP SpEL/下标。当前源代码和注释只支持参数名。
- admin `subjects` DTO 有意丢弃 `SubjectRef.relation` 与否；目前 `SubjectView` 只有 type/id，若 userset 也会返回需产品确认。
- `AuthzServerSecurityFilter` 的 `/v1` 根路径策略与代理/容器 URI 规范化；Mock servlet 不能替代 Tomcat/proxy IT。
- protocol 追加测试依赖需要用户授权；本轮未改 POM。
- 现有 `JdbcAuditStoreTest` 使用 H2 `MODE=PostgreSQL`，而本次给定铁律要求新增 DB 测试用 `MODE=MySQL`。本轮不新增 DB 草案，既有测试不改；若统一旧测试，先验证 `TIMESTAMP WITH TIME ZONE` 和 retention SQL 在 MySQL mode 下是否兼容。

## 8. 资深测试架构师复审记录

已对本计划做第二遍反确认偏差复审并据此修正：

- 删除了“缺 permissionship 也返回资源”“bulk 漏项当 false”“null id 变字符串”的通过断言，只保留 TODO+issue。
- core happy path 同时断 HTTP path、Bearer、请求 JSON 和返回，不再只断布尔值；admin/controller 同时断端口实参和 DTO。
- 并发测试不依赖 sleep/执行顺序：使用 latch+Future；watermark 不错误声称字符串 token 可比较，audit 不断言具体保留线程。
- 所有 HTTP stub 使用端口 0 并 `@AfterEach stop`；所有 Mockito 为手工 mock；没有 Spring test slice/context/Mockito extension/field annotation。
- 没有虚构 `TenantContext`、SpEL parser、额外构造器或 package-private seam。
- protocol 的 test dependency 缺口已显式列为落地前置，未谎称当前可直接由 Maven 编译。
- 检查异常断言均使用 JUnit5 `assertThrows`；普通断言均为 AssertJ。

## 9. 最终验收清单

- [ ] 获准补 protocol test dependency 后，13 个草案类全部 testCompile；无生产代码为了测试而放宽可见性。
- [ ] `mvn test` 既有 38 tests 与所有新增测试全绿，无外部服务、固定端口、顺序/时间依赖。
- [ ] core 9 操作均有正常路径；check/checkBulk/lookup/stream/token 的关键异常闭合。
- [ ] Consistency/ZedToken 在 SDK→server→core 各层字段和值都被断言。
- [ ] AOP allow/deny/full/解析失败/依赖异常的目标方法调用次数正确。
- [ ] server bulk 漏项、core 流错误、Casdoor 缺 data 等在修复后启用 TODO 回归。
- [ ] Casdoor 多组织前缀、同步差量、threshold 等号、父边替换、webhook on/off 均覆盖。
- [ ] 并发测试稳定运行 50 次无抖动；无共享静态状态、ThreadLocal 或 H2 库名冲突。
- [ ] `03-suspected-issues.md` 每项已有负责人/决策；未决问题没有被写成“当前正确”的测试。
- [ ] 代码覆盖数字只作辅助；最终以矩阵关键行为、异常和副作用闭合为验收。

## 10. Claude 跨模型复核记录（2026-07-17）

对 Codex 蓝图逐条对照真实仓库核验。总体结论：**草案质量高、可直接落地**（回环 `HttpServer` 打桩、手工 Mockito、断言请求 JSON 而非仅返回值、疑似 bug 一律 `TODO(issue-)` 而不锁定为正确）。以下为对 17 条疑点的独立定级与三处事实确认。

### 事实确认（影响可行性）
- **`-parameters` 已开**：`spring-boot-starter-parent:3.3.5` 默认给 compiler 传 `-parameters`，故 AOP `getParameterNames()` 生产环境不返回 null；A01 的 NPE 分支只在 mock 下可复现，真实部署下判权链正常。
- **protocol 无 test 依赖属实（BLD01）**：`auth-platform-protocol/pom.xml` 无 junit/assertj，落地 `ProtocolValueObjectsTest` 前必须加 test-scope 依赖（POM 变更，需授权）。
- **本项目无 TenantContext、JdbcAuditStore 用 `MODE=PostgreSQL`**：技能通用铁律（H2 `MODE=MySQL`/`TenantContext.clear`）在本项目不适用，Codex 第 7 节已正确规避，落地时不强套。
- **C01 影响面**：`lookupResources` 仅被 admin 调试端点与 server facade 调用，**不在 `@CheckAccess` 主判权链**上，故 fail-open 危害小于"判权直接放行"，但仍是与 `hasPermission()` 严格语义不一致的真实 fail-open。

### 疑点定级（Claude 独立判断）
- **确认真问题（值得修）**：C01（lookup 空 permissionship 当放行，fail-open 不一致）、CAS02（Casdoor 删组/删部门后旧 tuple 永久残留＝撤权不彻底）、CAS01（Casdoor 空/畸形 200 被当空快照，阈值内仍会撤成员）、S01（server bulk facade 把引擎漏项静默降级为 deny，抵消 SDK 严格校验）、ADM01（先写 SpiceDB 后审计，审计失败则数据已变但无审计且客户端见 500）、C02（流式端点 error 项被静默跳过，返回可信部分结果）。
- **真实但低概率/防御性**：C03/C04（空 2xx、缺 token 当成功）、CAS03（信任完整 owner 段可跨 org 串写）、CAS04（org 未编码拼 query）、A01（null 实参变字面量 `"null"`，仍 fail-closed）、ADM02（record 无非空约束）、AUD01（ring 裁剪非原子，至多少留）、SEC01（精确 `/v1` 被放行，当前无 root handler）。
- **已知设计限制/非 bug**：S02（水位并发非因果单调——文档已声明单实例串行）、R01（reconcile 两阶段非原子——预期最终一致）、A02（不支持 SpEL/下标＝按设计只匹配参数名，非缺陷）。

上述定级不改变 Codex 的测试草案与 `TODO` 门禁；仅用于向用户呈报处置优先级。
