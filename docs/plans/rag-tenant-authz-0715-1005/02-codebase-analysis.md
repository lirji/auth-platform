# 代码库分析：检索、租户传播与 ReBAC 现状

## 1. 仓库与构建边界

本方案跨两个独立 Maven reactor：

- `/Users/liruijun/personal/LLM/langchain4j-platform`：业务平台；`knowledge-service` 是检索入口，`edge-gateway` 是外部身份入口，`platform-security` 负责内部 JWT/TenantContext。
- `/Users/liruijun/personal/LLM/auth-platform`：统一授权平台；SDK 走 HTTP 调 `auth-platform-server`，后者再走 SpiceDB HTTP API。

`knowledge-service/pom.xml` 已依赖 `com.lrj.authz:auth-platform-sdk:0.1.0-SNAPSHOT`。两个仓库不能靠一次 `-am` 自动联编；既有进度文档确认 langchain 项目使用 `/Users/liruijun/personal/repository` 作为本地 Maven 仓库，因此实施/CI 必须先发布或安装 auth-platform 制品，不能假设默认 `~/.m2`。

本轮只读分析，没有运行 Maven 测试，因为用户限定唯一可写目录为本规划目录，测试会写两个仓库的 `target/`。

## 2. 当前检索入口与调用链

### 2.1 HTTP 入口

`knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/KnowledgeQueryController.java`

- `query(KnowledgeQueryRequest)` 同时映射 `POST /rag/query` 与 `POST /knowledge/query`。
- 将四个请求字段传给 `KnowledgeQueryService.query(query, topK, minScore, category)`。
- 响应映射为 `KnowledgeQueryReply`，正文命中包含 `docId/text/source/visibility`。

`platform-protocol/.../KnowledgeQueryRequest.java` 只有四个字段，没有 tenantId；这是可复用的安全边界，无需改协议。

### 2.2 检索编排

`KnowledgeQueryService.query` 的实际顺序：

```text
TenantContext.current().tenantId()
  -> 计算 limit / poolLimit
  -> QueryExpander
  -> RetrievalRequest(query, variants, tenantId, category, poolLimit, ...)
  -> VectorRetrievalSource
  -> InMemoryKeywordRetrievalSource
  -> extra RetrievalSource（当前含 ES）
  -> GraphRetrievalSource
  -> HybridFusionService.fuse
  -> KnowledgeQueryService.filterReadable
  -> Reranker.rerank(query, authorizedCandidates, limit)
  -> KnowledgeQueryReply
```

关键事实：授权已经落在正确的“融合后、rerank 前”位置；当前缺口不是落点，而是生产接线、强约束、候选池、协议校验和端到端发布门。

### 2.3 调用方

公开协议不变时，以下调用方不需要改请求：

- `conversation-service/.../HttpKnowledgeClient.java` 与 `RagPromptAugmenter.java`
- `agent-service/.../HttpKnowledgeClient.java` 与 `RagSearchAction.java`
- `channel-service/.../DingtalkKnowledgeClient.java`
- `eval-service/.../HttpRetrievalClient.java`
- 展示前端和 `deploy/*.sh` 中对 `/rag/query` 的调用

服务间调用使用 `OutboundTenantForwarder` 传播内部 JWT；因此最终主体仍是最初 Casdoor 请求的 `sub`，不是中间服务账号。`eval-service` 当前也存在 API key 路径，属于灰度/兼容路径，不是 Casdoor-only 验收路径。

## 3. 当前租户隔离

### 3.1 身份到 TenantContext

真实链路如下：

```text
Authorization: Bearer <Casdoor JWT>
  -> CasdoorTokenExchangeFilter.filter（order=-120）
  -> ReactiveJwtDecoder：签名 + timestamp + issuer + audience
  -> exchangeAndForward：claim owner、sub
  -> InternalToken.mint(Tenant(owner, sub, scopes))
  -> X-Internal-Token
  -> knowledge-service InternalTokenAuthFilter.resolve
  -> TenantContext(tenantId=owner, userId=sub, scopes)
```

可复用实现：

- `CasdoorDecoderConfig.casdoorJwtDecoder` 已对 issuer/audience 空配置 fail-fast。
- `CasdoorTokenExchangeFilter` 已剥离外部 Authorization、API key 与伪造的内部头。
- `InternalToken.mint/verify` 使用 `sub=tenantId`、`uid=userId`、`scopes`。
- `KnowledgeAccessConfig.subjectResolver` 已返回 `SubjectRef.user(TenantContext.current().userId())`。

现有限制：

- `edge.casdoor.enabled` 默认 false。
- `tenantClaim` 当前可配置，虽默认 `owner`，但硬规则尚未固化。
- 验签失败会透传 session/API-key filter，这是 dual 灰度语义，不是 Casdoor-only。
- `InternalTokenAuthFilter` 对缺失/无效内部 JWT 不主动 401，而是留下 anonymous；生产要靠边缘/网络边界并关闭 direct API-key fallback。

### 3.2 ES 过滤

`ElasticsearchEsGateway.search(tenantId, category, queryText, limit)` 已构造：

```json
{
  "query": {
    "bool": {
      "must": {"match": {"text": "..."}},
      "filter": [
        {"term": {"tenantId": "<tenantId>"}},
        {"term": {"category": "<optional>"}}
      ]
    }
  }
}
```

`tenantId` 在 mapping 中为 `keyword`，符合精确 `term` 语义。删除也使用 tenantId/docId 双 term。

现有限制：

- 没有空 tenant 的 fail-fast 校验。
- 现有 `EsKeywordRetrievalSourceTest` 只断言 tenant 参数传给 fake gateway，没有直接验证真实 gateway 生成的 JSON 必含 tenant term。
- `ElasticsearchEsGateway` 把 JSON 构造与网络调用写在同一方法内，不便做纯单元安全断言。
- ES 关闭时仍有向量/内存关键词/Graph 路径；最终文档级授权必须覆盖融合结果，不能只包 ES。

### 3.3 其他检索源

- `VectorRetrievalSource.searchTenant` 同时选择 per-tenant store，并加 metadata `tenantId` filter。
- `KeywordSearchService.search` 从 `TenantContext` 读取租户，只读 `DocumentMirror.all(tenantId)`。
- `GraphSearchService.query` 从 `TenantContext` 取租户；但 `GraphRetrievalSource` 产出的 `docId` 为 null，当前 enforce 会丢弃。
- `PublicKb` 可额外查询 `__public__` 分区，`shared=true` 当前绕过 ReBAC；默认关闭。

## 4. 当前文档级 ReBAC

### 4.1 读路径

`KnowledgeQueryService.filterReadable` 已完成：

1. 排除 public/shared 和 null docId；
2. 用 `LinkedHashSet` 按 docId 去重；
3. 调 `KnowledgeAuthz.filterReadable(tenantId, userId, docIds)`；
4. `enforce` 只保留 public 或 readable 中的 doc；null docId fail-closed；
5. `shadow/disabled` 返回原候选。

`RealKnowledgeAuthz.filterReadable` 已把 docId 映射为 `ResourceRef.of("document", KnowledgeResourceIds.document(t,d))`，调用：

```text
engine.checkBulk(SubjectRef.user(userId), "view", resources, Consistency.fullyConsistent())
```

依赖错误语义已经是 shadow fail-open、enforce fail-closed，并有 `knowledge.authz.decisions` counter。

### 4.2 SDK 与 server

```text
RealKnowledgeAuthz
 -> AuthzEngine（SDK 自动装配 RemoteAuthzEngine）
 -> POST auth-platform-server /v1/check-bulk
 -> AuthzController.checkBulk
 -> SpiceDbAuthzEngine.checkBulk
 -> POST SpiceDB /v1/permissions/checkbulk
```

可复用：

- `RemoteAuthzEngine` 已支持 service Bearer token、连接/读取超时。
- server `AuthzServerSecurityFilter` 可保护 `/v1/**`，且 enabled+空 token 拒绝启动。
- `SpiceDbAuthzEngine` 使用真正的 bulk API，不是 N 次串行 check。
- `knowledge.zed` 已定义 organization/space/folder/document 的层级授权。

现有限制：

- AP server 安全开关默认 false；compose/Helm 尚未把 credential 接到 knowledge-service。
- SDK 根据响应数组下标映射结果，对缺失/额外/资源错位缺少显式协议校验。
- 当前每次使用 `FULLY_CONSISTENT`，正确但需压测延迟/吞吐。
- topK 与候选数没有授权专用上限；未授权高分文档可能导致 underfill。

### 4.3 写路径与单资源路径

`DocumentService` 已在新建后调用 `onDocumentCreated`，写 owner 与 default parent space；删除先撤关系再删业务数据。list/get/delete/同名覆盖也已接入 ReBAC。

`@CheckAccess` 当前不是查询过滤器，而用于 enforce-only 的分享操作：

- `KnowledgeAccessApplicationService.shareDocument/unshareDocument`
- `@CheckAccess(permission="edit", resourceType="document", resourceIdParam="documentResourceId", fullyConsistent=true)`
- `DocumentShareController` 由服务端构造完整资源 ID

这套 AOP 能力可复用，但集合检索必须继续使用编程式 checkBulk，不能在每个 hit 上逐条 AOP。

写路径仍有跨存储半失败窗口：向量/mirror/ES/graph/registry 已写后，SpiceDB 写失败会抛出，但已落业务数据；重试时 existing 分支可能不再补 owner。它主要造成可用性/孤儿数据，不会在 enforce 下泄露，因为缺关系默认 deny；切 enforce 前仍应通过对账/backfill 处理。

## 5. Casdoor role/group 与 SpiceDB 现状

### 5.1 Role

`deploy/casdoor-seed.sh` 把 5 个 role 与 11 个 scope permission 写入 Casdoor。token 的 `permissions[].name` 经 edge allowlist 写进内部 JWT。role 决定能否调用 chat/ingest 等能力，不直接表示某文档 view。

### 5.2 Group

`CasdoorClient.groupMembers/groupNames` 读取单个 organization 的用户/组；`GroupSyncService.sync` 计算 desired-current 差集并 TOUCH/DELETE：

```text
group:<shortName>#member@user:<Casdoor id/sub>
```

现有限制：

- `shortName()` 丢弃 organization/path，跨租户同名组会合并；不满足本任务多租户规则。
- 配置一次只支持一个 organization。
- `synchronized` 只保护单 JVM，不能防多副本同时 reconcile。
- current 通过 `lookupSubjects(..., permission="member")` 读展开结果，不是直接 tuple 清单；嵌套组场景有误删/漏补风险。
- `auth-platform-admin/application.yml` 尚未给出 `authz.casdoor.*` 配置块。
- group 同步只建 membership，不自动把 group 绑定到 `space:<tenant>_default#viewer`；该 binding 需显式管理。

## 6. 数据模型

### 6.1 现有业务/索引模型

- `DocumentInfo(docId, tenantId, displayName, contentType, sizeBytes, segmentCount, version, uploadedAt, category)`；没有 owner/space/folder/authz state。
- Redis registry：`rag:docs:<tenantId>` hash，field=docId。
- ES：每个 chunk 一条 `EsSegmentDocument`，稳定 ID `tenantId/docId/index`。
- 向量/关键词/图谱均携带 tenant metadata 或按租户分区。

### 6.2 现有授权模型

核心 tuple：

```text
document:<t>_<d>#owner@user:<sub>
document:<t>_<d>#parent_space@space:<t>_default
document:<t>_<d>#viewer@user:<sub>
space:<t>_default#viewer@group:<group>#member
group:<group>#member@user:<sub>
```

目标需要把后两类 group ID 改为 `<t>_<group>`。SpiceDB 无 SQL migration；“迁移”是 schema/relationship 的幂等写入与旧 tuple 清理。

## 7. 现有测试与缺口

可复用：

- `RealKnowledgeAuthzTest`：enforce/shadow、fully-consistent、故障 fail-open/closed。
- `KnowledgeAuthzIntegrationTest`：真实 AP/SpiceDB 下 owner/deny/grant/revoke、list/get/delete/overwrite。
- `CheckAccessShareTest`：AOP 真代理与精确 subject/resource/consistency。
- `TenantIsolationTest`：向量/关键词租户隔离。
- `EsKeywordRetrievalSourceTest`、`KnowledgeQueryServiceEsFusionTest`：ES 参数与融合。
- edge 的 `CasdoorTokenExchangeFilterTest`、`CasdoorJwksIntegrationTest`：owner/sub 与真实 JWKS。
- AP 的 `spicedb-smoke.sh`、`server-smoke.sh`。

缺口：

1. `KnowledgeAuthzIntegrationTest` 在服务不可达时用 assumption 跳过，不是可靠发布门。
2. 没有单测直接检查 ES request JSON 中 tenant term 永远存在。
3. 没有测试证明多 chunk 同 doc 只产生一个 checkBulk resource。
4. 没有完整的“真实 Casdoor token → edge → knowledge → ES → AP → SpiceDB”测试。
5. 没有 tenant A/B 同名 group 的隔离测试。
6. 没有 underfill、批量上限、超大 topK、checkBulk 畸形响应测试。

## 8. 受影响文件清单

以下“新增”均是计划项，不代表当前已存在。

### 8.1 langchain4j-platform：核心改动

| 文件 | 计划 |
|---|---|
| `knowledge-service/.../KnowledgeQueryService.java` | 固化可信 context 快照、topK/候选上限、安全乘法、授权 overfetch、授权指标 |
| `knowledge-service/.../es/ElasticsearchEsGateway.java` | tenant 非空校验；把 search body 构造提成可测试方法/组件；保留强制 term |
| `knowledge-service/.../es/EsGateway.java` | 强化端口契约，声明 tenant 必填且不得省略 |
| `knowledge-service/.../authz/RealKnowledgeAuthz.java` | 批次上限、严格响应覆盖检查、underfill/error 指标所需结果信息 |
| `knowledge-service/.../authz/KnowledgeAuthzConfig.java` | 统一使用校验过的 authz properties，避免非法 mode 静默退化 |
| `knowledge-service/.../authz/RagAuthzProperties.java` | **计划新增**：mode、candidateMultiplier、maxCandidates、bulkSize 等 |
| `knowledge-service/.../authz/KnowledgeAccessConfig.java` | enforce AOP subject resolver 与校验后 mode 统一装配 |
| `knowledge-service/.../authz/KnowledgeAccessApplicationService.java` | enforce 分享应用服务装配条件与统一 mode 对齐 |
| `knowledge-service/.../controller/DocumentShareController.java` | enforce-only 分享端点装配回归 |
| `knowledge-service/.../controller/AuthzExceptionHandler.java` | enforce 异常映射装配回归 |
| `knowledge-service/src/main/resources/application.yml` | 新配置默认值和注释；默认仍 disabled |
| `edge-gateway/.../CasdoorTokenExchangeFilter.java` | Casdoor-only 行为；tenant 固定 owner；失败不落 legacy |
| `edge-gateway/.../CasdoorDecoderConfig.java` | 从 enabled 条件迁移到 mode 条件，保持 issuer/audience/JWKS fail-fast |
| `edge-gateway/.../CasdoorSecurityProperties.java` | 增加 dual/only 模式或等价严格开关；约束 tenant claim |
| `edge-gateway/src/main/resources/application.yml` | 灰度配置与启动校验 |

### 8.2 langchain4j-platform：测试与部署

| 文件 | 计划 |
|---|---|
| `knowledge-service/src/test/.../es/ElasticsearchEsGatewayTest.java` | **计划新增**：JSON tenant term 安全单测 |
| `knowledge-service/src/test/.../KnowledgeQueryServiceAuthzTest.java` | **计划新增**：去重、顺序、null docId、underfill、批量失败 |
| `knowledge-service/src/test/.../authz/RealKnowledgeAuthzTest.java` | 扩充批次/协议异常/租户资源 ID |
| `knowledge-service/src/test/.../authz/KnowledgeAuthzIntegrationTest.java` | required profile 不可 skip；ES+ReBAC 组合 |
| `edge-gateway/src/test/.../CasdoorTokenExchangeFilterTest.java` | 增加 only 模式、伪造 tenant、无 owner、legacy 拒绝 |
| `deploy/docker-compose.yml` | knowledge 注入 RAG_AUTHZ/AUTHZ_SERVER；edge 严格模式；依赖说明 |
| `deploy/helm/platform/values.yaml`、`templates/secret.yaml` | AP URL、mode、credential、Casdoor 配置 |
| `deploy/smoke-rag-tenant-authz.sh` | **计划新增**：跨服务 required E2E |

### 8.3 auth-platform

| 文件 | 计划 |
|---|---|
| `auth-platform-admin/.../casdoor/CasdoorClient.java` | 保留完整 organization/group 上下文，不以全局短名作为资源 ID |
| `auth-platform-admin/.../casdoor/GroupSyncService.java` | 遍历 organizations；写 `<tenant>_<group>`；用 readRelationships 对账 direct tuple；分批、dry-run/删除阈值、多副本保护 |
| `auth-platform-admin/.../casdoor/CasdoorProperties.java` | group ID 策略、批次/删除阈值、organizations 列表与旧单值迁移 |
| `auth-platform-admin/src/main/resources/application.yml` | 补 `authz.casdoor.*`，默认仍关 |
| `auth-platform-admin/src/test/.../casdoor/GroupSyncServiceTest.java` | **计划新增**：跨租户同名组、幂等、失败不误删 |
| `auth-platform-sdk/.../RemoteAuthzEngine.java` | 严格校验 check-bulk cardinality/resource 对齐，异常抛出 |
| `auth-platform-sdk/src/test/.../RemoteAuthzEngineTest.java` | **计划新增**：畸形响应 fail-closed |
| `deploy/rag-authz-fixture.sh` | **计划新增或并入 E2E**：group membership、space binding、文档 tuple fixture |

### 8.4 可选 category→folder

若业务确认启用，再改：

- `DocumentService.java`：创建/更新时传递 category 授权投影。
- `KnowledgeAuthz.java`、`RealKnowledgeAuthz.java`：写/迁移 `folder#parent_space` 与 `document#parent_folder`。
- `KnowledgeResourceIds.java`：**计划新增**稳定 folder/category 编码方法。
- 对应单元/集成测试与 backfill 工具。

不建议在未确认 category 稳定性前给 `DocumentInfo` 或数据库新增字段。
