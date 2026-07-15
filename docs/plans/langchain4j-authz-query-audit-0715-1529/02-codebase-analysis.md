# 02 — Codebase Analysis

> 视角：codebase-explorer。路径前缀：`LC4J=/Users/liruijun/personal/LLM/langchain4j-platform`，`AUTH=/Users/liruijun/personal/LLM/auth-platform`。

## 1. 仓库与依赖边界

- `LC4J/knowledge-service/pom.xml:111-121` 是唯一引入 `auth-platform-sdk` 和 AOP 的模块。
- 全仓 Java 扫描只发现 production 直接调用：`RealKnowledgeAuthz.java:119` 的 `check`、`:150` 的 `checkBulk`；AOP 注解仅 `KnowledgeAccessApplicationService.java:35,41`。
- 未发现 production `lookupResources/lookupSubjects` 调用；edge-gateway、conversation、agent、eval 等模块也未引入 `com.lrj.authz`。
- `GraphController.java:63-67` 与 `MultimodalImageSearchController.java:79-82` 只按 mode fail-closed，不调用 AP。

## 2. 查询调用清单

| # | 消费位置（file:line） | 最终调用 | 主体 | 资源 | permission / consistency | disabled | shadow | enforce |
|---|---|---|---|---|---|---|---|---|
| Q1 | `KnowledgeQueryService.java:327-345` | `RealKnowledgeAuthz.java:150 checkBulk` | query 开头快照的 `userId` | 去重后的 `document:<tenant>_<docId>` | `view` / full | 直通，不调 AP | 真查、返回全集、记 would_filter | 真查并过滤；异常空集 |
| Q2 | `DocumentService.java:214-216` 覆盖同名文档 | `RealKnowledgeAuthz.java:119 check` | `TenantContext.current().userId()` | `document:<tenant>_<docId>` | `edit` / full | 放行 | 真查但放行、记 would_deny | deny 抛 403；异常亦拒绝 |
| Q3 | `DocumentService.java:289-302` 列表 | `RealKnowledgeAuthz.java:150 checkBulk` | 当前 user | 当前 tenant 的文档集合 | `view` / full | 全量 | 真查、返回全量 | 只保留 readable；异常空列表 |
| Q4 | `DocumentService.java:313-324` 单文档 get | `RealKnowledgeAuthz.java:119 check` | 当前 user | 当前 tenant document | `view` / full | 返回 registry 结果 | 真查但放行 | deny/异常返回 empty，上层 404 |
| Q5 | `DocumentService.java:332-345` 删除 | `RealKnowledgeAuthz.java:119 check` | 当前 user | 当前 tenant document | `edit` / full | 允许后走 Noop 关系删 | 真查但允许，随后删关系 | deny/异常返回 false；允许后先删关系 |
| Q6 | `KnowledgeAccessApplicationService.java:35-37` share | `CheckAccessAspect.java:26-36 -> engine.check` | `SubjectResolver` 当前 user | controller 构造的完整 document id | `edit` / full | endpoint/bean 不存在 | endpoint/bean/aspect 不存在 | deny 抛 `AccessDeniedException`；异常不 proceed |
| Q7 | `KnowledgeAccessApplicationService.java:41-43` unshare | 同上 | 同上 | 同上 | `edit` / full | endpoint/bean 不存在 | endpoint/bean/aspect 不存在 | 同上 |
| Q8 | 全仓扫描 | `lookupResources` | — | — | — | 无 | 无 | 无 |
| Q9 | 全仓扫描 | `lookupSubjects` | — | — | — | 无 | 无 | 无 |

Q1-Q5 的两个实现入口分别在 `RealKnowledgeAuthz.checkDocument:116-132` 与 `filterReadable:135-178`；表按消费语义拆开，避免把一个通用方法误算成一个业务调用点。

## 3. 主调用链

### 3.1 RAG 查询

`KnowledgeQueryService.query:250-303` 在 `:254-257` 一次性快照 tenant/user，检索多源后 `fusion.fuse:296`，随后 `filterReadable:298`，最后才 `reranker.rerank:299` 和响应。`filterReadable:331-345` 排除 shared、去重 docId、调用 bulk，并在 enforce 丢弃 deny/null-docId。

### 3.2 文档元数据与写操作

- overwrite：registry 命中后先 `edit` check，再覆盖（`:211-223`）。
- list：当前租户 registry 列表经 bulk view 过滤（`:289-302`）。
- get：先 tenant-scoped registry get，再 view check；deny 隐藏为 404（`:313-324`）。
- delete：先 registry get，再 edit check，再删关系，最后删业务数据（`:332-355`）。
- create：新建完成后写 owner + parent_space（`:263-267`）；覆盖不改 owner。

### 3.3 分享 AOP

`DocumentShareController.java:35-52` 接收裸 docId，用 `KnowledgeResourceIds.document(currentTenant, docId)` 构造 full id，再从外部 bean 调 `KnowledgeAccessApplicationService`。切面按参数名解析 full id；`CheckAccessShareTest.java:89-123` 证明代理、subject/resource、full consistency 生效。

## 4. 契约与 schema 对照

- `AuthzEngine.java:14-23` 定义四个查询；bulk 返回 `Map<ResourceRef,Boolean>`。
- `ResourceRef.java:4-12` 是 record，结构相等；消费方 `allowed.get(ResourceRef.of(...))` 可正确回读。
- `Consistency.java:14-23` 提供 minimize/full/at-least-as-fresh。
- `knowledge.zed:43-54` 定义 document `edit/comment/view`；owner → edit → comment → view，parent_space 箭头成立。
- `RealKnowledgeAuthz.java:86-93` 新建写 owner 与 `parent_space@space:<t>_default`；`:104-112` viewer 授予/撤销；所有 document id 最终经 `KnowledgeResourceIds.document:12-13`。
- `RemoteAuthzEngine.java:60-105` 回显 resource 严格对齐；`AuthzController.java:34-40` 按请求资源顺序回传 resource+allowed。

## 5. 逐维度审计结论

| 维度 | 结论 | 证据与推导 | 风险/触发/最小建议 |
|---|---|---|---|
| 1 权限与类型 | **正确，但 D3 运营前置存疑** | `knowledge.zed:43-54` 与 view/edit 调用一致；owner 推导 view/edit。`RealKnowledgeAuthz.java:44-46` 明示 default space 自身无 viewer | **中**：未 provision group→space tuple 时，同租户成员被误拒。切 enforce 前机器校验 D3 tuple/membership |
| 2 租户前缀 | **正确** | write/delete/grant/revoke/check/bulk 均经 `KnowledgeResourceIds`；AOP full id 由 controller `:51-52` 构造 | 当前无串权/漏前缀。低风险加固：AOP 服务不要同时信任独立 fullId 与 bare docId |
| 3 主体来源 | **正确，存在低级加固点** | edge `CasdoorTokenExchangeFilter.java:106-115` 取 owner/sub；内部 JWT `InternalToken.java:105-110,128-136`；下游 filter `:59-73`；query 单次快照 | `DocumentService` 与分享链多次读 ThreadLocal，当前同步请求内稳定，无已证实竞态。建议方法入口快照/传值 |
| 4 bulk 对齐 | **缺陷（低）** | record equality 与分批 `putAll` 正确；SDK校验基数/resource/重复，但 `RemoteAuthzEngine.java:96` 对缺失或非 boolean `allowed` 直接 `asBoolean()`，与 `:72-76`“无缺字段”承诺不符 | 畸形响应会被当普通 deny，enforce 安全但 shadow/error 指标错误。严格校验 `allowed.isBoolean()`；single check 同修 |
| 5 一致性 | **正确，性能待验证** | single/bulk/AOP 全部 full；撤权和跨副本 read-after-write 最安全 | 可能增加延迟/超时进而产生空结果，但不会越权。压测前不降级；长期可评估 token 传播 |
| 6 三态 | **直接路径正确；share shadow 语义存疑** | Noop 直通；Real shadow return true/all；enforce 真拦。分享四个 bean 仅 enforce | 已有计划规定 shadow 不暴露分享，本次措辞却要求“放行操作并观测”。先冻结产品决定；不得默默暴露新 API |
| 7 故障姿态 | **安全正确，协议观测有缺口** | Real 两路径 catch `RuntimeException`；RestClient/解析错误均属 runtime。AOP 异常阻止 `proceed`，但只有 deny 被映射 403 | AOP 依赖故障为 5xx 且无 authz error 指标；安全 fail-closed。补指标/错误分类为加固项 |
| 8 无 docId | **正确** | `KnowledgeQueryService.java:343-351` enforce 丢弃；Graph/image 独立端点 enforce 整体禁用 | 无泄漏；会误伤 GraphRAG/旧脏 metadata，属已知可用性损失 |
| 9 public/shared | **正确（当前实现）** | vector/ES 由独立 public 分区查询设置；keyword 从 public tenant 分区判定；融合 `HybridFusionService.java:75-90,105-119` 用 AND 偏私有 | 当前不可由客户端伪造。未来 RetrievalSource 可伪造 shared，新增源必须纳入安全评审；strict/public 已互斥 |
| 10 AOP | **正确，接口不变量可加固** | 外部 controller 调用；参数名与注解一致；full id/type/edit/full 均有测试。schema 没有 document.manage | 独立 `documentResourceId`/`docId` 理论可错配；当前唯一生产 caller 同源构造。可改为由服务内部只从 tenant+docId 派生 |
| 11 overfetch/bulk | **授权正确；召回/容量待验证** | `computePoolLimit:311-320` 取 max 非相乘、封顶；Real 按 bulkSize 稳定分批 | 只会 underfill/误伤，不会漏判后放行。`latest` SpiceDB 与生产 SLO/上限待验证；固定版本并压测 |
| 12 时机/日志 | **knowledge 内正确；跨服务存在高风险旁路** | fuse 后、rerank/响应前过滤；日志仅 tenant/user/id/数量，不记未授权正文 | **高（条件触发）**：conversation semantic cache 开启后 `ConversationController.java:74-84` 在 RAG 前短路，`SemanticCache.java:56-68` 仅 tenant 分桶；撤权也不清缓存。enforce/shadow 期间保持关闭 |

## 6. 发现清单

### F1 — 高：授权查询可被 tenant-wide pre-RAG semantic cache 绕过（条件触发）

触发：`CONVERSATION_SEMANTIC_CACHE_ENABLED=true`，用户 A 生成含其可见私有文档的回答，用户 B 同租户以相似问题命中缓存；或用户被撤权后再次命中旧缓存。缓存条目只有 question/vector/reply，不能重校验来源；share/unshare 也没有调用缓存失效器。默认配置为 false，因此不是默认态现存泄漏，但它是 enforce 发布的硬互斥项。

最小修复：在细粒度 authz shadow/enforce 阶段禁止该缓存；长期另做 user+authorization-version/source-recheck 设计。

### F2 — 中：D3 业务规则依赖手工/可选关系 provision

`GroupSyncService.java:53-88` 只同步 group membership，不建立 group→default space viewer。`rag-authz-fixture.sh:36-48` 会写，但默认 dry-run；`casdoor-tenant-provision.sh:92-98` 仅 `WIRE_SPICEDB=1` 执行。漏执行会安全地误拒所有非 owner，而不是越权。

最小修复：把 tuple/membership 校验作为 enforce gate；不建议由通用 group sync 擅自猜业务授权。

### F3 — 低：SDK “严格响应”未校验 allowed 字段类型/存在性

`check` 与 `parseCheckBulk` 都使用 `path("allowed").asBoolean()`；缺失/错误类型可静默变 false。修复应在 SDK，knowledge 无需改调用。

### F4 — 低/待确认：share shadow 无观测面

当前是有意的 enforce-only API，但无法在切 enforce 前量化 share/unshare would-deny。是否改变属于产品/API 决策，不作为无条件修复。

## 7. 可复用代码与受影响文件

- 保留：`KnowledgeResourceIds`、`KnowledgeAuthz`/Real/Noop、现有 mode config、bulk 分批、指标、AOP 测试框架、fixture/provision 脚本。
- 确需修改：`AUTH/auth-platform-sdk/.../RemoteAuthzEngine.java`、对应测试；两个仓库的 enforce smoke/部署文档与 D3 preflight。
- 条件修改：若决定 shadow 分享观测，涉及 `KnowledgeAccessConfig`、`KnowledgeAccessApplicationService`、`DocumentShareController`、`AuthzExceptionHandler`，并需要 mode-aware evaluator；不可只放开 controller 条件。
- 暂不修改：`knowledge.zed`、数据库、外部 `/rag/query` 与 `/v1/check(-bulk)` DTO、消息结构。
