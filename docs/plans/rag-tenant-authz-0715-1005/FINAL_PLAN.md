# langchain4j-platform RAG 多租户 + ReBAC 最终实施计划

> 文档性质：可直接交给开发 Agent 执行的跨仓库实施计划。本文把“当前存在”与“计划新增/修改”明确分开；所有现状类名、方法和数据结构均已从实际代码核对。当前默认仍为 `app.rag.authz.mode=disabled`。

## 1. 背景

`langchain4j-platform` 的文本 RAG 已是多源检索：向量、进程内关键词、Elasticsearch BM25、GraphRAG 经融合与可选 rerank 返回正文。租户隔离也不是空白：查询从 `TenantContext` 取租户，向量/关键词按租户分区，ES 已使用 `term tenantId`。

知识服务还已接入 auth-platform SDK：融合后按 docId 去重，调用 SpiceDB `checkBulk` 做 document view 后置过滤；文档新建/删除也会写删关系。edge 已实现 Casdoor token 的 JWKS 验签与 `owner/sub` 到内部 JWT 的换发。

但这些能力尚未构成生产闭环：授权默认 disabled；edge Casdoor 默认关闭且仍是 dual 语义；compose/Helm 没接 AP server；group 使用全局短名；历史文档关系不完整；真实集成测试可跳过；候选池与 bulk 缺少安全上限。因此本计划重点是“固化不变量 + 数据准备 + 部署接线 + 灰度验证”，而不是重写检索。

## 2. 目标与非目标

### 2.1 目标

1. Casdoor 最终验收路径中，tenantId 只来自已完整验签 access token 的 `owner`，userId 只来自 `sub`。
2. `/rag/query` 不接受 tenant 参数；ES 每次查询强制 `term tenantId=<owner>`。
3. 融合候选在返回正文前执行：top-K/有界候选 → docId 去重 → `checkBulk(document:<tenant>_<docId>#view@user:<sub>)` → 丢弃判否/缺失/错误。
4. Casdoor role 提供粗粒度 scope，tenant-scoped group 通过 default space/folder/document 关系提供 ReBAC view。
5. 新用户完成 Casdoor 创建、role/group 分配、登录换内部 JWT 后，只能查询本租户且拥有 view 的文档。
6. `disabled → shadow → enforce` 可观测灰度，授权故障 enforce fail-closed，可配置回滚。

### 2.2 非目标

- 不修改 `KnowledgeQueryRequest/Reply` 的公开结构。
- 不让客户端传 tenantId；单个 token 仍只承载一个 `owner`，不支持“同一用户在一次会话中切换 active tenant”。同步侧必须支持配置多个 Casdoor organization，这与 token 的单 owner 语义不冲突。
- 不把 role/scope 当成 document relation。
- 不让 knowledge-service 直连 SpiceDB gRPC；继续走 SDK HTTP → AP server → SpiceDB HTTP。
- 不在核心发布中重做 GraphRAG/图片资源溯源；无 docId 继续 enforce 丢弃。
- 不在首期物化 ACL 到 ES，也不引入完整授权事件投影系统。
- category→folder 是可选后续阶段，不阻塞核心闭环。

## 3. 已确认的业务规则

1. 外部查询 DTO 只有 `query/topK/minScore/category`；tenant 必须由安全上下文注入。
2. Casdoor 身份等式必须成立：`token.owner = internal JWT sub = TenantContext.tenantId`；`token.sub = internal JWT uid = TenantContext.userId = SpiceDB user id`。
3. ES tenantId 是 keyword 字段，用 `term` 而不是 `match`。
4. document 资源 ID 只由 `KnowledgeResourceIds.document` 生成 `<tenantId>_<docId>`。
5. 文档 view 以 `knowledge.zed` 为准，可由 direct relation 或 parent folder/space 继承。
6. role 只产生粗粒度 scopes；group membership 与 group→space/folder relation 才产生文档 view。
7. 融合后按 docId 判权，reranker 只能看到已授权候选。
8. `deny`、bulk missing、协议异常与依赖故障在 enforce 都不能返回候选正文。
9. `shadow` 只观测、不拦截，不是安全完成态。
10. strict E2E 保持 `RAG_PUBLIC_ENABLED=false`；当前 shared bypass 不满足“本租户且 view”。
11. 历史文档无可信 owner 时不得伪造 owner；只补可证明的 parent relation 或按授权 manifest 写关系。
12. group ID 必须 tenant-scoped：`<tenantId>_<groupName>`。

## 4. 当前代码与调用链分析

### 4.1 认证与租户

```text
Casdoor JWT
 -> CasdoorTokenExchangeFilter.filter
 -> CasdoorDecoderConfig / ReactiveJwtDecoder
 -> exchangeAndForward(owner, sub, permissions)
 -> InternalToken.mint
 -> X-Internal-Token
 -> InternalTokenAuthFilter.resolve
 -> TenantContext
```

已存在：issuer/audience/timestamp 校验、Authorization/API key 剥离、内部头防伪、owner/sub 映射。缺口：默认关闭、tenant claim 可配置、验签失败会落 legacy、下游匿名请求不主动 401。

### 4.2 检索

```text
KnowledgeQueryController.query
 -> KnowledgeQueryService.query
 -> tenant = TenantContext.current().tenantId()
 -> RetrievalRequest
 -> Vector / Keyword / ES / Graph
 -> HybridFusionService.fuse
 -> KnowledgeQueryService.filterReadable
 -> RealKnowledgeAuthz.filterReadable
 -> RemoteAuthzEngine /v1/check-bulk
 -> AuthzController.checkBulk
 -> SpiceDbAuthzEngine /v1/permissions/checkbulk
 -> reranker.rerank
 -> KnowledgeQueryReply
```

ES `ElasticsearchEsGateway.search` 当前已经把 tenant term 放进 `bool.filter`。`KnowledgeQueryService.filterReadable` 当前也已用 `LinkedHashSet` 按 docId 去重，且 `RealKnowledgeAuthz` 使用 fully-consistent checkBulk。缺口主要是不可直接测试的 ES JSON、候选/批次上限、SDK 响应校验、部署与 required E2E。

### 4.3 文档与关系

新文档当前写：

```text
document:<tenant>_<doc>#owner@user:<TenantContext.userId>
document:<tenant>_<doc>#parent_space@space:<tenant>_default
```

分享端点已通过 `@CheckAccess(edit, fullyConsistent=true)` 保护。list/get/delete/overwrite 也已接入编程式 ReBAC。

跨存储无事务：业务数据先写后写关系可能产生“有数据、无关系”的安全隐藏。此问题不造成 enforce 泄露，但会造成搜不到，必须用 shadow/backfill/reconcile 和告警治理。

### 4.4 Casdoor group

`CasdoorClient` 读取用户 `id` 与 groups；`GroupSyncService` 差量写 membership。但当前用 `shortName` 作为全局 group ID，且只创建 membership，不创建 group→default space binding。两者是本任务端到端的实际缺口。

## 5. 候选方案对比与评分

评分 1–5，越高越有利；风险/复杂度/测试难度/回滚成本采用“越低分越高”。完整论证见 `03-solution-a.md` 至 `05-solution-c.md`、`comparison.md`。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 加权 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 融合后 checkBulk 生产化 | 5 | 5 | 5 | 4 | 3 | 4 | 5 | **4.55** |
| B lookup 前置收窄 + checkBulk | 5 | 3 | 2 | 3 | 4 | 2 | 3 | 3.45 |
| C ES ACL 投影 + checkBulk | 4 | 2 | 1 | 2 | 5 | 1 | 2 | 2.65 |

## 6. 最终方案及选择原因

### 6.1 选择

采用 A 为安全主链，并吸收 B/C 的部分优点：

- 保留现有融合后 checkBulk，不重写 retrieval source。
- 用有界 candidate multiplier、maxTopK、maxCandidates、bulkSize 缓解 underfill/DoS，而不是首期引入 lookup 大集合。
- 记录 underfill、deny/error、checkBulk latency、group reconcile/backfill 状态，但不复制 ACL 到 ES。
- 增加 Casdoor-only 验收 profile、tenant-scoped group、历史关系准备与不可跳过 E2E。

### 6.2 选择原因

1. 与用户指定的“ES top-K → docId 去重 → checkBulk → 丢弃判否”完全一致。
2. 当前代码已经具备主链，改动最小、事实基础最强。
3. 新 retrieval source 天然受统一后置过滤保护，不会出现只保护 ES 的局部方案。
4. 不新增授权副本，撤权正确性直接由 fully-consistent SpiceDB 判定保证。
5. 回滚只需把 `RAG_AUTHZ_MODE` 从 enforce 切回 shadow/disabled；关系数据可以保留。

### 6.3 已知弱点

1. 每次查询增加 AP/SpiceDB 往返，P95 会升高；首期以正确性优先，压测后再调一致性。
2. 有界 overfetch 不能完全消除 underfill。
3. AP/SpiceDB 故障时 enforce 安全但可能“全空”；必须有高优告警。
4. 文档关系双写无事务状态机，缺关系会隐藏数据；历史 backfill/reconcile 是上线依赖。
5. current public/shared bypass 不在核心方案内，strict profile 必须关闭。
6. group 同步需要遍历显式配置的 organization 列表；列表越大，对 Casdoor API 分页、限流和整轮完整性判断的要求越高。单个用户跨 organization 的 active-tenant 选择仍不在本期。
7. knowledge-service 使用现有 AP service credential 同时执行 check 与关系写入；若该凭据泄露，影响面大。首期必须配合 Secret 隔离、网络策略与轮换，后续再按 AP 能力模型拆分 read/check 与 relationship-write 权限。

## 7. 目标架构与核心时序

```text
Browser
  | Casdoor access token (owner=t, sub=u)
  v
edge-gateway [verify + Casdoor-only]
  | X-Internal-Token(sub=t, uid=u)
  v
knowledge-service
  |-- ES _search: term tenantId=t
  |-- other tenant-scoped sources
  |-- fusion
  |-- unique docIds
  `-- checkBulk(user:u, view, [document:t_d...])
         |
         v
    auth-platform-server -> SpiceDB
         |
         `-- allowed map
  -> drop denied/missing/error -> rerank -> response

Casdoor groups -> auth-platform-admin reconcile
  -> group:t_group#member@user:u
  -> explicit space:t_default#viewer@group:t_group#member
```

## 8. 精确修改清单

以下新类/文件均标记“计划新增”。

### 8.0 执行路径索引

下表路径均为对应仓库根目录的相对路径；“入口”只列已从代码确认存在的方法，计划新增的方法在后续小节单独标注。

| 仓库 | 精确文件 | 现有类/入口 |
|---|---|---|
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java` | `KnowledgeQueryService.query`、`filterReadable` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/ElasticsearchEsGateway.java` | `ElasticsearchEsGateway.search`、`deleteByDoc` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsGateway.java` | `EsGateway.search`、`deleteByDoc` 契约 |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/EsKeywordRetrievalSource.java` | `EsKeywordRetrievalSource.retrieve` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/KnowledgeAuthzConfig.java` | `shadowKnowledgeAuthz`、`enforceKnowledgeAuthz` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/RealKnowledgeAuthz.java` | `filterReadable`，并回归 `onDocumentCreated/onDocumentDeleted/checkDocument/grantDocumentViewer/revokeDocumentViewer` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/RagAuthzProperties.java` | **计划新增类** |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/KnowledgeAccessConfig.java` | `subjectResolver` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/KnowledgeAccessApplicationService.java` | `shareDocument`、`unshareDocument` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentShareController.java` | `share`、`unshare`、`resourceId` |
| langchain4j-platform | `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/AuthzExceptionHandler.java` | `onDenied` |
| langchain4j-platform | `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorSecurityProperties.java` | 现有 properties，计划增加 mode |
| langchain4j-platform | `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java` | `filter`、`exchangeAndForward` |
| langchain4j-platform | `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorDecoderConfig.java` | `casdoorJwtDecoder` |
| auth-platform | `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorClient.java` | `groupMembers`、`groupNames`（计划改为显式 organization 参数） |
| auth-platform | `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/GroupSyncService.java` | `sync` |
| auth-platform | `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorProperties.java` | 现有 properties，计划增加 organizations/安全阈值 |
| auth-platform | `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/RemoteAuthzEngine.java` | `checkBulk`，并复用现有 `readRelationships` |

配置、部署与测试文件的精确路径列在 §8.1–§8.5；实现 Agent 开始前应以本表为主索引，不应另造并行入口。

### 8.1 langchain4j-platform：knowledge-service

#### `KnowledgeQueryService.java`

修改 `query(...)`：

1. 方法开始一次读取 `TenantContext.current()`，校验 tenantId/userId 非 anonymous/空白；构造请求期不可变快照。
2. `limit` 增加 `maxTopK` 上限。
3. 用 long/饱和算法计算 `poolLimit`：取 `topK × max(reranker.retrieveMultiplier(), authzCandidateMultiplier)`，不得把两个倍数相乘；最后限制到 `maxCandidates`。这样同时满足 rerank 与授权过滤余量，又避免候选爆炸。
4. 保留 `fusion.fuse -> filterReadable -> reranker.rerank` 顺序。
5. 记录 candidates、uniqueDocs、allowedDocs、returned、underfill、mode；指标 tag 不放 tenant/user/docId。

修改 `filterReadable(...)`：

- 参数显式接收同一请求快照中的 tenantId/userId，避免方法内第二次读取上下文。
- 保留 `LinkedHashSet` 去重和 null docId fail-closed。
- strict profile 的首选控制是在启动时校验 `strictTenantOnly=true` 与现有 `app.rag.public.enabled=true` 互斥；运行时若因配置热变更或异常数据仍出现 shared candidate，则记录配置违规并拒绝。非 strict/旧 public profile 保持既有兼容行为。

#### `authz/RagAuthzProperties.java`（计划新增）

`@ConfigurationProperties("app.rag.authz")`，字段建议：

- `AuthzMode mode = DISABLED`
- `int candidateMultiplier = 3`
- `int maxCandidates = 200`
- `int bulkSize = 100`
- `boolean strictTenantOnly = false`（strict E2E/prod 置 true）

使用校验约束：multiplier≥1、maxCandidates≥1、bulkSize∈[1,maxCandidates]。非法 mode/数值启动失败，不能静默 disabled。

#### `authz/KnowledgeAuthzConfig.java`

- 启用并注入 `RagAuthzProperties`。
- 单一工厂按 enum 返回 Noop/Real，或保持互斥 bean 但必须依赖已校验 properties；避免字符串拼写错误无 bean而静默放行。
- shadow/enforce 创建 Real 时传 bulkSize 与 MeterRegistry。
- 增加跨配置启动校验：`strictTenantOnly=true` 时现有 `app.rag.public.enabled` 必须为 false，且 mode 不能是 disabled；冲突直接拒绝启动。

#### `authz/RealKnowledgeAuthz.java`

修改 `filterReadable(...)`：

- 校验 tenantId、userId、docId。
- 按 bulkSize 稳定分批；每批仍用 `Consistency.fullyConsistent()`。
- missing/false 不加入 readable；协议异常由 SDK 抛出并走现有 shadow/enforce 错误语义。
- 增加 checkBulk latency/batch/error 指标。

保留 `onDocumentCreated/onDocumentDeleted/checkDocument/grant/revoke` 现有资源编码和一致性。

#### `es/ElasticsearchEsGateway.java`

- `search(...)`、`deleteByDoc(...)` 在网络调用前拒绝 null/blank tenantId。
- 把 search JSON 生成提取为 package-private 静态方法或计划新增 `EsQueryBodyFactory`；其唯一入口必须无条件添加 `term tenantId`。
- `_source` 读取时可读取 tenantId 并校验等于请求 tenant；不等时丢弃并记录 isolation violation。若选择扩充内部 `EsSearchHit`，它仅是 knowledge 内部 record，不改外部协议。

#### `es/EsGateway.java`、`search/EsKeywordRetrievalSource.java`

- Javadoc/参数契约明确 tenant 必填、由可信上下文产生。
- source 不允许调用无 tenant 的 gateway search。

#### `src/main/resources/application.yml`

保留：

```yaml
app.rag.authz.mode: ${RAG_AUTHZ_MODE:disabled}
```

新增建议配置：

```yaml
app.rag.authz.candidate-multiplier: ${RAG_AUTHZ_CANDIDATE_MULTIPLIER:3}
app.rag.authz.max-candidates: ${RAG_AUTHZ_MAX_CANDIDATES:200}
app.rag.authz.bulk-size: ${RAG_AUTHZ_BULK_SIZE:100}
app.rag.authz.strict-tenant-only: ${RAG_AUTHZ_STRICT_TENANT_ONLY:false}
app.rag.query.max-top-k: ${RAG_QUERY_MAX_TOP_K:50}
```

以上数值是计划默认值，需性能测试确认；安全上限必须存在。

生产同时设置 `platform.security.allow-api-key-fallback=false`，并通过网络策略禁止绕过 edge 直连业务端口。

### 8.2 langchain4j-platform：edge-gateway

#### `CasdoorSecurityProperties.java`

计划新增 `mode=disabled|dual|only`（可保留 `enabled` 作为兼容迁移，但最终以 mode 为准）：

- disabled：不启用 Casdoor。
- dual：验签失败可进入 legacy，仅灰度。
- only：非 open path 必须有效 Casdoor token，否则 401。

tenant claim 固定 `owner`。可删除 `tenantClaim` 配置；若为兼容保留，则 only 模式启动时必须断言值为 `owner`。

#### `CasdoorTokenExchangeFilter.java`

修改 `filter(...)` 与 `exchangeAndForward(...)`：

- only 模式无 Bearer、decode 失败、缺 owner/sub 均 401。
- dual 模式保持当前 fallback，供回滚窗口。
- 直接读取 `jwt.getClaimAsString("owner")`；不读取任何租户 header/query。
- 保留内部头/API key/Authorization 剥离。

#### `CasdoorDecoderConfig.java`、`application.yml`

- 延续 issuer/audience/JWKS fail-fast。
- `CasdoorDecoderConfig` 与 `CasdoorTokenExchangeFilter` 现有 `@ConditionalOnProperty(edge.casdoor.enabled=true)` 必须一并迁移为 mode 条件；不能只改属性类而导致 decoder/filter 未装配。
- 新增 `EDGE_CASDOOR_MODE`；最终验收用 `only`。
- Casdoor 大 token header 上限保持 32KB 或按实测调整。

#### `KnowledgeAccessConfig.java`、`KnowledgeAccessApplicationService.java`、`DocumentShareController.java`、`AuthzExceptionHandler.java`

- 这四处当前都以字符串条件 `app.rag.authz.mode=enforce` 装配；迁移到已校验 enum 后必须统一条件来源，保证 enforce 时四者同时存在，disabled/shadow 时分享写接口仍不暴露。
- `KnowledgeAccessConfig.subjectResolver()` 继续只从 `TenantContext.userId` 构造 `SubjectRef.user`；不读取请求体 subject 作为调用者身份。
- 增加 Spring context 测试覆盖三个 mode，防止 Real/Noop、AOP evaluator、controller 和 exception handler 发生部分装配。

### 8.3 auth-platform：admin group 同步

#### `CasdoorClient.java`

- 查询方法显式接收 organization，读取/返回 organization 与 group short name 的结构化组合，不再只向下游暴露全局 shortName map。
- Casdoor 请求使用 URI builder 编码 `owner` 查询参数，不继续字符串拼接 organization；避免特殊字符改变查询语义。
- subject 继续默认取 Casdoor user `id`（已验证等于 OIDC sub）。
- 若 Casdoor API 分页，完整拉取所有页；任一页失败，本轮 desired state 标记不完整。

#### `GroupSyncService.java`

修改 `sync()`：

- group resource ID 使用 `<organization>_<normalizedGroupName>`；统一 helper，禁止散落字符串拼接。
- 逐个遍历 `CasdoorProperties.organizations`，分别生成 desired/current 集合；某 organization 拉取不完整时，只禁止该 organization 的 DELETE，其他 organization 可继续 TOUCH，但整轮必须以 partial 状态告警。
- desired state 不完整时禁止 DELETE。
- TOUCH/DELETE 分批，设置删除比例/绝对数量熔断，支持 dry-run summary。
- 多副本仅允许一个执行者：首期可将 admin 部署为单副本；若必须多副本，增加外部 lease。Java `synchronized` 不能作为跨副本保证。
- current direct membership 使用现有 `AuthzEngine.readRelationships(RelationshipFilter.of("group", groupId, "member"))` 读取，不再用会展开继承的 `lookupSubjects(permission="member")` 作为删除依据；只删除 manifest 中本同步器管理的 direct `user` membership，未知 subject 类型不动。

#### `CasdoorProperties.java`、`application.yml`

把当前单值 `organization` 扩展为非空 `organizations` 列表；可在一个发布周期兼容旧单值并启动告警，但两者同时配置时拒绝启动。另补充并校验：subjectField=id、groupIdStrategy=tenant-prefixed、batchSize、deleteThreshold、dryRun、reconcile interval。默认 `enabled=false`、`reconcileEnabled=false`。

group ID 的 `<organization>_<group>` 只由 codec 生成、不得靠 `_` 反向 split；codec 对允许字符/长度做校验并附 version，迁移 manifest 保存原 organization/group，避免 `a_b+c` 与 `a+b_c` 这类拼接碰撞。具体转义/哈希算法属于阶段 1 待验证项，未确定前不得写生产 tuple。

#### default space binding

不按 group 名猜权限。通过现有 `AdminController.grant` 或受控 fixture 明确写：

```text
space:<tenant>_default#viewer@group:<tenant>_<configuredGroup>#member
```

生产绑定清单由业务审批；E2E 使用专用 `rag-readers` group。

### 8.4 auth-platform：SDK 严格响应

#### `RemoteAuthzEngine.java`

修改 `checkBulk(...)`：

- 校验 `results` 数量与 resources 一致。
- 校验每个结果中的 resource 与请求匹配；若 server DTO 当前已返回 resource，可直接使用，不只按下标盲配。
- 少项、多项、错位、空 body 抛协议异常；上游 enforce 会 fail-closed。

`AuthzController.checkBulk` 和 `SpiceDbAuthzEngine.checkBulk` 的公开协议/SpiceDB 调用不改，只补对应测试与必要的内部响应验证。

### 8.5 测试与部署文件

- 计划新增 `knowledge-service/src/test/java/com/lrj/platform/knowledge/es/ElasticsearchEsGatewayTest.java`。
- 计划新增 `knowledge-service/src/test/java/com/lrj/platform/knowledge/KnowledgeQueryServiceAuthzTest.java`。
- 扩展 `knowledge-service/src/test/java/com/lrj/platform/knowledge/authz/RealKnowledgeAuthzTest.java` 与 `KnowledgeAuthzIntegrationTest.java`。
- 扩展 `edge-gateway/src/test/java/com/lrj/platform/edge/CasdoorTokenExchangeFilterTest.java` 与 `CasdoorJwksIntegrationTest.java`。
- 计划新增 auth-platform 的 `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/GroupSyncServiceTest.java` 与 `auth-platform-sdk/src/test/java/com/lrj/authz/sdk/RemoteAuthzEngineTest.java`。
- `langchain4j-platform/deploy/docker-compose.yml` 给 knowledge 注入 RAG_AUTHZ/AUTHZ_SERVER，给 edge 注入 Casdoor mode；auth-platform 服务当前不在该 compose 内，必须写清外部地址/启动依赖。
- `langchain4j-platform/deploy/helm/platform/values.yaml` 增加非敏感 URL/mode，`templates/secret.yaml` 与 `templates/externalsecret-sample.yaml` 增加 `AUTHZ_SERVER_TOKEN`、Casdoor secret；避免把 service credential 注入所有不需要的服务，优先 knowledge 专属 Secret。
- 计划新增 `langchain4j-platform/deploy/smoke-rag-tenant-authz.sh`，见 test-plan。

## 9. 数据库、接口、配置与消息结构变更

### 9.1 数据库/索引

- 无 SQL 表新增或迁移。
- ES mapping 核心方案不新增 ACL 字段；现有 tenantId/docId/category keyword mapping 保留。
- Redis `DocumentInfo` JSON 核心方案不变。
- SpiceDB schema `knowledge.zed` 无需修改，已有 group/space/folder/document 能表达目标。
- 关系数据需要迁移/补齐：tenant-scoped group membership、default space binding、历史 document parent_space；旧全局短 group tuple 在验证新 tuple 后分批清理。

### 9.2 外部接口

- `/rag/query`、`/knowledge/query` 请求/响应无变更。
- `/v1/check-bulk` 契约字段不变，但 SDK 对响应执行严格校验。
- 继续复用 `/admin/grants` 创建 group→space binding；本期不强制新增业务 API。

### 9.3 配置

新增/调整：`EDGE_CASDOOR_MODE`、授权候选/批次/strict 配置、compose/Helm 的 `RAG_AUTHZ_MODE`、`AUTHZ_SERVER_URL/TOKEN`、admin Casdoor `organizations` sync 配置。默认仍是 disabled，不能因安装依赖自动启用。

### 9.4 Token/消息结构

- Casdoor access token 契约不变：读取 `owner/sub/permissions/groups`。
- 内部 JWT 结构不变：`sub=tenantId`、`uid=userId`、`scopes`。
- `KnowledgeQueryRequest/Reply` 不变。
- 无新 Kafka/消息队列结构。
- 新增的只是 SpiceDB relationships，其精确形态见 §3/§8。

## 10. 数据准备与迁移

### 10.1 盘点

按 tenant 导出：registry 中 docId/category、现有 document relations、Casdoor user sub/group、旧 group tuple、default space bindings。输出差异：无 parent_space、无 owner、未知 group、跨租户短名碰撞。

### 10.2 历史文档

1. 对能从 registry 证明 tenant/docId 的记录，幂等 TOUCH `document:<t>_<d>#parent_space@space:<t>_default`。
2. 只有可信 owner manifest 才写 owner；否则保持 unknown。
3. 根据审批的 group→space 清单写 viewer/editor binding。
4. shadow 查询对比 would-filter；不允许为了让差异归零给全体用户 blanket owner/viewer。

### 10.3 Group 迁移

1. 先写 `<tenant>_<group>` membership。
2. 按 organization 对账 Casdoor sub 数、SpiceDB direct membership 数、unmatched 数；current 以 `readRelationships` 的 direct tuple 为准。
3. 写新 group→space binding。
4. shadow 验证。
5. enforce 稳定后再删除旧全局短 group tuple；删除必须有 manifest、阈值与回滚文件。

## 11. 分阶段实施步骤与依赖关系

依赖顺序：阶段 1 → 2 → 3 → 4 → 5。核心能力未完成不得只靠配置直接 enforce。

### 阶段 1：数据结构与领域模型

1. 在 knowledge 新增并校验 `RagAuthzProperties`；固化 mode、候选/批次/strict 上限。
2. 固化 resource/group ID codec：document 使用现有 `KnowledgeResourceIds`；auth-platform 增加 tenant-scoped group helper（计划新增，具体类名由实现保持包内风格，例如 `CasdoorGroupIds`），并用碰撞测试确定版本化编码。
3. 定义关系 backfill manifest 格式（tenant/docId/parentSpace/可选 owner/source），不新增 SQL。
4. 盘点历史关系与 group 碰撞；冻结试点 tenant/group→space binding。
5. category→folder 仅产出待验证决策，不进入核心实现。

完成标准：

- 非法 mode/上限配置启动失败。
- 所有资源 ID 有单一构造入口与单测。
- 多 organization 配置、分页/限流预算及 group codec 已验证；不得以“逐租户手工部署”替代正式同步设计。
- 迁移 dry-run 报告可对账，未知 owner 明确列出。
- 试点 group binding 经业务确认。

### 阶段 2：核心业务逻辑

1. 修改 `KnowledgeQueryService.query/filterReadable`：上下文快照、上限、安全 overfetch、去重、过滤顺序、指标。
2. 修改 `ElasticsearchEsGateway`：空 tenant fail-fast、可测试 query body、返回 tenant 校验。
3. 修改 `RealKnowledgeAuthz.filterReadable`：分批、严格 fail-closed、metrics。
4. 修改 SDK `RemoteAuthzEngine.checkBulk`：响应完整性校验。
5. 修改 Casdoor group sync：多 organization 遍历、tenant-scoped ID、direct relationship 对账、完整性/删除熔断/批次/幂等。

完成标准：

- 单元测试证明 tenant term 永不缺失。
- 一文档多 chunk 只产生一个 checkBulk resource。
- deny/missing/error 在 enforce 都不进入 reranker。
- tenant A/B 同名 group 不合并。
- direct membership 对账不会把嵌套/计算所得成员误当作可删除 tuple。
- disabled 回归不调用 AP。

### 阶段 3：接口与适配层

1. edge 增加 `disabled|dual|only`，only 固定 owner/sub，所有失败 401。
2. knowledge/AP server 接 service credential；server 生产安全开关启用。
3. compose/Helm/ESO 配置接线；网络策略只允许 edge 访问 knowledge、knowledge 访问 AP server。
4. 编写 backfill/group binding/E2E fixture 脚本，默认 dry-run、manifest 驱动。
5. 保持 `/rag/query` 与跨服务 DTO 不变。

完成标准：

- 真实 Casdoor token 换出的内部 JWT tenant/user 精确等于 owner/sub。
- only 模式不能用 session/API key 调 `/rag/query`。
- knowledge 无/错 AP credential 无法调用 `/v1/**`。
- 部署配置可启动全链，secret 不进 Git 明文。

### 阶段 4：测试

1. 执行 test-plan 中全部单元、组件、真实 ES、真实 SpiceDB、required E2E。
2. 建 CI required profile，环境缺失失败而非 skip。
3. 做 AP/SpiceDB/ES 故障注入、grant/revoke 并发与性能测试。
4. disabled/shadow/enforce 三态回归。

完成标准：

- required E2E 从 Casdoor 新用户走到查询通过。
- 未授权、跨租户与授权故障均无正文泄露。
- 性能达到目标环境确认的 SLO；若未确认 SLO，不能自行宣称容量验收。
- 回滚演练成功。

### 阶段 5：文档与最终检查

1. 更新两仓库 README/运维手册：配置、启动顺序、关系模型、故障语义。
2. 附试点迁移报告、shadow 指标窗口、group/owner unmatched 清单。
3. 逐项执行最终验收清单。
4. 删除或标记过时的“默认全关/规划态”描述，确保代码和文档一致。

完成标准：

- 运维能按文档独立完成部署、灰度、回滚。
- 无待验证项被误写为已完成。
- 最终变更只包含审批范围，无凭据与测试数据残留。

## 12. 测试方案摘要

完整测试见 `test-plan.md`。发布必须覆盖：

- 单元：Casdoor owner/sub、ES JSON tenant term、docId 去重、bulk 分批/畸形响应、group 租户化。
- 集成：真实 ES tenant A/B；真实 AP/SpiceDB group→space→document 继承与撤权。
- E2E：Casdoor 新用户/role/group → token → edge → internal JWT → knowledge → ES → checkBulk → 允许/拒绝。
- 回归：公开 DTO、现有调用方、disabled 行为、dual 灰度路径。
- 异常：AP/SpiceDB timeout/5xx、ES down、超大 topK、missing docId、group sync 不完整。
- 性能：候选规模/允许率/并发矩阵，记录 P50/P95/P99 与 underfill。

## 13. 风险、监控、灰度与回滚

### 13.1 风险与控制

| 风险 | 控制 |
|---|---|
| tenant 来源被配置漂移 | only 固定 owner；启动校验；E2E 伪造 tenant |
| ES 漏 tenant filter | query body 结构单测 + 真实 ES A/B 测试 + isolation violation 指标 |
| ReBAC 缺关系导致全空 | backfill、shadow、reconcile、unmatched 报告 |
| group 短名碰撞 | tenant-prefixed group；旧 tuple 延后删除 |
| group reconcile 误删 | `readRelationships` 只读 direct tuple；分页不完整禁止 DELETE；删除熔断与 manifest |
| AP/SpiceDB 故障 | enforce fail-closed；高优 availability/error 告警 |
| fully-consistent 延迟 | bulk、候选上限、压测；未经验证不降一致性 |
| underfill | candidate multiplier、underfill ratio；二期按需试点 B |
| dual 模式身份不满足 owner-only | 仅灰度；最终验收切 only |
| public bypass | strict profile 关闭 public；另行设计 public_viewer |
| 双写半失败 | 缺关系安全 deny；projection lag/orphan 指标与补偿 |
| service credential 权限过大 | knowledge 专属 Secret、网络 allowlist、定期轮换；记录后续按 AP capability 拆权债务 |

### 13.2 监控

已有 `knowledge.authz.decisions` 继续使用，并补充低基数指标：

- `knowledge.authz.check_bulk.latency`
- `knowledge.authz.check_bulk.errors`
- `knowledge.authz.candidates`
- `knowledge.authz.allowed_docs`
- `knowledge.authz.underfill`
- `knowledge.rag.tenant_isolation_violations`
- `authz.casdoor.sync.added/removed/errors/incomplete`
- AP server `/v1/check-bulk` QPS/P95/error，SpiceDB latency/error。

日志可带 traceId、mode、数量；不得记录 Bearer、service token、整段正文或高基数 doc/user 作为 metric tag。

### 13.3 灰度

1. **disabled 基线**：记录结果/延迟，不写安全承诺。
2. **shadow 写与双算**：先新文档，再历史 backfill；观察 would-deny/error/underfill。
3. **单 tenant enforce + edge dual**：验证业务结果，但不宣称 owner-only 全局完成。
4. **扩大 enforce**：按 tenant 批次，每批有对账和停止条件。
5. **edge Casdoor-only**：最终 E2E 与安全验收；保留短期 dual 配置用于应急回滚。

进入下一阶段的硬条件：上一阶段无未知高比例 deny、AP error 在 SLO 内、关系对账完成、回滚已演练。

### 13.4 回滚

- 授权过滤：`enforce → shadow → disabled`；不删除新关系。
- 身份：`only → dual`，只在批准的回滚窗口恢复 legacy；不能把 dual 当长期目标。
- group sync：停 reconcile，按 manifest DELETE 新 binding/membership，保留旧 tuple 直到回滚窗口结束。
- SDK/knowledge：回滚到兼容镜像；公开 API 未变，无消费者同步回滚。
- ES：核心方案不改 mapping，无索引回滚；若可选 tenant source 校验引发兼容问题，可关闭额外校验但不能移除 query tenant term。
- 禁止用清空 SpiceDB/ES/Redis 或重置 Git 作为回滚手段。

## 14. 可选 category→folder 方案

仅在 A-06 明确 category 稳定、重命名规则与授权主体后实施：

1. `KnowledgeResourceIds` 增加稳定 category folder ID（建议基于 tenant+规范化 category 的不可逆 hash；算法需版本化）。
2. 文档创建/分类变更写：

   ```text
   folder:<t>_<categoryKey>#parent_space@space:<t>_default
   document:<t>_<d>#parent_folder@folder:<t>_<categoryKey>
   ```

3. 通过现有 admin grant 写 `folder#viewer@group:<t>_<g>#member`。
4. 分类变更要 TOUCH 新 parent_folder 并 DELETE 旧 parent_folder，必须有补偿/对账。
5. 查询仍执行 document checkBulk；category 请求参数只做检索过滤，不做授权依据。

由于当前 `DocumentInfo` 只有 category 字符串、没有稳定 folder ID/层级/重命名语义，本可选项不应和核心 enforce 同批上线。

## 15. 最终验收清单

### 身份与租户

- [ ] Casdoor token 完整验签，owner/sub 缺失 401。
- [ ] 最终 profile 为 Casdoor-only；session/API key 不能调用业务查询。
- [ ] 内部 JWT/TenantContext/SpiceDB subject 四处身份一致。
- [ ] 客户端 tenant 伪造不改变 owner。

### 检索与授权

- [ ] ES 每个 search 必含 tenant term，tenant 空白不发请求。
- [ ] 多 chunk/多源同 doc 只判一次。
- [ ] checkBulk 精确使用 document `<tenant>_<docId>`、view、user `<sub>`。
- [ ] deny/missing/error/null-docId 在 enforce 不返回正文。
- [ ] reranker 只接收授权后候选。
- [ ] public/shared 在 strict profile 关闭或已有另行批准的 ReBAC 方案。

### 关系与数据

- [ ] 新文档 owner/parent_space tuple 正确。
- [ ] group ID tenant-scoped，跨租户同名不碰撞。
- [ ] 配置的多个 organization 均完成分页同步与 direct tuple 对账；单个失败不会触发该 organization 的 DELETE。
- [ ] group→default space binding 有审批 manifest。
- [ ] 历史文档 parent-space backfill 完成；未知 owner 未被伪造。
- [ ] 旧短 group tuple 仅在新链稳定后受控清理。

### 部署与安全

- [ ] AP server `/v1/**` service credential 与网络隔离启用。
- [ ] knowledge 禁止外部直连/anonymous fallback。
- [ ] Secret 不入库、不打日志。
- [ ] topK/candidates/bulk 有上限且配置校验。
- [ ] strict tenant-only 与 public enabled 冲突时启动失败。

### 测试、监控与运维

- [ ] required E2E 未 skip：Casdoor user/role/group 到查询 allow/deny 全闭环。
- [ ] tenant A/B、grant/revoke、故障注入、畸形 bulk 均通过。
- [ ] disabled 回归通过，shadow 指标窗口经业务签字。
- [ ] 性能达到目标 SLO或有明确容量限制。
- [ ] 告警、灰度、回滚演练通过。

## 16. 架构复审记录（最终交付前）

资深架构复审重点检查了身份权威、双重隔离、授权时序、group 租户化、历史数据、故障语义、公开库、候选上限、部署和回滚。复审后已在本版统一以下结论：

1. 明确区分 dual 灰度与 Casdoor-only 最终验收，避免“owner-only”与 legacy 并存自相矛盾。
2. 把当前 public/shared bypass 明确排除出 strict 核心验收，避免声称“本租户”却仍跨租户返回公共正文。
3. 保留 mandatory checkBulk，即使未来采用 lookup/ES ACL 预过滤，也不能取消最终权威复核。
4. 把历史 owner 不可恢复写成硬约束，禁止以管理员/迁移者补 owner。
5. 把 group 短名碰撞与 group→space binding 提升为 enforce 前置，不把 role scope 误当文档权限。
6. 补入 topK/候选/bulk 上限与协议畸形 fail-closed，避免功能正确但暴露 DoS/静默错配。
7. 把可跳过的现有集成测试拆成 developer optional 与 CI required，避免假绿。
8. category→folder 延后到业务语义确认后，避免将可变展示标签直接固化成授权主键。
9. 将 Casdoor 同步从“单 organization/逐部署”修正为显式 organizations 列表，并改用现有 `readRelationships` 对账 direct tuple，消除多租户闭环与 destructive reconcile 的矛盾。
10. strict/public 冲突上移到启动校验，候选倍数改为与 reranker 取最大值，避免运行期语义漂移与乘法放大。

复审未发现最终方案与当前实际类/方法/表结构冲突；计划新增内容均已明确标记。仍待验证的生产 SLO、group→space 业务映射、历史 owner manifest、category 稳定性、Casdoor 多 organization API 限流预算和 group codec 编码，必须在对应阶段形成书面结论，不能由实施 Agent 自行猜测。

## 17. Claude 跨模型复核记录（codex-plan 阶段二）

Claude 对照两个真实仓库逐条核验了 Codex 标注的"当前存在"断言，结论：**关键事实全部属实，无需推翻方案**。带证据的确认：

| Codex 断言 | 证据（文件:行） | 结论 |
|---|---|---|
| `fuse → filterReadable → rerank` 落点 | `knowledge-service/.../KnowledgeQueryService.java:290-293` | ✅ |
| ES 无条件 `term tenantId`，category 可选 | `.../es/ElasticsearchEsGateway.java:202-213` | ✅ |
| checkBulk(view, fullyConsistent) + `<tid>_<docId>` | `.../authz/RealKnowledgeAuthz.java:126-141` | ✅ |
| 资源 id 服务端 mint、拒收客户端前缀 id | `.../authz/KnowledgeResourceIds.java:5,12,26` | ✅ mint 非 compare |
| `app.rag.authz.mode` + `AuthzMode{DISABLED,SHADOW,ENFORCE}` | `.../authz/KnowledgeAuthzConfig.java:24,30`；`AuthzMode.java`；`application.yml:36` | ✅ |
| filterReadable 已含 shared 放行 + null docId enforce fail-closed | `KnowledgeQueryService.java:303-333` | ✅ 语义比 Codex 描述更完整 |
| 候选池 `poolLimit=topK×rerank放大(默认3)` 已有界 | `KnowledgeQueryService.java:256`；`application.yml:166` | ✅ 印证 §8.1/§16.10「取 max 非相乘」 |
| `CasdoorClient.shortName` 丢 org 前缀 → 跨租户同名组合并 | `auth-platform-admin/.../casdoor/CasdoorClient.java:37,68-70` | ✅ 缺陷真实，Phase 1 修 |

**两点补充（不改方案，仅澄清）**：

1. **底座比"草稿"更成熟**：knowledge-service 的 ReBAC 读路径 git status **干净=已提交**、单测通过（与 memory「里程碑 A/A2 全绿」一致）。故本计划性质确认为"生产化 + 安全切 enforce"，非从零实现——这与 Codex §1「重点是固化+接线+灰度，而非重写检索」完全一致。

2. **一个需业务先拍板的决策点（Codex §8.3 default space binding 的 manifest 内容）**——即"同租户成员对本租户知识库的默认可见性"。它决定 backfill/binding manifest 写不写 `space:<t>_default#viewer@group:<t>_members#member`：
   - 选项①「同租户默认可见」：租户成员组绑 default space viewer；符合多数"公司内部知识库"直觉。
   - 选项②「仅显式授权可见」（现状语义）：只有 owner / 被直授 / 被授权组可见；更严格。
   - 选项③「按库配置」：每个 space 自行决定。
   本决策不阻塞 Phase 0/2 的代码硬化，但**必须在 Phase 1 冻结 binding manifest 前确定**。

   **✅ 已定（2026-07-15，用户拍板）：选项① 同租户默认可见。** Phase 1 的 backfill/binding manifest
   须为每个租户写 `space:<t>_default#viewer@group:<t>_<membersGroup>#member`（`membersGroup` 为该租户
   的"全体成员组"，名称由 manifest 逐租户声明），使同租户成员登录即可见本租户 default space 下全部文档。
   owner/直授/其它授权组叠加在此之上。

**复核结论**：方案（A 合并硬化）事实基础可信、分阶段安全（shadow→enforce、backfill 前置、仅切配置回滚），**批准进入呈报与用户确认关卡**。
