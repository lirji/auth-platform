# FINAL_PLAN — 全平台权限接入 Casdoor（迁移 + 灰度 + 收敛 legacy）

> 由 Codex 多视角规划（requirements / codebase-analysis / solution A–D / comparison / test-plan）+ Claude 跨模型复核合成。Codex 在写完 comparison/test-plan 后到 10 分钟超时被杀，未及写本文件；本 FINAL_PLAN 由 Claude 依据其产物 + 对真实仓库的逐条核验补全，并**修正了用户初始前提中的一处关键错误**（见 §0）。

## 0. 结论先行 + 关键前提修正（务必先读）

**采用方案**：Codex 方案 **A（声明式 manifest + 幂等 reconcile 脚本 + 单栈 feature-gated strangler）** 为主，吸收 B 的「独立 oidc canary 构建」做小流量灰度、C 的「plan/diff/journal/单写者/drift 报告」原则（但不建在线控制器），拒绝 D 的人工硬切。

**⚠️ 前提修正（Claude 复核实测，推翻用户与我先前的口径）**：
> 我先前告诉用户「判权机制已全平台就绪，各服务端点 `TenantContext.hasScope()` 缺 scope→403」。**这对绝大多数服务是错的。** 对 langchain4j-platform main 全量核验（`grep hasScope/require*Scope` 遍历所有 Controller）：
> - **真正强制 scope 的只有 4 个 Controller**：`AdminController`(role-admin)、knowledge `DocumentController`(ingest/write)、`MultimodalImageSearchController`(ingest)、`WorkflowController`(approve)。
> - **chat / agent / analytics / channel / eval / vision / voice / interop / async 全部只在内部 JWT 里携带 scope、但从不校验** —— 这些端点对任何已认证用户开放，与其 Casdoor 角色无关。
> - `InternalTokenAuthFilter` 只绑定身份、不拒绝匿名；解析失败也以 `TenantContext.ANONYMOUS` 继续。
>
> **含义**：把「权限真相源」换成 Casdoor（身份 + 已有的 3 类门禁）靠迁移+灰度即可；但要让 **11 项 scope 真正在全平台被强制**（用户「把整个项目权限都接入」的字面目标），**必须新增服务端 scope enforcement**。这是本轮的**核心待决策 + release blocker**，不是可选项，也绝不能只改前端 `requiredScopes` 冒充。

## 1. 目标 / 非目标

**目标**
- Casdoor 成为**人类身份 + 粗粒度授权（scope）的唯一真相源**：user∈org(=tenant)，user→role→permission(=scope)，edge 验 Casdoor token 写 `permissions∩allowlist(11)` 进内部 JWT。
- 全平台灰度：edge `EDGE_CASDOOR_ENABLED` off→on、前端 `VITE_AUTH_MODE` apikey→dual→oidc，每步可观测、可反向。
- **补齐服务端 scope 强制**（见 §0，Phase B-0），使 5 角色 × 11 能力矩阵得到确定的 403/200。
- 收敛下线人类 legacy：session Bearer、`/auth/**`、auth-service 运行实例、人类静态 API Key。

**非目标**（对齐 4 决策 + Codex requirements §4）
- 不改内部 JWT claim 形状 / `X-Internal-Token` 名 / 下游传播协议；不给每个服务重装 OIDC 验签（只 edge 验）。
- 不读/迁 auth DB（用户视为 dev seed：alice@acme=admin、bob@globex=viewer、analyst-a@tenantA=analyst，来自 `SeedUsers/SeedRoles`）；不迁 SpiceDB 历史 subject（新写入用 Casdoor sub）。
- **保留机器 `X-Api-Key`**（`ApiKeyToInternalTokenFilter` 不删）；client_credentials 全面替换 api-key 属二期。
- 单 org=单 tenant，多 org 用户不支持（检测到即失败）。
- 不删 auth DB / 源码 / 备份（不可逆动作另行审批）；本轮停在**可逆的运行时下线态**。

## 2. 采用方案与否决理由

| 方案 | 总分 | 结论 |
|---|---|---|
| **A 声明式单栈 strangler** | 28 | **采用为主**：最复用已合入链路、阶段独立、每层反向开关、成本适中 |
| B 蓝绿双入口 | 27 | 否决为主线（当前无成熟双入口 OIDC 流量基础设施，非业务故障面大）；**仅吸收**其「独立 oidc canary 构建/URL」做 build-time flag 的真实小流量灰度 |
| C 长期在线控制器（扩 auth-platform-admin） | 23 | 否决（为迁 3 条 demo 造在线高权限控制器=过度设计 + 新攻击面）；**仅吸收**其 plan/diff/journal/单写者/drift 原则用于脚本 |
| D 人工 UI 硬切 | 11 | 拒绝（不可证明幂等、易漏顺序、回滚脆弱、无审计） |

## 3. 阶段划分（每阶段可独立验证 + 反向回滚）

### Phase A｜Casdoor 成为权限真相源（纯 Casdoor 侧，不动运行服务，最安全先做）
把 `deploy/casdoor-seed.sh` 演进为**默认 dry-run、显式 apply、只增改不删**的 reconcile，输入一份**无密钥 manifest**（`deploy/casdoor-rollout-manifest.json`：org/user/期望 role/enabled，不含密码/secret）。
- 步骤（有向 + journal 补偿，Casdoor 无跨对象事务）：① 每 tenant 建/核对同名 org；② 建 user 保持单 owner；③ 建 org 下 5 角色；④ **先把 user 分配到 role**；⑤ 再更新/重建 11 permission（若实测有 `update-permission` 则原位更新，避免 delete+add 空窗）；⑥ 每角色样本签 token 校验 `owner/sub/permissions`（**强制 post-hook，非人工记忆**）。
- 严格错误分类：现脚本 `add-role ... || true` 把「已存在」与 401/5xx/网络错误混同 → 改为按 HTTP 码分类、`--fail-with-body`、非幂等错误即停。
- 并发单写者（同一 org 同时只一个 reconcile）；默认不 `--prune`。
- **待验证（Codex 分析时本地 :8000 不可达）**：`add-user/update-user/update-permission` 等写端点的存在性与 JSON 字段——实施首步先对本地 Casdoor 探活确认，不得凭经验填字段。
- **验收**：空库/已存在库重复执行第二次 dry-run 零差异；无未知对象被删；每 user 新 token claim 符合 manifest。**回滚**：不开任何运行开关，Casdoor 数据可重跑/手工清理，运行流量零影响。

### Phase B-0｜服务端 scope 强制（**新增必做门，§0 缺口**）— 需用户拍板实现方式
先跑「5 角色 × 11 能力」黑盒矩阵确认现状（预期：chat/agent/analytics/channel/eval/vision/voice 无 403 → 门缺失）。然后补齐 enforcement。**推荐方案：edge 集中式 route→scope 门禁**（单点、最小侵入、契合现有 edge-centric 认证模型）：
- 新增 `EdgeScopeEnforcementFilter`（order 介于 -100 与路由之间，如 -95）：读已换发的内部 JWT scopes，按 `edge.authz.route-scopes`（路径前缀[+方法] → required scope，如 `/chat/**→chat`、`/agent/**→agent`、`/analytics|/chat/sql→analytics`、`/channel/**→channel`、`/eval/**→eval`、`/vision/**→vision`、`/voice/**→voice`）缺 scope→403；open path / 已有更细的下游门（knowledge ingest、workflow approve）不冲突（edge 粗门 + 服务细门=防御纵深）。
- 与前端 `capabilities.yml`/`SCOPE_ALLOWLIST` 对齐（前端多数 `requiredScopes` 现为空，本阶段一并补上，仅用于预判/展示，服务端 edge 门才是安全边界）。
- **备选**（若用户不要 edge 集中式）：逐 Controller 加 `TenantContext.hasScope` —— 触及 ~15 Controller，分散、侵入大，不推荐。
- **验收**：5×11 矩阵**确定的 403/200**（viewer 只 chat 通、editor +ingest、analyst +analytics、approver +approve、admin 全通）；关闭 `edge.authz.enforce`（默认可先 shadow-log 再 enforce）即回退。**回滚**：开关关闭 → 恢复「携带但不强制」现状。
- **⚠️ 这是本轮工作量的大头**（真代码 + 测试），也是「都接入」的实质。**若用户只要身份统一 + 现有 3 类门禁，可将本 Phase 降级为二期**，但须明确记录「chat/agent/… 对任何登录用户开放」的现状。

### Phase B｜全平台灰度启用（DR-11 顺序）
- 配置契约预检（发布前静态）：前端 `VITE_CASDOOR_CLIENT_ID` ∈ edge `edge.casdoor.audiences`；前后端 11 scope allowlist 逐字一致；issuer/JWKS/redirect/CORS 正确；edge enabled 且缺必需配置即启动失败（已实现，复用）。
- 顺序：① edge `EDGE_CASDOOR_ENABLED=true`（各环境）→ ② 前端 `dual`（Casdoor 优先、api-key 兜底、观测）→ ③ 前端 `oidc`。**每步观测窗 + 反向开关**。build-time flag 的按比例灰度用 B 的独立 oidc canary 构建/URL（不复制下游）。
- edge 补**低基数指标**：Casdoor/session/api-key 各自使用量、缺 claim、验签失败、header 超限（防 431 盲区）——不改判权语义。
- **验收**：dual 下 Casdoor token 与 session/key 并存正确；oidc 下人类流量全 Casdoor；矩阵仍绿。**回滚**：前端改回 `dual/apikey` 重构建、edge `EDGE_CASDOOR_ENABLED=false`，秒级。

### Phase C｜收敛下线人类 legacy（稳定观察后，仅运行时下线、不删源码/DB）
安全次序（每步独立开关 + 反向）：
1. session filter 加**独立开关** `SessionBearerAuthFilter` 先 shadow→停用（观察 session 使用量归零后）；
2. `EdgeOpenPaths` 收敛 `/auth/login|register|refresh|logout|public-config`（回调/第三方 webhook 保留）；
3. auth-service **只读冻结**（关 admin 写，防双真相源）→ 取消对外路由 → scale-to-zero（源码/DB 保留至观察期结束）；
4. 前端 oidc 隐藏/停用旧 RBAC 控制台与 API Key UI（大部分已由 ③ 完成）。
- `ApiKeyToInternalTokenFilter` **保留**（机器身份）；须盘点「人类 key vs 机器 key」，只下人类 key。
- **不可逆点**（删 route/secret/DB/源码）延后、单独审批、须可恢复备份 + 已演练恢复。
- **验收**：oidc 全量后 `/auth/*` 人类端点不可达、session Bearer edge 不再接受、auth-service 可缩 0；机器 key 仍经 edge 工作；下游直连不能用 `X-Api-Key` 拿生产身份（收紧 `allow-api-key-fallback` + 网络边界）。**回滚**：Phase C 前半各开关反向；**注意**（Codex 隐藏场景#12）删 `/auth` 后仅把前端改回 apikey **并不能**恢复账密登录，须同时恢复 edge route/session filter/auth-service。

## 4. 文件级改动（实施期）

**auth-platform（Phase A 主战场）**
- `deploy/casdoor-seed.sh` ✏️ → 严格错误分类 + dry-run/apply + reconcile + postcondition token 断言 + journal。
- `deploy/casdoor-rollout-manifest.json` 🆕 无密钥 org/user/role 期望态。
- `deploy/casdoor-rollout-smoke.sh` 🆕 配置契约 + token claim + 5×11 矩阵驱动验收。

**langchain4j-platform（Phase B-0/B/C）**
- `edge-gateway/.../EdgeScopeEnforcementFilter.java` 🆕（Phase B-0，若采 edge 集中式）+ `edge.authz.route-scopes/enforce` 配置 + 测试。
- `edge-gateway/src/main/resources/application.yml` ✏️ route-scopes、session 独立开关、指标。
- `edge-gateway/.../SessionBearerAuthFilter.java` ✏️ 配置门控 + legacy 指标；`CasdoorTokenExchangeFilter.java`/`ApiKeyToInternalTokenFilter.java` ✏️ 补指标（不改协议）；`EdgeOpenPaths.java` ✏️（Phase C 收敛 auth 路径）；对应测试。
- 前端 `capability-showcase-frontend/src/config/*` / `capabilities.yml` ✏️ 补 `requiredScopes`（与 edge route-scopes 对齐，仅预判）；隐藏旧 RBAC 入口（多数已完成）。
- `deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、Secret 示例 ✏️ Casdoor 配置、下游直连收敛、auth-service 缩容。
- 两仓 `README.md` / 前端 README / OIDC·RBAC 运维文档 ✏️ 真相源 + 回滚 runbook。

**明确不改**：`InternalToken` claim/wire、`TenantContext.Tenant`、`OutboundTenantForwarder`、auth-service JDBC 表、knowledge 历史 SpiceDB subject。

## 5. 风险与隐藏失败场景（Codex 12 项，全数保留）
角色加了但 permission 未刷新→登录成功却全 403（顺序坑 R6）；脚本把 401/5xx 当「已存在」；valid Casdoor + dual key 时 key 被剥离不合并（正确语义易误判）；JWKS 冷启动/轮转失败（dual 回落 session、oidc-only→401）；旧 access token 未过期→撤权有 TTL 延迟（非即时）；前端 client_id≠audience→全 401；两 allowlist 漂移；token 超 header 限→431 盲区；owner 错/多 org→写错 tenant（拒 header 覆盖 owner）；oidc 全量后旧 RBAC UI 仍可写 auth-service→双真相源；下游直连 `InternalTokenAuthFilter` 不拒匿名 + api-key fallback→靠网络边界；Phase C 删 `/auth` 后前端改 apikey 不能恢复账密登录。
**共同前提风险**：服务端 scope 门（§0）——已确认缺失，Phase B-0 必须闭合，否则「都接入」名不副实。

## 6. 总体验收标准
1. Phase A 幂等：二次 dry-run 零差异、无未知对象删除、每 user token claim 符合 manifest。
2. 配置契约检查通过（client_id∈audience、11 scope 逐字一致、issuer/JWKS/redirect/CORS、缺配启动失败）。
3. **5 角色 × 11 能力矩阵确定的 403/200**（Phase B-0 闭合缺口后）。
4. 灰度严格 edge on→dual→oidc，每步观测窗 + 反向。
5. oidc 全量后 `/auth/*` 人类端点不可达、session Bearer 不被接受、auth-service 可缩 0；机器 key 仍经 edge 工作；人类界面不再展示/发送 API Key。
6. 不可逆删除前有单独审批 + 可恢复备份 + 已演练恢复；本轮停在可逆运行时下线态。

## 7. 需用户拍板的关键决策
- **D-核心｜服务端 scope 强制（§0 / Phase B-0）**：`chat/agent/analytics/channel/eval/vision/voice` 当前**不校验 scope**。
  - 选项 1（推荐，真「都接入」）：本轮新增 **edge 集中式 route→scope 门禁**（`EdgeScopeEnforcementFilter`），11 scope 全平台强制。工作量大头在此。
  - 选项 2：本轮只做身份统一 + 灰度 + 收敛 legacy，**scope 强制降级二期**，明确接受「上述服务对任何登录用户开放」。
  - 选项 3：逐 Controller 加 `hasScope`（不推荐，侵入 ~15 Controller）。
- 其余 4 决策已拍板（保留机器 key / dev 重建 / dual→oidc / 跳过 subject crosswalk），本 FINAL_PLAN 已据此。
