# 实施进度 — 里程碑 A（①ReBAC，shadow 模式）

> 关联：[FINAL_PLAN.md](./FINAL_PLAN.md) §0 复核修正。本轮用户批准范围＝**里程碑 A + 并行整理阶段0 调研清单**（见 [PHASE0-RESEARCH.md](./PHASE0-RESEARCH.md)）。B/C 阻塞在阶段0，不在本轮。

## 里程碑 A 目标（验收标准）

knowledge-service 经 auth-platform SDK 把「某用户能否 view 某文档」的判定委托给 SpiceDB，**shadow 模式**端到端接通：
- 写路径：文档建/删时向 SpiceDB 写/删 `document:<tid>_<docId>` 的 `owner + parent_space` 关系；✅
- 读路径：RAG 融合后对候选 docId 双算 ReBAC 可见集，**只记指标、不拦截**（shadow）；✅
- 一致性正确（多副本安全）：读用 `FULLY_CONSISTENT`，不用单进程水位；✅
- AP server `/v1/**` 提供 service-credential 关口（默认关、生产须开，enabled+空 token 启动即失败）；✅
- 默认关（`app.rag.authz.mode=disabled`）时与接入前逐字一致。✅

## 决策记录（本轮对 FINAL_PLAN 的落地修正，附原因）

- **D1｜里程碑 A 走「编程式判权」而非 `@CheckAccess` AOP，且本轮不加 `SubjectResolver` bean。**
  原因：现有草稿已是编程式（`DocumentService`/`KnowledgeQueryService` 内直接调 `KnowledgeAuthz`），shadow 语义要「双算但不拦截」，而 `@CheckAccess` 天然是「不通过即抛异常」的 enforce 语义。**且**若此刻注册 `SubjectResolver` bean，会触发 SDK 的 `AuthzSdkAutoConfiguration` 装配 `CheckAccessAspect`（`@ConditionalOnBean(SubjectResolver)`），凭空引入一条无用途的 AOP 链。→ `@CheckAccess` + `SubjectResolver` + `KnowledgeAccessApplicationService` 一起推迟到 enforce 化（里程碑 A2）。
- **D2｜暂不引入 SpaceController/SpaceRegistry/SpaceInfo CRUD、状态机、Reconciler。** shadow 期不需要（FINAL_PLAN §0.3 修正4）。default space 以隐式常量 `<tid>_default` 存在。
- **D3｜default space 语义（实测确认）。** 文档只写 `owner + parent_space@space:<tid>_default`，default space **无 viewer 元组** → **enforce 后同租户他人文档不可见，仅 owner（及被显式授 viewer 者）可见**，与现状「租户内全体共享」相反，是行为收紧。这正是 shadow 要量化暴露的核心差异（`RealKnowledgeAuthz` 打点 `would_filter`）。若业务要「同租户共享」，需给 default space 绑 `viewer@group:<tid>_all#member`（依赖里程碑 C）。里程碑 A 不决定该策略。
- **D4｜AP server `/v1/**` 鉴权提前到里程碑 A。** 加共享 service-credential（Bearer），默认关（dev/smoke 兼容）、生产开。能力分级 allowlist 推迟到里程碑 C。

## 分阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| A-1 | 基线：install protocol+sdk 到 m2；knowledge-service 草稿可编译、现有测试绿 | ✅ 完成（162 tests 绿） |
| A-2 | shadow 化 + 修正：`AuthzMode`；`RealKnowledgeAuthz` 删水位改 `FULLY_CONSISTENT` + SHADOW 分支；接口 `enabled()`→`mode()`；`KnowledgeAuthzConfig` 按 mode 装配；yml 加 `app.rag.authz.mode` | ✅ 完成（162 tests 绿） |
| A-3 | AP server `/v1/**` 最小 service-credential（`OncePerRequestFilter`，不引 spring-security）；SDK 带 token | ✅ 完成（AP+knowledge 编译测试绿） |
| A-4 | 单测：`RealKnowledgeAuthzTest`（6）、`AuthzServerSecurityFilterTest`（6） | ✅ 完成（knowledge 168 tests、server 6 tests 绿） |
| A-5 | 文档 + 端到端真判权验证 | ✅ 完成（集成测试真跑通过） |

## 变更文件清单

### langchain4j-platform / knowledge-service
- `authz/AuthzMode.java`（新）— DISABLED/SHADOW/ENFORCE 枚举 + 宽松解析
- `authz/KnowledgeAuthz.java`（改）— `enabled()` → `mode()` + default `enabled()`
- `authz/NoopKnowledgeAuthz.java`（改）— `mode()` = DISABLED
- `authz/RealKnowledgeAuthz.java`（改）— 删 `AtomicReference` 水位；读 `FULLY_CONSISTENT`；SHADOW 双算记差异不拦截；双参构造带 mode（单参默认 ENFORCE 兼容集成测试）
- `authz/KnowledgeAuthzConfig.java`（改）— 按 `app.rag.authz.mode` 装配 shadow/enforce 两互斥 bean
- `src/main/resources/application.yml`（改）— 加 `app.rag.authz.mode` 与 `authz.client.server-url/token`
- `src/test/.../authz/RealKnowledgeAuthzTest.java`（新）— 6 单测
- （草稿既有：`DocumentService`/`KnowledgeQueryService`/`pom.xml` 已带钩子，未再改）

### auth-platform
- `auth-platform-server/.../AuthzServerSecurityProperties.java`（新）— `authz.server.security.*`
- `auth-platform-server/.../AuthzServerSecurityFilter.java`（新）— `/v1/**` Bearer 关口，常量时间比较
- `auth-platform-server/.../AuthPlatformServerApplication.java`（改）— `@EnableConfigurationProperties` 加 SecurityProperties
- `auth-platform-server/src/main/resources/application.yml`（改）— 加 `authz.server.security`
- `auth-platform-server/src/test/.../AuthzServerSecurityFilterTest.java`（新）— 6 单测
- `auth-platform-sdk/.../AuthzClientProperties.java`（改）— 加 `token`
- `auth-platform-sdk/.../RemoteAuthzEngine.java`（改）— 双参构造带 token（Bearer defaultHeader）
- `auth-platform-sdk/.../AuthzSdkAutoConfiguration.java`（改）— 用 `props.getToken()`

## 如何启用（试点）

```bash
# knowledge-service 环境变量
RAG_AUTHZ_MODE=shadow          # disabled(默认) | shadow | enforce
AUTHZ_SERVER_URL=http://auth-platform-server:8200
AUTHZ_SERVER_TOKEN=<与 server 一致>   # server 未开鉴权时留空

# auth-platform-server 环境变量（生产必开）
AUTHZ_SERVER_SECURITY_ENABLED=true
AUTHZ_SERVER_TOKEN=<同上>
```
灰度：`disabled → shadow`（观测 `authz shadow ... would_filter=N` 日志评估影响）`→ enforce`。

## 阶段完成记录

### A-1 ✅
install protocol+sdk 到 m2；`mvn -pl knowledge-service -am install` + 单测：**162 tests, 0 fail, BUILD SUCCESS**。修正 reactor 陷阱：单跑 knowledge-service 须先 install 上游或带 `-am`（旧 m2 protocol 缺 `KnowledgeRuntimeView`/新 `KnowledgeHit` 会报 2 error）。

### A-2 ✅
6 文件改动，`mvn -pl knowledge-service test`：**162 tests 绿**。

### A-3 ✅
AP server+sdk `install -am`：BUILD SUCCESS；knowledge-service 重测：**162 tests 绿**。

### A-4 ✅
`RealKnowledgeAuthzTest` 6、`AuthzServerSecurityFilterTest` 6。knowledge-service 全量 **168 tests 绿**；server **6 tests 绿**。

### A-5 ✅
端到端：SpiceDB(docker) + auth-platform-server(:8200) + 灌 knowledge.zed。`KnowledgeAuthzIntegrationTest` **真跑通过（Tests run: 1, Skipped: 0, 1.1s, BUILD SUCCESS）**——不再 skip，证明 knowledge-service → SDK → :8200 → SpiceDB 全链路判权正确：alice(owner) 可见 / 同租户 bob 未授权不可见 / grant viewer 后可见 / revoke 后立即不可见（`FULLY_CONSISTENT` 使撤权即时生效，替代原单进程水位）。`deploy/spicedb-smoke.sh` 灌 schema + 断言 exit 0。
文档：本台账 + PHASE0-RESEARCH.md；两仓库 README 片段留到里程碑 A2（enforce 化）合并时统一更新。

---

# 里程碑 A2（enforce 化：补单资源判权 + `@CheckAccess` 示范）✅

## 目标
从 shadow 走向 enforce：补上单资源判权安全缺口（A 只在 query 列表路径过滤），并落地 `@CheckAccess` 声明式判权。用户选定**混合方案**：主链路编程式统一 mode + `@CheckAccess` 示范端点。

## 决策记录
- **D5｜`@CheckAccess` 示范端点（文档分享）仅在 `mode=enforce` 装配。** 原因：`@CheckAccess` 是「不通过即抛 `AccessDeniedException`」的硬 enforce，与 shadow「不拦截」相悖；分享是敏感授权变更，天然强判权、不参与观察期。disabled/shadow 均不暴露 `/share` 端点。SubjectResolver 也只在 enforce 注册，从而只在 enforce 触发 SDK 的 `CheckAccessAspect` 装配。
- **D6｜主链路 get/delete 走编程式 `checkDocument`（统一 mode）**，与 query 的 `filterReadable` 同受 `app.rag.authz.mode` 控制，shadow 双算记 `would_deny`、enforce 真拦（get→404 空、delete→false）。避免 get/delete 用 `@CheckAccess` 造成「shadow 下 query 不拦但 get 拦」的割裂。
- **D7｜SDK 的 `starter-aop` 是 `optional`**（不强加消费者），故 knowledge-service 显式引入 `spring-boot-starter-aop` 才能让 `@CheckAccess` 生效；`-parameters` 由 spring-boot-starter-parent 默认提供（`CheckAccessAspect` 靠参数名解析 resourceId）。

## 分阶段
| 阶段 | 内容 | 状态 |
|---|---|---|
| A2-1 | `KnowledgeAuthz.checkDocument`（Noop/Real）；`DocumentService.get/delete` 接入（enforce 拒绝→404/false，不泄露存在性） | ✅（171 tests） |
| A2-2 | `starter-aop` 依赖；`KnowledgeAccessConfig`(SubjectResolver)、`KnowledgeAccessApplicationService`(@CheckAccess edit)、`DocumentShareController`(/rag/documents/{docId}/share)、`AuthzExceptionHandler`(403)，均 enforce-only | ✅（171 tests，disabled 不装配） |
| A2-3 | `CheckAccessShareTest`（slice Spring AOP：无 edit→AccessDeniedException / 有 edit→授权）；集成测试扩 `documentApiAuthz_getAndDelete_enforced`（真判权） | ✅（**174 tests，含 2 真跑集成 + 2 AOP slice**） |

## A2 变更文件
### knowledge-service
- `authz/KnowledgeAuthz.java`、`NoopKnowledgeAuthz.java`、`RealKnowledgeAuthz.java` ✏️ — 加 `checkDocument`（shadow `would_deny` 打点 / enforce 真判 / `FULLY_CONSISTENT`）
- `lifecycle/DocumentService.java` ✏️ — get 判 view、delete 判 edit
- `authz/KnowledgeAccessConfig.java` 🆕 — enforce-only `SubjectResolver`
- `authz/KnowledgeAccessApplicationService.java` 🆕 — `@CheckAccess(edit)` share/unshare
- `controller/DocumentShareController.java` 🆕 — `/rag/documents/{docId}/share`
- `controller/AuthzExceptionHandler.java` 🆕 — `AccessDeniedException`→403
- `pom.xml` ✏️ — 加 `spring-boot-starter-aop`
- `test/.../authz/RealKnowledgeAuthzTest.java` ✏️（+3 checkDocument）、`CheckAccessShareTest.java` 🆕（2）、`KnowledgeAuthzIntegrationTest.java` ✏️（+1 真跑）

## A2 遗留 / 下一步
- **切 enforce 前必做**：先在试点租户开 `shadow` 观测 `would_filter`/`would_deny` 日志，评估 D3「同租户互不可见」的收紧影响。
- upload 暂未判 `space edit`（default space 无 editor 会全拒，卡上传）——保持现有 `ingest` scope 门禁；「同租户共享 default space」需里程碑 C 的租户全员组。
- 未落地：space CRUD/policy API、半失败 reconciler、状态机（仍为 FINAL_PLAN §8.4 的 enforce 生产健壮性项，按需推进）。

---

# 里程碑 B（完整，经 Casdoor）— 进行中

用户选定**完整 B（经 Casdoor 中央对账）**。B 的判权对齐要求 `TenantContext.userId = Casdoor sub`，故必须先做 **③ Casdoor SSO 身份切换**，② 组同步随后。

## 阶段0 实测 ✅（见 PHASE0-RESEARCH.md「已实测结论」）
本地 docker Casdoor(:8000) 实测：claim 契约（`sub/owner/groups/roles`）、读组/用户 API、`client_credentials`/`password` grant。**关键结论**：③ 是 B 前提；scope 展开归 Casdoor（不在 edge，见下）。

## ③ 详细设计 ✅ = [MILESTONE-B-STEP3-CASDOOR-SSO-DESIGN.md](./MILESTONE-B-STEP3-CASDOOR-SSO-DESIGN.md)
- 核心：edge 新增 `CasdoorTokenExchangeFilter`(-120)，验 Casdoor token→换发形状不变的内部 JWT，验不过透传 legacy（灰度）。
- **可扩展性修正（用户提出）**：edge **不维护 role→scope 表**；role→能力的展开归 Casdoor（权限中心），edge 只从 token 透传已展开的 scope + 固定 11 项 allowlist。**角色 5→5000，edge 零改动**。
- §10 待确认项已全部落定（scope 映射=复刻 SeedRoles 初始化进 Casdoor；audience；单 org=单 tenant；前端 oidc-client-ts）。

## ③ 阶段①（edge 后端）✅
| 项 | 内容 |
|---|---|
| `edge/CasdoorSecurityProperties.java` 🆕 | `edge.casdoor.*`（enabled/issuer/jwkSetUri/audiences/claims/scopeAllowlist） |
| `edge/CasdoorDecoderConfig.java` 🆕 | `ReactiveJwtDecoder`（JWKS）+ timestamp/issuer/audience validator，enabled-only |
| `edge/CasdoorTokenExchangeFilter.java` 🆕 | order -120；验过→mint(owner/sub/scope∩allowlist)+剥离 Authorization；验不过/无 Bearer→透传；缺 tenant/sub→401 |
| `edge-gateway/pom.xml` ✏️ | +`spring-boot-starter-oauth2-resource-server` |
| `edge-gateway/application.yml` ✏️ | +`edge.casdoor`（默认 `enabled:false`） |
| 测试 🆕 | `CasdoorTokenExchangeFilterTest`(5, mock)、`CasdoorJwksIntegrationTest`(1, **真实 Casdoor JWKS 验签**) |

**验证**：edge-gateway **17 tests 绿**（含现有 filter 未破坏）；真实 token 经 `NimbusReactiveJwtDecoder` 验签通过、`owner=built-in`/`sub` 正确。`enabled:false` 默认不影响现状。

## ③ 阶段②③（本地联调 + scope 对齐）✅
**scope 展开机制实证**：把业务 scope 建成 Casdoor **permission**（name=scope），关联到 role；Casdoor 在签 token 时把 `user→role→permission` 展开进 `token.permissions`（对象数组含 name）。edge 从 `permissions[].name` 提取 + allowlist 过滤——**role→scope 展开完全在 Casdoor，edge 零增长**（兑现用户的可扩展性顾虑）。

- **edge 提取升级**：`scopeClaim` 默认 `permissions` + `scopeNameField=name`；`extractCandidates` 支持对象数组（取 name）/字符串数组/空格分隔串。单测 `extractsScopeFromCasdoorPermissionsObjects`。
- **Casdoor seed 脚本** 🆕 `deploy/casdoor-seed.sh`：把 SeedRoles 的 5 角色 + 11 scope-permission（role→scope 映射）幂等初始化进 Casdoor。**关键顺序坑（已实测+注释）**：Casdoor 的 permission→role→user 展开在 permission【写入时】确定，`role.users` 后变不重算 → 必须先分配用户角色再（重）跑 seed；角色改为"不存在才建"以保留 users。
- **端到端实证**：
  - `admin`→`admin` role→token 带全 **11 scope**（`deploy/casdoor-seed.sh` + 分配 admin role 后实测）。
  - `CasdoorJwksIntegrationTest.filter_endToEnd_realToken_...`：**真实 Casdoor token → 真实 JWKS 验签 → filter 提取 permissions → 内部 JWT**，断言 `tenant=built-in`、`userId=sub`、`scopes ⊆ allowlist`、Authorization 剥离。edge-gateway **19 tests 绿**（2 集成测试需 `CASDOOR_CLIENT_ID/SECRET` env，否则 skip）。

## ③ 阶段④（前端 OIDC）✅（2026-07-15）
capability-showcase-frontend 接 Casdoor OIDC（Authorization Code + PKCE），构建期 `VITE_AUTH_MODE=apikey(默认)/oidc/dual` 三态灰度、可秒回滚、apikey 零回归。走了 `/frontend-plan`（计划：`~/.claude/plans/langchain4j-platform-auth-platform-iam-iridescent-papert.md`，DR-1~DR-11 + §13 全程进度），并经 `/codex-review` 两批修复 + DR-1 完整硬化。**已全部合入 langchain4j-platform `main`（4 提交）**：
- `ca13976 feat(knowledge)`：① SpiceDB ReBAC 文档级判权（disabled/shadow/enforce，默认关）。
- `5d8a3bc feat(edge)`：② Casdoor SSO 换发内部 JWT（`CasdoorTokenExchangeFilter` -120，默认关）。
- `25a1df0 feat(showcase)`：③ 前端 OIDC 阶段④（`oidc-client-ts` 薄封装、sessionStorage 存 token、双驱动 auth store、`/callback`、灰度门控）+ codex-review 批1（真 bug/安全）+ 批2（生产化：edge pom 换 `spring-security-oauth2-jose` 去自动安全链根治 CORS 预检 401、头大小进 yml、build args、回调路由用配置、credentialMode Casdoor-aware）。
- `4b3e729 feat(showcase)`：③ DR-1 完整硬化——SessionExpiredDialog + authorizedFetch 弹模态引导重登、humanizeError OIDC 分支、authPrompt 单一文案源、curl Bearer 占位符、SSE 中途断流续订、BroadcastChannel 多标签登出、nginx CSP。
- **验证**：前端 344 tests + type-check + 生产构建全绿；edge 22 tests + `mvn package`；运行期冒烟通过（CORS 预检 200、Casdoor 8.8KB token→200 不再 431、no-token 401、dual 200）；CSP 已在 :8093 nginx 实测。

## ③ 剩余阶段（下一步）
- 阶段⑤：灰度（三模并存→Casdoor default→Casdoor-only；`edge.casdoor.enabled=true` + 移除 legacy）。
- 阶段⑥：② 组同步 reconciler（Casdoor groups→SpiceDB role group→绑 space）+ 历史 subject username→sub crosswalk。
- 生产化：Casdoor 用户→角色的迁移（auth DB crosswalk）、真实 audience/多 org、RS256 内部 JWT；`server.max-http-request-header-size`/CSP 域名按 prod 落定。

---

# Codex 独立审查修复（2026-07-15）

`/codex-review` 发现 20 个问题；用户批准「全部真问题 + 测试补强」，已修 5 批（全绿：knowledge **180 单测 + 3 集成**、edge **21**、server **6**）：

- **判权缺口**：GraphRAG `docId=null` fail-open → enforce 丢弃（`KnowledgeQueryService`）；`DocumentService.list` 补判权；`GraphController`/图片检索 enforce fail-closed（无 docId 溯源）；同名覆盖判 `edit`（防夺权）、owner 仅新建写。
- **edge reactive/安全**：`onErrorResume` 收窄到仅 `JwtException`（不再把下游错误当验签失败重入）；剥离入站 `X-Internal-Token`（防客户端伪造绕过认证）。
- **一致性/健壮性**：`delete` 改「先撤关系再删数据」fail-closed（修正注释矛盾）；判权依赖故障 shadow fail-open / enforce fail-closed + `decision=error` 指标；`@CheckAccess(fullyConsistent=true)`（分享用强一致）；SDK `RestClient` connect/read timeout。
- **死代码/文档/seed**：删 `AuthzMode.from()`；抽 `KnowledgeResourceIds` codec（消除重复拼接）；修过时 Javadoc（`enabled`→`mode`）+ `DEFAULT_SPACE` 注释（与 D3 一致）；`casdoor-seed.sh` 加 `--fail-with-body` + 角色不覆盖 users；AP server `enabled=true` 空 token 启动即失败；文档「不再裸奔」等表述改准。
- **测试补强**：fail-open/closed 单测、`@CheckAccess` AOP 参数（subject/resource/consistency 精确断言，不再 `any()`）、剥离伪造内部头测试、edge 端到端断言非空+含 chat（不再空集也过）、list/同名覆盖集成测试。

**未改（已知/后续，Codex 也认属非隐瞒偏离）**：reconciler/状态机（enforce 生产健壮性）、图谱/图片纳入资源级 ReBAC（需数据带 docId）、Dingtalk `dingtalk:<staffId>`→Casdoor sub crosswalk（随 ② 迁移）、legacy filter 的入站内部头信任（既有架构，本次只堵 Casdoor 入口）。

**踩坑记**：langchain4j-platform 用自定义本地仓库 `/Users/liruijun/personal/repository`（非 `~/.m2`）——auth-platform 的 SDK/protocol 产物须 `install -Dmaven.repo.local=/Users/liruijun/personal/repository` 才能被 langchain4j 消费。

## Codex 复审修复（第二轮，2026-07-15）

`/codex-review` 复审确认 5 项已解决（list、GraphController 403、@CheckAccess 全链强一致、scope 非空断言、AOP 参数断言），并抓到几处不彻底/漏改，已再修（全绿：knowledge **181**、edge 21）：

- **#3 `onErrorResume` 彻底修**：改用 `map(Optional::of).onErrorReturn(empty)` 隔离——decode 的**任何**失败（验签/JWKS/网络）→ 透传给 legacy，decode 成功后的 mint/下游错误正常传播（不吞、不重入）。
- **#20 漏改 Javadoc**：sed 修正 `KnowledgeQueryService`/`DocumentService` 5 处 `app.rag.authz.enabled`→`mode`；`parent_space` 改用 `KnowledgeResourceIds.space()`。
- **`CasdoorDecoderConfig` 启动校验**：enabled 时 jwks/issuer/audiences 任一为空即拒绝启动（iss/aud 校验不可静默跳过）。
- **null-docId 准确化**：明确不只图谱三元组——缺 metadata 的向量/关键词命中也 `docId=null`；enforce fail-closed 丢弃并计数日志；注释/记录改准。
- **#7 并发（部分）**：owner 用 `CREATE`（非 TOUCH）——SpiceDB 拒绝第二个 owner，防并发/重复新建累积或接管 owner。
- **#9 澄清**：shadow fail-open **仅对读判权**；写路径（`onDocumentCreated/onDocumentDeleted`）失败仍传播（upload/delete 会失败）——完整原子性需 reconciler。

**仍属 enforce 前 backlog（需 reconciler 级工程）**：registry 层并发 CAS + 跨系统 saga（create/delete 孤儿窗口）、图片 ingest 纳入 ReBAC 生命周期、SDK 协议字段校验（缺字段静默 deny）、integration test profile（防 CI 全 skip）、Dingtalk `staffId`→Casdoor sub crosswalk、legacy filter 入站内部头信任（既有架构）。

**闭环**：Codex→Claude→Codex→Claude 两轮。快速正确性/表述问题已收敛；剩余为需要专门设计的生产健壮性项，enforce 化前处理。
