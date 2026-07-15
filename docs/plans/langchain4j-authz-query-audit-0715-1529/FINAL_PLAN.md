# langchain4j-platform 授权查询正确性审计报告 + 修复计划

> 审计日期：2026-07-15（Asia/Shanghai）  
> 审计对象：`/Users/liruijun/personal/LLM/langchain4j-platform` 与 `/Users/liruijun/personal/LLM/auth-platform` 当前工作树  
> 重要说明：两仓库均有未提交改动；本报告的代码行号对应本次审计时工作树。实施前应重新 `rg` 并校准行号。  
> 本文件只做分析与规划，没有修改任何业务代码。

## 1. 背景、目标与非目标

knowledge-service 已通过 auth-platform SDK 对 RAG、文档元数据、覆盖/删除及分享进行 document 级 ReBAC。本审计逐一验证 `check/checkBulk/@CheckAccess` 的 permission、resource、subject、tenant codec、一致性、批量对齐、三态与错误姿态，并扫描其它模块是否漏有查询调用。

非目标：不开发 space/folder ACL、新 permission、GraphRAG/图片溯源或授权感知缓存；不修改 `knowledge.zed`；不把性能优化伪装成 correctness 修复；不推断历史 owner。

## 2. 总结论

**直接授权查询调用总体正确，未发现把本应 deny 的 document 错误放行为 allow 的高危调用缺陷。** permission/resource type、租户前缀、主体、bulk map key、full consistency、enforce fail-closed、null-docId 丢弃、过滤时机均能由真实代码和 schema 推导成立。

但整体接入仍不能无条件宣告“生产正确”，存在以下发现：

| ID | 级别 | 结论 |
|---|---|---|
| F1 | **高（条件触发）** | conversation tenant-wide pre-RAG semantic cache 开启时会短路 knowledge 查询，跨用户复用回答或撤权后命中旧回答，绕过本次所有正确 check；默认关闭，故当前默认态不触发 |
| F2 | **中** | 已冻结 D3“同租户成员默认可见”依赖显式 group→default-space viewer tuple；当前 GroupSync 只同步 membership，fixture 默认 dry-run、provision 可选 wiring，漏执行会造成大面积误拒 |
| F3 | **低** | SDK 声称严格校验 bulk，但 `allowed` 缺失/类型错误会被 `asBoolean()` 静默解释为 false；single check 同样如此。不会越权，但会掩盖协议错误和污染 shadow 指标 |
| F4 | **低/待确认** | share/unshare 仅 enforce 暴露，shadow 无 would-deny 观测；这符合既有批准规则，却与本任务“shadow 放行操作并打点”的措辞冲突，需产品确认后才可改 |
| H1 | 加固 | DocumentService/AOP 链有多次 ThreadLocal 读取，当前同步请求内稳定；AOP 服务同时接 full resource id 与 bare docId，当前唯一 controller 同源构造但接口不变量偏弱 |
| P1 | 性能待验证 | full consistency 与 bulk-size=100 correctness 安全，但生产 P95/P99、真实 SpiceDB 版本上限尚无仓库内证据；镜像使用 `latest` 降低可重复性 |

**发布判断：** 修复 F3，并把 F1/F2 变成不可绕过的发布门禁后，可按 disabled→shadow→单租户 enforce 灰度；否则不得切 enforce。

## 3. 已确认业务规则

1. 读=`view`；覆盖/删除/分享/撤销=`edit`。
2. document/space id=`<tenantId>_<id>`，客户端只给裸 docId。
3. subject=`user:<TenantContext.userId>`；Casdoor `sub` 经 edge 换发到内部 JWT `uid`，下游验签后重建。
4. `document.owner → edit → comment → view`；`parent_space->edit/view` 有效。
5. D3 已冻结为同租户成员默认可见；必须显式存在 default-space viewer binding 与 membership。
6. disabled 不调 AP；shadow 对实际调用 fail-open；enforce 真拦且依赖错误 fail-closed。
7. public/shared 是有意公共分区语义；strict-tenant-only 与 public 互斥。
8. 非 public 且无 docId 的命中 enforce 丢弃。
9. 当前分享规则是 editor 可转授 viewer；schema 不存在 `document.manage`，本轮不发明新权限。

## 4. 查询调用清单

| 调用点（file:line） | 类型 | 主体 | 资源 | permission | consistency | disabled / shadow / enforce |
|---|---|---|---|---|---|---|
| `KnowledgeQueryService.java:327-345` → `RealKnowledgeAuthz.java:150` | checkBulk | query `:254-257` 快照 user | 去重 `document:<t>_<docId>` | view | full | 直通 / 真查后全集+would_filter / 真过滤，异常空集 |
| `DocumentService.java:214-216` overwrite | check | 当前 user | `document:<t>_<docId>` | edit | full | 放行 / 真查但放行+would_deny / deny或异常→403 |
| `DocumentService.java:289-302` list | checkBulk | 当前 user | 当前 tenant doc 集 | view | full | 全量 / 真查后全量 / 可读子集，异常空列表 |
| `DocumentService.java:313-324` get | check | 当前 user | 当前 tenant document | view | full | registry 结果 / 真查但放行 / deny或异常→empty→404 |
| `DocumentService.java:332-345` delete | check | 当前 user | 当前 tenant document | edit | full | 放行 / 真查但放行 / deny或异常→false→404 |
| `KnowledgeAccessApplicationService.java:35-37` share | @CheckAccess→check | `SubjectResolver` 当前 user | controller helper 构造完整 document id | edit | full | endpoint 无 / endpoint 无 / deny 403；异常不执行写 |
| `KnowledgeAccessApplicationService.java:41-43` unshare | @CheckAccess→check | 同上 | 同上 | edit | full | endpoint 无 / endpoint 无 / 同上 |
| 全仓 production 扫描 | lookupResources/lookupSubjects | — | — | — | — | **均为 0** |

实现公共落点：single 为 `RealKnowledgeAuthz.checkDocument:116-132`，bulk 为 `filterReadable:135-178`；表按业务消费点展开。其它服务没有 auth-platform 查询调用。`GraphController:63-67`、`MultimodalImageSearchController:79-82` 仅在 enforce 整体 fail-closed。

## 5. 当前代码与调用链分析

### 5.1 Schema 与关系推导

`AUTH/auth-platform-core/src/main/resources/schemas/knowledge.zed:43-54` 定义 document owner/editor/commenter/viewer/public_viewer 以及 `edit/comment/view`。`RealKnowledgeAuthz.java:86-93` 写 owner+parent_space，因此 owner 同时拥有 edit/view；`:104-112` 写删 viewer，与 schema 一致。

D3 不由 document create 自动完成：`RealKnowledgeAuthz.java:44-46` 明确 default space 无 viewer。`GroupSyncService.java:53-88` 只维护 group#member；D3 binding 位于 `deploy/rag-authz-fixture.sh:36-48` 与可选 `casdoor-tenant-provision.sh:92-98`。因此查询语义正确，但关系前置可能缺失。

### 5.2 资源与主体

所有 document write/check/bulk 最终经 `KnowledgeResourceIds.document:12-13`；AOP full id 经 `DocumentShareController:51-52` 同一 helper。没有缺前缀或双前缀生产调用。

可信身份链：`CasdoorTokenExchangeFilter.java:106-115` 取 owner/sub；`InternalToken.java:105-110,128-136` 签发/验签 tenant/uid；`InternalTokenAuthFilter.java:59-73` 重建 ThreadLocal。RAG query 已一次快照；DocumentService/AOP 多读同一不可变 ThreadLocal record，当前无实际不一致证据。

### 5.3 Bulk 对齐

`ResourceRef.java:4-12` 是 record，type/id 完全相等即可回读。knowledge 构造请求与 `allowed.get` 均用相同 helper。SDK `RemoteAuthzEngine.java:78-105` 校验数组、基数、请求集合、重复并按请求顺序回填；server `AuthzController.java:34-40` 回显 resource。分批 key 不重叠，`putAll` 正确；任一后续批异常时 catch 返回全集/空集，不会泄出局部 allow。

唯一缺口是 `RemoteAuthzEngine.java:96` 未验证 `allowed` 的存在和 boolean 类型；`check:56` 同样静默 false。

### 5.4 过滤时机与公共命中

`KnowledgeQueryService.java:296-302` 的顺序是 fuse→filter→rerank→response。未经授权正文不会进入 reranker/LLM；日志只记录 tenant、user、id 和数量。shared 标志由分区选择产生：vector/ES 分开查询 public tenant，keyword 从 public partition 判定；`HybridFusionService.java:75-90,105-119` 对冲突取 AND，偏向 tenant，当前不可由外部请求伪造。

### 5.5 跨服务缓存旁路

`ConversationController.java:74-84` 在 RAG 前调用 cache；`SemanticCache.java:56-68` 仅以 tenantId 查写，命中直接返回 reply。缓存条目没有 source docIds 或授权版本；knowledge 的缓存失效只在 upload/delete 后调用（`DocumentService.java:277,354`），share/unshare 不调用，且 invalidator 是 best-effort、默认关闭。这会让正确的 full check 在 cache hit 时根本不执行。

## 6. 十二维度逐条结论

### 1. permission 与资源类型 — 正确；D3 前置存疑

证据：schema `knowledge.zed:43-54` 与所有 view/edit/document 调用一致。owner 可推导 view/edit。风险：D3 tuple 缺失造成误拒（中），触发为新 tenant 未执行 wiring/backfill。最小修复：enforce gate 验证 binding/membership/parent_space。

### 2. 租户前缀 — 正确

证据：`KnowledgeResourceIds.java:11-18`，Real write/check/bulk `:87-92,100,106,112,120,148`，controller `:51-52`。当前没有串权、漏前缀、双前缀。加固：AOP service 内只接裸 docId 并自行派生 full id，避免未来 caller 错配。

### 3. 主体身份 — 正确；低风险加固

userId 均来自验签后的 TenantContext，写 owner 与查权限使用同类 ID。RAG 已单次快照；DocumentService/AOP 多次读取在同步 ThreadLocal 请求内稳定。建议后续入口快照，非阻断修复。

### 4. checkBulk 对齐 — 缺陷（低）

map key/分批/漏 key处置正确；SDK对 resource 严格。缺陷是 allowed 字段未严格校验。触发为 AP 版本漂移、代理截断或畸形 JSON；不会 false allow，但 shadow 将协议错误记成 deny。最小修复：只接受 boolean，single 同步修复。

### 5. 一致性 — 正确；性能待验证

full 对授权/撤权即时生效与多副本最稳，不存在 correctness 风险。纯读可能过度并增加超时/空结果；先压测，再评估 ZedToken/at-least-as-fresh，不能无数据降级。

### 6. shadow/enforce/disabled — 直接路径正确；分享语义待确认

Noop、Real single/bulk 分支正确。分享仅 enforce 存在，符合早期批准规则，但无法观测 would-deny。若产品要求 shadow 分享，必须采用 mode-aware 方案整体改 controller/service/aspect，不能只放开条件。

### 7. 依赖/协议故障 — 安全正确；观测缺口

single/bulk catch `RuntimeException` 覆盖 RestClient 与解析异常；shadow open、enforce closed。AOP engine 异常不会 `proceed`，安全上 fail-closed，但通常变 5xx 且不计 knowledge authz error。F3 修复后协议异常会正确进入 error 路径。

### 8. 无 docId — 正确

`KnowledgeQueryService.java:343-351` enforce 丢弃 tenant null-docId；Graph/image 端点 enforce 整体关闭。无泄漏，代价是 GraphRAG/脏数据 recall 损失。

### 9. public/shared 短路 — 正确（当前真实源）

它是明确公共库语义，不是 document public_viewer。shared 由受控内部源/分区产生，融合冲突 AND 偏私有；strict/public 配置互斥。未来新增 RetrievalSource 必须证明 shared 来源，不得把布尔值当普通展示字段。

### 10. @CheckAccess — 正确；接口不变量可加固

外部 bean 调用、参数名、document/edit/full、完整 id 均由 `CheckAccessShareTest.java:89-123` 证明；AOP 依赖存在。edit 也符合既定规则。风险是 fullId/bareId 双参数可被未来 caller 错配，当前唯一生产 caller 安全。

### 11. overfetch 与 bulk-size — 授权正确；容量待验证

`computePoolLimit:311-320` 取 multiplier 最大值而非相乘并封顶；bulk 分批不会漏判或超出配置。候选不足只造成 false negative/underfill，不造成越权。生产 SpiceDB 版本/限制和 SLO 待验证。

### 12. 过滤时机/日志 — knowledge 内正确；平台整体有条件缺陷

过滤发生在 rerank/response 前，未发现先返回后过滤或正文日志。F1 semantic cache 在另一个服务 pre-RAG 短路，是端到端高风险条件缺陷；authz 开启期间必须保持缓存关闭。

## 7. 候选方案对比与评分

5 分越优；风险/复杂度/测试/回滚维度均按越低越高分。

| 维度 | A 外科式加固 | B 统一 mode-aware 门面 | C lookup 优先+授权缓存 |
|---|---:|---:|---:|
| 正确性 | 4.6 | 4.7 | 4.4 |
| 改动风险 | 4.8 | 3.2 | 1.7 |
| 复杂度 | 4.8 | 3.3 | 1.5 |
| 可维护性 | 4.2 | 4.6 | 3.2 |
| 扩展性 | 3.2 | 4.2 | 5.0 |
| 测试难度 | 4.5 | 3.2 | 1.5 |
| 回滚成本 | 4.9 | 3.7 | 1.8 |
| 总分 | **31.0** | 26.9 | 19.1 |

- A：保留 bounded post-fusion checkBulk，修 SDK strictness，D3/cache 变发布门禁。
- B：统一 single/bulk/share 模式与身份快照；设计更整洁，但可能把 shadow 分享 404 改为 2xx，超出审计修复。
- C：lookup allowed set 下推源并给缓存加授权版本；长期能力强，但高基数、事务和迁移风险过大。

## 8. 最终方案及选择原因

采用 **A 为主，选择性吸收 B 的低风险加固**：

1. 修复 SDK single/bulk allowed 类型校验。
2. 保留 knowledge 当前调用、permission、full consistency、filter 时机与 mode 行为。
3. 将 D3 关系完整性和 semantic-cache=false 设为 enforce 硬门禁。
4. 增加 AOP dependency-error、DocumentService 三态和真实 D3 测试。
5. 身份单次快照/fullId-bareId 收敛列为低优先加固；只有在不改变 API 时实施。
6. shadow 分享不自动改；先解决 Q1。

选择原因：它覆盖全部已证实缺陷，合法请求/响应和外部 API 不变，回滚最简单，也不引入 lookup 全集和授权 epoch 这两个新分布式问题。

已知弱点：长期仍不能在细粒度 authz 下开启现有 tenant-wide semantic cache；D3 仍由显式业务 manifest 管理；full consistency 性能尚待实测；发布门禁若被绕过仍会误配置。

## 9. 精确修改清单

### 9.1 确需修改

| 仓库/文件 | 类/方法 | 修改 |
|---|---|---|
| auth-platform `auth-platform-sdk/.../RemoteAuthzEngine.java` | `check`, `parseCheckBulk`，计划新增私有 boolean 解析 helper | 要求 allowed 存在且 JSON boolean；异常信息不含敏感 body |
| auth-platform `auth-platform-sdk/.../RemoteAuthzEngineTest.java` | 解析测试 | 补 single/bulk 缺失、null、错误类型测试 |
| auth-platform `deploy/rag-authz-fixture.sh` | APPLY 后验证段 | 写后读回/实际 check D3；验证失败非零退出 |
| langchain4j `deploy/smoke-rag-tenant-authz.sh` | preflight/E2E | 明确 cache 必须关闭；加入 member/stranger/跨租户、grant/revoke、AP 故障门禁 |
| langchain4j `knowledge-service/.../authz/RealKnowledgeAuthzTest.java` | single/bulk tests | 第二批失败、部分 map、error/deny 指标 |
| langchain4j `knowledge-service/.../KnowledgeQueryServiceAuthzTest.java` | filtering tests | shared 冲突 AND、reranker 不见 deny 正文、边界池大小 |
| langchain4j `knowledge-service/.../authz/CheckAccessShareTest.java` | AOP test | engine 异常时 viewer 写 never |
| langchain4j `knowledge-service/.../authz/KnowledgeAuthzIntegrationTest.java` | 真实 schema 场景 | 增 D3 member/非成员/跨租户，现有显式分享场景保留并说明无 D3 fixture |
| 两仓运维/RAG/semantic-cache 文档 | 对应章节 | 写清互斥、前置、故障姿态、回滚 |

### 9.2 条件修改（不在默认实施范围）

Q1 若决定 shadow 也暴露分享，再修改 `KnowledgeAccessConfig`、`KnowledgeAccessApplicationService`、`DocumentShareController`、`AuthzExceptionHandler`，采用统一 mode-aware 门面；不得直接在 shadow 注册现有 AOP，否则 deny 会硬拦。

低风险身份加固可修改 `DocumentService.upload/list/get/delete` 在方法入口一次快照；分享服务只接 tenant snapshot+裸 docId 并内部派生 full id。此项必须保持外部 API 不变。

### 9.3 明确不改

`knowledge.zed`、`AuthzEngine` 公开端口、`AuthzController` DTO、`KnowledgeResourceIds` 编码、`/rag/query` 与 `/v1/check(-bulk)` 外部结构、数据库表、消息结构均不改。

## 10. 数据库、接口、配置、消息结构变更

- 数据库：无 SQL/Redis schema 迁移。
- SpiceDB schema：无变更；关系数据需有 D3 binding、membership、历史 parent_space，对账而非推断 owner。
- 接口：合法 `/v1/check`/`check-bulk` 响应不变；畸形 allowed 由静默 deny 变异常。业务 HTTP API 不变。
- 配置：建立发布约束 `RAG_AUTHZ_MODE in {shadow,enforce} ⇒ CONVERSATION_SEMANTIC_CACHE_ENABLED=false`；记录并冻结 SpiceDB 镜像版本。是否增加机器可读 validator 的具体载体由部署体系确认。
- 消息：无新增事件/消息。长期 cache epoch 方案不在本轮。

## 11. 分阶段实施步骤与依赖

### 阶段 1 — 数据结构与领域模型

1. 冻结审计快照对应 commit/制品版本和 SpiceDB 镜像版本。
2. 形成每 tenant 的 D3 manifest：tenant、membersGroup、defaultSpace、member subjects、document parent_space；owner 只取可信证据。
3. 固化 authz/cache 配置兼容矩阵；shadow/enforce+cache=true 为非法发布组合。

完成标准：manifest 可 dry-run/对账，无未知 owner 被补造；所有生产 tenant 有明确 D3 期望；版本可复现。

### 阶段 2 — 核心业务逻辑

1. 修改 SDK single/bulk boolean strictness。
2. 补协议单元测试。
3. 保持 knowledge 现有 check/checkBulk 实现不变；仅补测试发现的回归修复。

依赖阶段 1 的协议/版本冻结。完成标准：畸形响应全抛异常；合法响应、乱序对齐和现有调用结果不变。

### 阶段 3 — 接口与适配层

1. fixture APPLY 后验证 D3；provision 文档明确 enforce tenant 必须 wiring。
2. E2E/preflight 阻断 cache 冲突与缺 D3 的 enforce。
3. 若批准低风险加固，统一入口身份快照和 AOP id 派生；不改变 endpoint。
4. Q1 未决定前不改 shadow 分享装配。

依赖阶段 2 SDK 制品已被 knowledge 使用。完成标准：绕过脚本无法得到绿色发布门；所有模式 bean 矩阵符合规则。

### 阶段 4 — 测试

1. 单元、Spring AOP、模块回归。
2. 真实 AP+SpiceDB schema 集成。
3. AP down/timeout/401/500/坏 JSON/第二批失败故障注入。
4. 真实 Casdoor→edge→knowledge→AP→SpiceDB required E2E。

完成标准：`test-plan.md` 全部通过；required E2E 不允许 skip；deny 正文不进 reranker，跨 tenant 永不 allow。

### 阶段 5 — 文档与最终检查

1. 更新 RAG、semantic-cache、部署和回滚文档。
2. 重跑全仓授权调用扫描并更新清单。
3. 核对 git diff 只含批准范围，确认无 schema/API/消息意外变化。
4. 安全负责人签署 D3 manifest、cache 互斥、故障注入结果。

完成标准：文档与运行配置一致；监控/告警存在；验收清单逐项签字。

## 12. 测试方案

详见 `test-plan.md`。核心验收集合：

- SDK：合法/乱序、少/多/重复/错 resource、缺/错 allowed、空/坏 JSON。
- schema：owner/viewer/editor/default-space member/stranger/跨 tenant。
- mode：disabled 零 AP；shadow deny/error 均放行并区分指标；enforce deny/error 均不返回正文。
- bulk：边界分批、第二批失败整次降级、map key 精确回读。
- path：query/list/get/delete/overwrite/share/unshare/public/null-docId/graph/image。
- cache：authz 非 disabled 时冲突配置发布失败。
- AOP：外部代理、参数名、full id、dependency exception never writes。

## 13. 风险、监控、灰度与回滚

### 风险与控制

| 风险 | 控制 |
|---|---|
| semantic cache 绕权 | authz shadow/enforce 期间强制关闭；长期方案另立项 |
| D3 缺 tuple 误拒 | manifest + write/read/check preflight；缺失不得 enforce |
| AP 故障造成空结果 | error rate/latency 告警；enforce 保持安全，必要时业务批准后回 shadow |
| full consistency 延迟 | 监控 `knowledge.authz.check_bulk.latency`、请求 P95/P99、timeout；不因压力自动 fail-open |
| bulk underfill | `knowledge.authz.underfill/candidates/allowed_docs` 比率告警 |
| public bypass 漂移 | 新 RetrievalSource 安全评审；strict/public 启动校验 |
| share AOP 部分装配 | 三模式 context test；启动 smoke |
| 版本不可重复 | 固定 SpiceDB 与 SDK 制品版本，记录 checksum/commit |

### 灰度

1. disabled 基线：结果量、延迟、缓存状态。
2. shadow：cache 必须关闭；观察 deny/error/would_filter、underfill 和 D3 差异至少一个完整业务周期。
3. 单 tenant enforce：D3 preflight 通过，故障演练完成。
4. 扩租户：逐 tenant manifest 验证，不做全局一次性切换。

### 回滚

- 首选切 `RAG_AUTHZ_MODE=shadow`，保留关系写和观测；若 AP 故障持续且业务批准，再切 disabled。
- 回滚不删除 SpiceDB tuples、不重置数据库、不回滚 schema；这样可快速再进 shadow。
- SDK 修复回滚一般不需要；若确因兼容异常回滚，必须先证明 server 输出非标准 allowed 的原因，不能长期恢复静默 false。
- cache 不因 authz 回滚自动开启；必须独立审批。

## 14. 最终验收清单

- [ ] 全仓只有 knowledge-service 引入 SDK；lookup 调用仍为 0，新增调用已审计。
- [ ] 所有 query/list/get/delete/overwrite/share/unshare 的 subject/resource/permission/full 与表一致。
- [ ] `KnowledgeResourceIds` 是 document/space id 唯一构造入口。
- [ ] SDK 对 missing/wrong-type allowed 抛协议异常。
- [ ] bulk 乱序、分批、第二批失败、缺 key均满足安全结果。
- [ ] owner、editor、viewer、D3 member、stranger、跨 tenant 的真实 schema 结果通过。
- [ ] 每个 enforce tenant 的 D3 binding/membership/parent_space preflight 通过。
- [ ] authz shadow/enforce 环境实际 `CONVERSATION_SEMANTIC_CACHE_ENABLED=false`。
- [ ] disabled 零调用；shadow fail-open+指标；enforce fail-closed。
- [ ] public/shared 仅受控源可置 true；strict/public 冲突拒绝启动。
- [ ] null-docId tenant 命中 enforce 丢弃；Graph/image enforce 关闭。
- [ ] 未授权正文不进入 reranker/响应/日志。
- [ ] AOP 真实代理生效，deny/异常均不写 viewer。
- [ ] required E2E 与故障注入通过且未 skip。
- [ ] P95/P99、error、underfill 告警阈值经压测确认（具体数值待验证）。
- [ ] 灰度、回滚、关系保留策略已演练。

## 15. 交付给实施 Agent 的执行顺序

先读本文件与 `test-plan.md`，锁定两仓库快照；按阶段 1→5 连续执行。禁止先改 knowledge 查询架构；先修 SDK strictness、建立 D3/cache 门禁并完成故障测试。Q1 未获明确决定不得改变 shadow 分享 API。遇到生产 SLO、SpiceDB bulk 上限或 D3 manifest 数据缺失时标“待验证”并阻断对应 tenant enforce，不自行猜测。

---

## 16. Claude 跨模型复核补充（2026-07-15）

以下为 Claude 对照真实代码复核 Codex 报告后的结论与增补，不改变第 8 节所选方案（A）。

### 16.1 已逐行核实的结论

- **对“查询权限调用是否正确”这一原始问题的直接回答：正确。** knowledge-service 的全部 `check`/`checkBulk`/`@CheckAccess` 调用，其 permission（读=view、改/删/分享=edit）、资源类型（document）、租户前缀（`<tenantId>_<docId>`，统一经 `KnowledgeResourceIds`）、主体（验签后 `TenantContext.userId()`）、一致性（fullyConsistent）、shadow/enforce/disabled 三态与 fail-open/closed 姿态均正确；未发现“应拒却放行”的越权缺陷。F1/F2/F3 均为**端到端/邻接**问题，不是判权调用本身写错。
- **F3 已核实为真**：`RemoteAuthzEngine.check`（`RemoteAuthzEngine.java:56`）`post(...).path("allowed").asBoolean()`；Jackson `path()` 对缺失字段返回 MissingNode，`asBoolean()` 静默得 `false`。`parseCheckBulk` 对基数/资源对齐/重复**是严格的**（与记忆一致），但对 `allowed` 布尔值本身（`item.path("allowed").asBoolean()`）未做 `isBoolean()` 校验。后果=fail-closed（不越权），但畸形/缺失响应被静默记为 deny，污染 shadow 指标、掩盖协议漂移。严级“低”准确。
- **F1 已核实为真**：`SemanticCache.getOrCompute` 在 `ConversationController.java:78` 于 RAG 前调用，缓存桶**仅按 tenantId 分桶**（`store.findNearest(tenantId, vector)`），命中直接返回 reply 并短路整条 RAG+判权链；默认 `app.conversation.semantic-cache.enabled=false`。开启且 enforce 时，同租户内可跨用户复用回答、撤权后仍命中旧答——真实端到端授权缺口。

### 16.2 Claude 增补的核实项（Codex 未充分强调）

- **【重要】双 Maven 仓库导致“审计对象≠部署制品”风险**：本审计所依据的 `auth-platform-sdk/.../RemoteAuthzEngine.java`（含严格 `parseCheckBulk` 与 F3 缺口）是**工作树未提交源码**（`git status` 显示 `M`）。而 langchain4j-platform 使用自定义本地仓库 `/Users/liruijun/personal/repository`（**非** `~/.m2`）。若未执行
  `./mvnw -pl auth-platform-protocol,auth-platform-sdk install -DskipTests -Dmaven.repo.local=/Users/liruijun/personal/repository`，
  则 langchain4j 实际编译/运行的可能是**旧版 SDK**——旧版若仍是“按下标盲映射”的 checkBulk，则乱序/漏项时会把 A 的判定安到 B 头上（真正的越权面）。**实施前置动作**：先确认 langchain4j 构建消费的 SDK 版本确含严格 `parseCheckBulk`（比对已安装 jar 的字节码或版本号），否则本报告“bulk 对齐正确”的结论对部署制品不成立。此项应加入阶段 1“冻结版本”的验收。
- **F3 的 single-check 修复不要遗漏**：修 SDK 时 `check`（single）与 `parseCheckBulk`（bulk）需同批加 `isBoolean()` 严格校验，二者是同一缺口的两处。

### 16.3 复核后对方案的确认

维持方案 A（外科式加固）为主、选择性吸收 B 的低风险加固。方案集合（A/B/C）经核实与真实代码契约一致，评分合理。唯一对实施顺序的强化：把 16.2 的“SDK 制品版本核对”提到阶段 1 最前，作为整个审计结论对生产是否成立的前提。
