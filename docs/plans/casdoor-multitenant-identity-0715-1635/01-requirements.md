# 需求分析（requirements-analyst）

## 1. 任务定位

本计划补齐既有 `docs/plans/casdoor-authz-rollout-0715-1101/` 明确排除的多租户身份缺口。既有计划已经冻结以下能力，本计划不重复设计：

- Casdoor 是人类身份与粗粒度 scope 真相源；
- edge 验 Casdoor token 后换发内部 JWT；
- `VITE_AUTH_MODE=apikey→dual→oidc`、`EDGE_CASDOOR_ENABLED=false→true` 的可逆灰度；
- Phase B-0 的 `EdgeScopeEnforcementFilter`（`off/shadow/enforce`、route→scope）是服务端 scope 强制的唯一主线，见既有 `FINAL_PLAN.md:51-57`；
- legacy session、`/auth/**`、人类 API key 的最终收敛顺序，见既有 `FINAL_PLAN.md:65-73`。

本次核心是：让任意已开通租户都可从同一个平台前端选择自己的身份域登录，并确保该登录得到的 `tenantId/userId/scopes` 在 edge、业务服务、SpiceDB 和管理面中保持同一语义。

## 2. 已确认业务规则

### 2.1 身份和租户

1. 当前部署模型为“一租户 = 一个 Casdoor organization，一 organization = 一个 application”；仓库脚本明确生成 `rag-<tenant>` 和 `rag<tenant>client01`，见 `deploy/casdoor-tenant-provision.sh:45-47`。
2. 在该模型下，已验签 token 的 `owner` 是平台 `tenantId`，`sub` 是稳定 `userId`，`name` 是展示/登录用户名；edge 当前在 `CasdoorTokenExchangeFilter.exchangeAndForward()` 中按配置读取租户和主体 claim，见 `langchain4j-platform/edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java:106-115`。
3. scope 来自 `permissions[].name` 并与 11 项 allowlist 求交；未知 scope 不进入内部 JWT，见同文件 `:135-150`。
4. 浏览器选择的租户只是登录路由提示，不是授权事实。回调后 token 的 `owner` 必须与选择的租户及 token 的 `aud` 对应 application 三者一致；query/header/local state 均不得覆盖已验签 claim。
5. 同一个用户名可以出现在不同 org。当前 legacy `USERS.USERNAME` 是全局主键（`JdbcUserAccountStore.java:42-51`），因此迁移后管理标识必须从 `username` 升级为 `(tenantId, username)`；`sub` 仍是权限关系中的真正主体 ID。
6. 一个登录会话只绑定一个租户。跨租户工作需要显式登出/切换身份，不在一个 token 中合并多个 tenant；本期不支持“单 token 多 tenant”。

### 2.2 application、audience 与登录

1. 每租户 app 的 `client_id` 是公开路由信息，不是 secret；`client_secret`、用户密码和管理 token 绝不进入前端注册表、Git manifest、日志或 metrics。
2. 有效 token 必须同时满足：签名、时间、issuer、`aud` 已注册、`owner` 已注册、`(owner,aud)` 是同一启用租户的绑定。仅做 `aud ∈ 全部 client_id` 不足以证明归属。
3. 新租户开通后，前端和 edge 必须在不重建前端、不重启 edge 的情况下识别新租户；失败时继续使用上一个完整、已验证的注册表快照（last-known-good，LKG），不能接受半份文件。
4. 所有租户 app 必须登记相同的实际前端 callback、post-logout 和 silent-renew URI。当前脚本只登记 callback（`casdoor-tenant-provision.sh:65`），而前端声明三条 URI 都必须登记（`config.ts:64-71`），这是上线前必须闭合的现存缺口。
5. `VITE_AUTH_MODE` 的三态语义保持不变：`apikey` 不初始化 OIDC；`dual` 用 Casdoor Bearer 优先、API key 兜底；`oidc` 隐藏 API key。现有请求注入语义见 `capability-showcase-frontend/src/api/client.ts:111-126`。

### 2.3 权限、ReBAC 与缓存

1. 11 项 scope 的服务端强制直接依赖既有 Phase B-0，不在本计划另建第二套路由表或逐 Controller 方案。
2. knowledge ReBAC 的对象 ID 继续使用 `<tenantId>_<id>`，由 `KnowledgeResourceIds` 统一构造（`knowledge-service/.../KnowledgeResourceIds.java:1-28`）；采用 `owner=tenant` 的方案不改变该协议。
3. Casdoor group 同步进入 SpiceDB 时继续使用 `<org>_<group>`，并遵守 `CasdoorGroupIds` 的 v1 字符约束（`auth-platform-admin/.../CasdoorGroupIds.java:20-51`）。
4. `GroupSyncService.sync()` 目前是单 org、同步方法内串行、差量 TOUCH/DELETE，并带删除熔断（`GroupSyncService.java:53-90`）。真正多租户后必须遍历注册表中的启用 org，按 org 隔离失败、锁和指标。
5. F1 是发布硬门：当 knowledge authz 处于 `shadow` 或 `enforce` 时，conversation 语义缓存必须关闭。当前缓存只按 tenant 分桶、命中会绕过 RAG 和后续 ReBAC（`SemanticCache.java:52-69`），没有 user/授权版本维度。
6. F3 已就位，计划不得弱化：SDK 严格校验 check-bulk 基数、资源和 `allowed` 类型（`RemoteAuthzEngine.java:72-122`）；core 严格校验 SpiceDB `pairs/permissionship`（`SpiceDbAuthzEngine.java:59-91`）。

### 2.4 管理面和真相源

1. 当前前端 admin 是 legacy auth-service 的 BFF 客户端：`api/admin.ts` 明确调用 Bearer-only `/auth/admin/**`（`:1-47`），用户编辑器把 profile/role/group 拆成多次带 version 的写（`UserEditor.vue:192-252`）。
2. 最终态禁止“auth-service 写一份再异步同步 Casdoor”作为长期架构，否则用户、role、scope 会有两个真相源和不可控授权窗口。
3. 最终态采用 Casdoor-backed 管理适配层：浏览器不持 Casdoor 管理 secret；适配层把租户/user/role/permission 变更提交到同一声明式 reconcile 流程，返回 operation/plan 状态，而不是伪装成同步强事务。
4. 迁移窗口允许 legacy auth-service → Casdoor 的受控双写，但必须是单向、限时、带 outbox/journal 和对账；Casdoor 一旦进入 authoritative 状态，legacy admin 写先冻结再下线。
5. `GroupSyncService` 只负责 Casdoor group membership → SpiceDB tuple，不负责 scope 计算、用户创建或 app/audience 注册，避免职责混叠。

## 3. 目标

- 至少 acme 与 globex 两个不同 org 的用户可从同一 SPA 选择租户、完成 Authorization Code + PKCE、回调、静默续期和登出。
- 前端租户解析、edge audience/owner 绑定、Casdoor org/app 和 ReBAC 租户前缀使用同一份版本化注册表。
- 新租户通过声明式 manifest 与幂等 reconcile 完成 org→app→user→role→permission→token probe→registry publish；edge 无需重启，SPA 无需重建。
- 把 auth-service 的用户、角色、direct scope、租户基础角色、组与成员关系一次性 backfill 到 Casdoor/SpiceDB，并提供 subject crosswalk、差异报告和可重跑 journal。
- 灰度期 legacy session/API key 与 Casdoor token 语义明确、不合并权限；最终 human identity 收敛到 Casdoor。
- 与既有 Phase B-0 接线后，两个租户都通过 5×11 scope 矩阵且互不串租户。
- 支撑数百至上千租户：edge 请求热路径不调用 Casdoor Admin API；注册表查找 O(1)，更新原子替换；reconcile 按 org 限流和隔离。

## 4. 非目标

- 不改 `X-Internal-Token` 的名称和当前内部 JWT `tenantId/userId/scopes` 语义，不给每个业务服务新增 OIDC 验签。
- 不重做既有 Phase B-0 的 route→scope 规则和 shadow/enforce 机制。
- 不实现一个 token 同时代表多个租户，也不支持登录后无审计地修改 tenant header。
- 不把机器身份全部迁为 Casdoor client credentials；机器 `X-Api-Key` 仍是阶段性例外，但必须盘点、标记 owner 和到期时间。
- 本次规划不执行 Casdoor/数据库/SpiceDB 写入，不修改业务代码。
- 不物理删除 auth DB、历史凭证或 SpiceDB tuple；不可逆清理需在观察期后另行审批。

## 5. 边界条件与歧义

| 编号 | 问题 | 规划结论 / 待验证 |
|---|---|---|
| Q1 | Casdoor 当前镜像版本 | Compose 使用 `casbin/casdoor:latest`（`deploy/docker-compose.yml:55-61`），本地 `:8000` 未运行；实施前先固定版本并做 API contract probe。 |
| Q2 | `organizationName` 是否能让单 app 跨 org 登录 | 仓库无证据；方案 C 仅作候选，标记待固定版本实测，不能作为默认方案。 |
| Q3 | user/role/permission 原位 update API 及错误码 | `add-*`/`delete-permission` 由脚本证明，update 字段与 409 语义待验证；reconcile 首阶段必须录制契约 fixture。 |
| Q4 | Casdoor 自定义 property 是否稳定进入 access token | 仓库只证明 `owner/sub/name/permissions`；方案 B 的自定义 tenant claim 待验证。 |
| Q5 | Casdoor groups 是否能唯一表达 tenant | token 有 groups 的代码证据来自 `SecurityConfig.jwtAuthenticationConverter()`（`auth-platform-admin/.../SecurityConfig.java:73-88`），但多组用户、嵌套组和 claim 大小需实测。 |
| Q6 | 生产 redirect origins/域名 | 当前只证明 localhost；生产 URI 清单由环境 manifest 注入，实施前由运维确认。 |
| Q7 | 生产 auth DB 是否只有 demo seed | 不能假设。必须先只读导出实际表和行数，再决定“全迁/冲突隔离”；零行也要生成审计报告。 |
| Q8 | 历史 SpiceDB 主体是否使用 legacy USER_ID | 当前 fixture 同时出现名字和 Casdoor UUID 的可能；必须扫描并按 crosswalk 分类，未知主体不自动删除。 |

## 6. 失败场景（必须显式处理）

1. 用户选择 acme，却用 globex app 回调：前端清会话，edge 以 `(owner,aud)` 不匹配返回 401，并打低基数原因指标。
2. registry 发布中断或 JSON 半写：edge 拒绝新快照，继续 LKG；新租户暂不可登录，既有租户不受影响。
3. 两个 reconcile 同时修改同一 org：第二个拿不到 org lease/lock，退出或排队，绝不交错 delete+add permission。
4. org/app 已创建但 user/permission 失败：journal 记录完成边界；重跑 current→desired 收敛，不自动删除已创建对象。
5. Casdoor 成功、registry publish 失败：tenant 状态保持 `provisioning`，不向登录目录暴露；补发 registry 后才 `active`。
6. registry 先发布、Casdoor 数据未完成：禁止该发布顺序；edge readiness probe 不能替代 token postcondition。
7. 角色撤销后旧 token 仍有效：以最大 access-token TTL 为撤权收敛上限；高风险撤权同时禁用用户/撤 session（具体 Casdoor 能力待验证）。
8. legacy 与 Casdoor 双写部分失败：以 durable journal/outbox 重试；在对账一致前该主体不进入 oidc canary。
9. 同名用户跨租户：所有 admin、迁移和审计键使用 `(tenant,username)`；不得沿用 `/users/{username}` 的全局假设。
10. F1 配置误开：启动/发布前合同检查失败，`RAG_AUTHZ_MODE∈{shadow,enforce}` 与 `CONVERSATION_SEMANTIC_CACHE_ENABLED=true` 不允许同环境发布。

## 7. 验收标准

### 7.1 功能

- acme 和 globex 用户分别选择租户并登录；token `owner`、内部 JWT tenant、业务响应 tenant、SpiceDB 对象前缀完全一致。
- 同名 `sam@acme` 与 `sam@globex` 可并存、管理、登录，`sub` 不同且数据不串。
- 新增 beta 租户后不重启 edge、不重建 SPA即可登录；注册表 revision 在前端与 edge 可观测一致。
- callback 刷新、silent renew、refresh-token 轮换、多标签登出、切租户均不会复用错误 UserManager。
- admin 最终写 Casdoor-backed 控制面；legacy `/auth/admin/**` 只读后不可写，最后不可达。

### 7.2 安全

- 错 issuer/签名/时间/aud/owner、未知 tenant、禁用 tenant、owner-aud 错配均为 401；缺 scope 为 Phase B-0 的 403。
- acme token 无法通过 query/header/path 访问 globex 数据或使用 globex app 身份。
- registry 不含 secret；日志、journal、测试报告不含 token/password/client secret/API key。
- 任何预期 401/403 得到 2xx、任何跨租户读写成功均为零容忍失败。

### 7.3 数据、幂等和规模

- manifest schema 校验、dry-run、apply、第二次 dry-run 零差异；默认不 prune。
- backfill 对每个 legacy 用户给出 migrated/conflict/skipped，角色/scope 有集合对账，subject crosswalk 完整或明确阻断。
- 注入任一步骤 429/500/timeout 后可安全重跑；同 org 并发单写；不同 org 可受控并行。
- 1000 tenant/10000 client registry 加载、查询与热更新压测通过；请求验 token 不发远程 registry/Casdoor Admin 请求。

### 7.4 灰度与回滚

- 顺序严格为：数据预置 → registry shadow → edge Casdoor dual → 前端 dual canary → Phase B-0 shadow/enforce → oidc → edge only → legacy 冻结。
- 每一步有反向开关和明确恢复组合；回滚到 apikey 时同时保证 auth-service、session filter、路由和 secret 仍可恢复，不能只回滚 SPA。

## 8. 发布硬门

- Casdoor 版本未固定/API contract 未验证；
- 任一启用租户缺三类 redirect URI；
- registry 不能验证 `(owner,aud)` 一一绑定或存在重复 clientId；
- backfill 有未处置身份冲突/未知 SpiceDB subject；
- F1 配置不满足；
- F3 回归失败；
- 两租户登录与跨租户负向 E2E 未全绿；
- 既有 Phase B-0 的 5×11 scope 矩阵未全绿。
