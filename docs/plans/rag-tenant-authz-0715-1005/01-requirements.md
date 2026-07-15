# 需求分析：RAG 多租户与文档级 ReBAC

## 1. 分析依据与状态

本文结论来自以下实际文件的只读核对：

- `langchain4j-platform/knowledge-service` 的检索、ES、文档生命周期、授权适配和测试代码。
- `langchain4j-platform/edge-gateway`、`platform-security` 的 Casdoor token 换发与内部 JWT 传播代码。
- 本仓库 `auth-platform-sdk`、`auth-platform-server`、`auth-platform-core`、`auth-platform-admin` 的 SDK、REST facade、SpiceDB schema 与 Casdoor 组同步代码。
- `docs/plans/langchain4j-authz-integration-0714-1701/`、两仓库 `CLAUDE.md`，以及 `~/.claude/plans/mock-velvet-mist.md` 中的既有决策记录。

状态基线：核心代码已经部分实现，但运行默认值仍是 `app.rag.authz.mode=disabled`；本文规划的是从“代码存在”收敛到“可灰度、可验证、可回滚的端到端闭环”，不是假设尚无任何实现。

## 2. 目标

### 2.1 核心目标

1. `POST /rag/query` 和别名 `POST /knowledge/query` 的租户边界只能来自可信安全上下文；Casdoor 路径下，该上下文只能由已通过签名、时间、issuer、audience 校验的 access token 的 `owner` claim 产生。
2. ES 查询必须在 `bool.filter` 中始终包含精确 `term tenantId=<trustedTenantId>`；客户端不能通过 body、query、header 覆盖该值。
3. ES/向量/关键词等候选融合后，在返回正文前按非空 `docId` 去重，一次或分批调用 `checkBulk`，判定对象严格为：

   `document:<tenantId>_<docId>#view@user:<sub>`

4. `checkBulk` 明确判否、缺失结果、协议异常或授权依赖故障时，`enforce` 必须丢弃对应文档；不得把授权异常降级成放行。
5. Casdoor 用户完成“创建用户（`owner=tenant`）→ 分配 role 与 group → 登录 → edge 换发内部 JWT → 查询”后，只能命中本租户且拥有 `view` 的文档。
6. 通过 `disabled → shadow → enforce` 灰度，不在关系数据未准备好时直接切强制模式。

### 2.2 可选目标

将现有 `category` 映射为 SpiceDB `folder`，让文档通过 `parent_folder->view` 继承类目授权。它不能替代文档级最终 `checkBulk`，也不作为核心闭环的发布门。

## 3. 已确认的业务规则

| 编号 | 规则 | 代码依据 |
|---|---|---|
| BR-01 | 查询请求没有 `tenantId` 字段，外部协议保持 `query/topK/minScore/category` 四字段 | `platform-protocol/.../KnowledgeQueryRequest.java` |
| BR-02 | `KnowledgeQueryService.query` 从 `TenantContext.current().tenantId()` 取租户 | `KnowledgeQueryService.java:247-273` |
| BR-03 | Casdoor 身份映射为 `owner→tenantId`、`sub→userId`，再由 `InternalToken.mint` 写入内部 JWT | `CasdoorTokenExchangeFilter.exchangeAndForward`、`InternalToken.mint` |
| BR-04 | ES 的 `tenantId/docId/category/...` mapping 是 `keyword`，检索使用 `term tenantId` | `ElasticsearchEsGateway.ensureIndex/search` |
| BR-05 | ReBAC 资源 ID 由服务端构造为 `<tenantId>_<docId>`，客户端只传裸 `docId` | `KnowledgeResourceIds.document` |
| BR-06 | 主体 ID 必须是 Casdoor `sub`，并与内部 JWT `uid`、`TenantContext.userId`、SpiceDB `user` 一致 | edge、security、`KnowledgeAccessConfig.subjectResolver` |
| BR-07 | 文档 `view` 可以来自直接 viewer、comment/edit、owner 或 parent folder/space 继承 | `knowledge.zed` 的 `document` 定义 |
| BR-08 | role 负责 token 中的粗粒度 scope；group membership 才是 SpiceDB 继承授权的主体边 | `casdoor-seed.sh`、`GroupSyncService` |
| BR-09 | 查询授权发生在融合后、rerank 前；同一文档多个 chunk 只需要一次文档判权 | `KnowledgeQueryService.filterReadable` |
| BR-10 | `enforce` 下没有 `docId` 的非公共命中必须丢弃 | `KnowledgeQueryService.filterReadable` |
| BR-11 | 当前公共库命中会以 `shared=true` 绕过文档 ReBAC；严格“本租户且有 view”验收必须保持 `RAG_PUBLIC_ENABLED=false`，或另行把公共库纳入 ReBAC | `filterReadable`、`PublicKb` |
| BR-12 | 默认 `disabled` 不双写、不后置过滤；`shadow` 真判定但不拦截；`enforce` 真过滤 | `AuthzMode`、`KnowledgeAuthzConfig` |

## 4. 功能需求

### FR-01 可信租户注入

- Casdoor token 必须校验签名、`exp/nbf`、issuer、audience。
- 目标验收 profile 中，租户固定取 `owner`；`tenant-claim` 不能被配置成其他 claim 后静默继续。
- 不读取 `X-Tenant-Id`、请求参数或 JSON 字段作为租户。
- `owner` 或 `sub` 缺失/空白返回 401。
- 进入 knowledge-service 后，查询方法只读取内部 JWT 还原的 `TenantContext`。

### FR-02 ES 强制租户过滤

- `ElasticsearchEsGateway.search` 的所有路径均必须生成 `bool.filter=[term tenantId, ...optional category]`。
- 空白 tenant 必须在发 ES 请求前失败；不能生成“无 tenant filter”的查询。
- `category` 仅是额外过滤条件，不能替代 tenant filter。
- 删除继续使用 `tenantId + docId` 双 term，避免跨租户清理。

### FR-03 文档级后置授权

- 候选顺序：多源召回 → 融合 → 收集非空 `docId` → `LinkedHashSet` 去重 → `checkBulk(view)` → 丢弃 denied/error/missing → rerank → 截断响应 topK。
- `checkBulk` 输入 tenant、subject 都取同一次请求的可信上下文快照，处理中途不能重新从可变请求输入获取。
- 文档资源构造只走 `KnowledgeResourceIds.document(tenantId, docId)`。
- `enforce` 授权服务超时或 4xx/5xx时返回空命中或明确 503，二者可选但不得返回未授权正文；本计划最终采用“查询成功但 hits 为空 + 错误指标”，保持当前接口形状。
- `shadow` 记录 would-deny/error，但返回原候选；它不是安全生效态。

### FR-04 关系准备

- 新文档写：`document:<t>_<d>#owner@user:<sub>` 与 `#parent_space@space:<t>_default`。
- Casdoor group 同步后写：`group:<t>_<group>#member@user:<sub>`；不得继续使用全局短组名导致同名组碰撞。
- 组获得默认知识空间 view：`space:<t>_default#viewer@group:<t>_<group>#member`。
- role 只决定 `chat/ingest/...` scope，不得被误认为自动拥有某篇文档的 `view`。
- 历史文档若没有可审计 owner，不得把迁移执行人伪造成 owner；可幂等补 `parent_space`，再由明确的 group/space binding 决定是否可见。

### FR-05 灰度与配置

- 默认继续 `disabled`。
- `shadow` 前必须能访问 AP server/SpiceDB，并完成新文档关系双写或历史 parent-space backfill。
- `enforce` 前必须完成：组 ID 租户化、关系对账、required E2E、不允许跳过的 CI profile、回滚演练。
- 生产 AP server `/v1/**` 必须启用 service credential 与网络隔离；knowledge-service 不直连 SpiceDB。

### FR-06 候选池与资源限制

- `topK` 必须有服务端上限，候选池乘法需防整数溢出。
- 授权后置过滤可能导致结果不足 topK；首期通过有上限的授权候选放大倍数缓解，不能无限循环拉取。
- `checkBulk` 资源数必须受控；超过单批上限时稳定分批，任一批失败仅能拒绝该批或整次请求，不能放行。

## 5. 边界条件与异常语义

| 场景 | 期望 |
|---|---|
| token 签名错、issuer/audience 错、过期 | edge 401，不换发内部 JWT |
| 已验签 token 无 owner/sub | edge 401 |
| 请求伪造 tenant header/query/body | 被忽略或明确拒绝；最终 tenant 仍等于 owner |
| tenant 为空 | 查询在调用 ES 前失败，不允许无 tenant filter |
| ES 返回其他租户 `_source` | 文档级 ReBAC 仍以当前 tenant 构造资源并拒绝；同时产生隔离违规指标 |
| 同一 doc 多个 chunk/多源命中 | 对该 doc 只做一次 checkBulk item，允许后保留原有多个候选语义 |
| `docId=null` | enforce 丢弃，shadow 记录 |
| checkBulk 少返回/乱序/重复 | 不能按“缺省 false 但无告警”静默吞掉；协议校验失败并 fail-closed |
| AP/SpiceDB 超时 | enforce 不泄露正文；shadow 可放行但必须 error 指标 |
| 用户刚加组/撤组 | 使用 fully-consistent 判定；同步尚未完成时按 SpiceDB 当前态决定 |
| 未授权高分文档挤占候选 | 允许结果不足，但通过有界 overfetch 降低概率并监控 underfill |
| 公共库开启 | 不属于严格本租户验收；必须单独决策其 ReBAC 语义 |

## 6. 非目标

- 不更改 `/rag/query` 的公开请求/响应结构。
- 不让客户端传 tenantId，也不新增 tenant 选择器。
- 不把 role、scope 和 ReBAC relation 合并成同一概念。
- 不让 knowledge-service 直连 SpiceDB gRPC。
- 不在本期把 GraphRAG、图片检索全部重构成有 docId 的资源模型；它们在 enforce 下继续 fail-closed。
- 不在核心阶段实现完整 space/folder CRUD、授权 UI 或通用 ABAC。
- 不承诺用分布式事务原子提交向量、Redis、ES、图谱与 SpiceDB；缺失关系以拒绝为安全默认，并通过 shadow/backfill/reconcile 收敛。
- 不在本计划中迁移其他业务服务的授权。

## 7. 歧义与待验证项

| 编号 | 歧义/待验证 | 默认决策 |
|---|---|---|
| A-01 | “tenantId 只取 token.owner”是否要求彻底下线 session/API key | 核心验收 profile 采用 Casdoor-only；灰度期允许 dual，但 dual 不计最终验收 |
| A-02 | 哪些 group 对 default space 有 viewer/editor | 待业务给出显式映射；禁止按角色名猜测。E2E fixture 可使用专用测试 group→viewer |
| A-03 | 历史文档的 owner | 无可信 manifest 时不补 owner，只补 parent_space；待数据盘点 |
| A-04 | 公共库是否保留 | 核心验收关闭；若需要，另做 `public_viewer` 关系化方案 |
| A-05 | 授权失败返回空结果还是 503 | 采用保持协议兼容的空结果并打 error 指标；若产品要求区分，后续增加稳定错误码而非泄露候选 |
| A-06 | category 是否稳定、是否允许重命名/层级 | 待验证；未确认前 category→folder 只作为后续可选阶段 |
| A-07 | SpiceDB `checkbulk` 单次上限与生产延迟预算 | 在目标版本压测后固化；计划先设保守可配置上限 |
| A-08 | Casdoor 多 organization 用户如何选择 active tenant | token 仍只支持单一 `owner`，同一会话切换 active tenant 不在本期；但同步服务必须支持显式 organizations 列表，覆盖多个租户组织 |

## 8. 验收标准

1. 使用 tenant A 用户 token 查询时，抓取的 ES request JSON 必含 `term.tenantId=tenantA`，伪造 tenant B 输入不能改变它。
2. tenant A 与 tenant B 有同文案文档时，A 的响应不含 B 的 `docId/text`。
3. 同租户用户 U 对 D 无 `view` 时，即使 D 是 ES 第一名也不出现在响应；授予后出现；撤销后立即消失。
4. 一篇文档多个 chunk、多源命中时，授权端只收到一个 `<tenant>_<docId>` item。
5. AP/SpiceDB 停止后，`enforce` 响应不含任何未验证正文，并产生 error 指标。
6. Casdoor 新用户的 `owner`、`sub` 分别等于内部 JWT 的 `sub`、`uid`；group 同步 tuple 使用租户前缀。
7. `disabled` 回归结果与改造前一致；`shadow` 不拦截但能观测差异；只有 `enforce` 计入安全验收。
8. required E2E profile 不允许以 assumption/环境缺失静默 skip 后仍宣称发布门通过。
