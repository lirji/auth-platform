# 代码库分析（codebase-explorer）

## 1. 证据范围与状态

- IAM 仓库：`/Users/liruijun/personal/LLM/auth-platform`，HEAD `0161fb1`；Casdoor/ReBAC 相关文件存在未提交改动，本计划只读并保留这些改动。
- AI 平台仓库：`/Users/liruijun/personal/LLM/langchain4j-platform`，HEAD `7f70ef7`。
- 已完整读取既有 `casdoor-authz-rollout-0715-1101` 的 `FINAL_PLAN.md`、代码分析、A–D、comparison、test-plan；本文件只补多租户身份差异。
- 本地 `http://localhost:8000` 无服务，故仓库脚本能证明“代码打算发送什么”，不能证明当前 `latest` Casdoor 接受全部字段；所有未由运行测试证明的 Casdoor update/organization-routing 行为均标待验证。

## 2. 当前浏览器登录调用链

```text
LoginView.casdoorLogin()
  -> authStore.startOidcLogin(returnTo)
  -> oidc.startOidcLogin()
  -> getUserManager() [全局单例]
       authority = CASDOOR_ISSUER [单值]
       client_id = CASDOOR_CLIENT_ID [单值，默认 auth-console]
       redirect/silent/logout = 同一组路径
  -> UserManager.signinRedirect()
  -> Casdoor application 所属 org 的登录
  -> /callback -> CallbackView -> authStore.handleOidcCallback()
  -> userFromAccessToken(): owner/name/permissions -> AuthUser
```

代码证据：

- `config.ts:49-62` 把 `AUTH_MODE`、issuer、client_id 固化为构建期单值，默认 client ID 为 `cad5642b16071c3513d4`。
- `oidc.ts:30-49` 只有一个 `manager`，`getUserManager()` 用固定 authority/client_id 构造。
- `oidc.ts:83-99` 把 `owner→tenant`，并且 `startOidcLogin` 只带 return state，没有 tenant/organization 参数。
- `LoginView.vue:31-43,157-167` 在 oidc/dual 下只有一个“用 Casdoor 登录”按钮，没有租户选择。
- `stores/auth.ts:116-137` 所有事件、redirect callback 都访问同一 manager；`:194-246` 的 refresh/bootstrap 也不知道 active tenant。
- `main.ts:32-37` 以固定 `CASDOOR_SILENT_PATH` 识别 silent callback；`router/index.ts:33-41` 只有一个 callback route。
- `CallbackView.vue:19-32` 回调失败重试时再次无租户地调用 `startOidcLogin('/')`。

结论：前端只能登录固定 client_id 所属 application/organization，不具备“先解析 tenant，再选 client_id，再构造 UserManager”的机制。当前 `sessionStorage` 设计可保留，但必须增加 pending/active tenant 上下文并按 tenant/client_id 隔离 manager 和事件。

## 3. 当前 edge 认证调用链

```text
Authorization: Bearer <Casdoor token>
  -> CasdoorTokenExchangeFilter.filter()       order -120
     -> ReactiveJwtDecoder.decode()
        -> timestamp + issuer + static audiences
     -> exchangeAndForward()
        -> tenant = tenantClaim(default owner)
        -> uid = subjectClaim(default sub)
        -> permissions[].name ∩ allowlist
        -> InternalToken.mint(Tenant)
        -> strip Authorization + X-Api-Key
  -> SessionBearerAuthFilter                   order -110 (DUAL fallback)
  -> ApiKeyToInternalTokenFilter               order -100 (DUAL fallback)
  -> [既有 Phase B-0 EdgeScopeEnforcementFilter]
  -> downstream
```

代码证据与缺口：

| 文件/方法 | 现状 | 多租户缺口 |
|---|---|---|
| `CasdoorSecurityProperties.java:19-58` | `enabled`、`DUAL/ONLY`、issuer、静态 `List<String> audiences`、claim 名和 scope allowlist | 没有 tenant registry、refresh/LKG/revision；只能保存 clientId 全集 |
| `CasdoorDecoderConfig.casdoorJwtDecoder():30-54` | enabled 时要求 issuer/JWKS/audiences 非空；ONLY 强制 `tenant-claim=owner` | `audienceValidator():62-73` 只做 membership，不验证 owner↔aud 绑定；列表只在启动时注入 |
| `CasdoorTokenExchangeFilter.filter():55-77` | open path 绕过；DUAL 验签失败透 legacy，ONLY 返回 401 | 无 unknown/disabled tenant、registry stale 和 owner-aud mismatch 原因分类 |
| `exchangeAndForward():106-132` | 缺 tenant/sub 401；mint 内部 JWT；剥外部凭证 | tenant 仍完全信任 claim 名配置；需在 decoder 成功前后绑定注册表验证 |
| `application.yml:111-129` | 默认单 audience `cad...`、owner/sub/permissions、11 scope | 静态 env 更新需重启；Compose 虽默认再加 built-in（`docker-compose.yml:494-504`），仍无租户 app 自动发现 |

`EDGE_CASDOOR_MODE` 已有且可复用：`DUAL` 允许 legacy 回退，`ONLY` 对缺 token/坏 token 401（`CasdoorTokenExchangeFilter.java:79-92`）。本计划不能用动态 audience 破坏这一语义。

## 4. 当前租户开通与 Casdoor 对象模型

`deploy/casdoor-tenant-provision.sh` 的实际顺序是：

1. `add-organization`，payload 见 `:60-62`；
2. `add-application`，organization=`TENANT`、app=`rag-<tenant>`、clientId=`rag<tenant>client01`，见 `:64-69`；
3. `add-user` + `set-password`，见 `:71-79`；
4. password grant 签 token并输出 `iss/aud/sub/owner/name`，见 `:81-90`；
5. 可选把 Casdoor UUID 写入 SpiceDB `<tenant>_<group>`，见 `:92-98`；
6. 明确要求把新 clientId 加到 `CASDOOR_AUDIENCES` 后重启 edge，见 `:101-110`。

现存风险：

- 脚本 `set -uo pipefail` 而不是 `-euo`（`:32`），多数 Casdoor `capi` 响应只打印 `status/msg`，没有一致 fail-fast；
- `add-*` 没有 current/desired diff，不能区分“已存在”和真实失败；
- client secret 默认按租户可预测派生并打印（`:46-47,101-110`），只适合本地 demo，不可进入生产；
- app 只登记 `http://localhost:8093/callback`，遗漏 post logout 和 silent URI；
- app/org/user 已部分成功时没有 journal/补偿；
- `WIRE_SPICEDB=1` 在身份对象未完全验收时直接写 ReBAC；
- 新增 user 与角色/permission 不在同一条脚本中。

`deploy/casdoor-seed.sh` 补角色/permission：

- `post()` 使用 `--fail-with-body`，但 `add-role ... || true` 仍吞掉 401/5xx（`:32-39`）；
- `perm()` 每次 delete+add（`:43-48`），有无权限空窗；
- 明确“先 role.users，再重建 permission”才能正确展开 token（`:8-11`）；
- 5 角色/11 scope 与 `SeedRoles.defaults()` 一致（脚本 `:50-60`；平台 `SeedRoles.java:17-26`）。

两个脚本必须合并到同一 manifest/reconcile 语义，但可以保留独立入口作为兼容 wrapper。

## 5. legacy 身份/RBAC 数据模型

### 5.1 数据表和权威关系

| 数据 | 真实位置 | 迁移含义 |
|---|---|---|
| 用户/profile/direct scope | `JdbcUserAccountStore.init()` 创建 `USERS`，`USERNAME` PK、`TENANT`、`USER_ID`、`SCOPES`、`ENABLED`，见 `:41-55` | 每行映射到 tenant org 的 Casdoor user；PASSWORD_HASH 不可直接当 Casdoor 密码迁移，需临时密码/重置链接，具体能力待验证 |
| 用户角色 | `USER_ROLE` 是权威，`USERS.ROLES` 只是影子，见 `JdbcUserAccountStore.java:23-26,56-71` | 按 tenant 复制角色并赋给对应用户 |
| 角色 scope | `ROLE_SCOPE` 是权威，`ROLES.SCOPES` 是影子，见 `JdbcRoleStore.java:19-25,41-66` | 反转成 permission(scope)→roles；每 org 一份 |
| 租户基础角色 | `TENANT_POLICY/TENANT_ROLE`，见 `JdbcTenantPolicyStore.java:15-55` | Casdoor 无已证明的 tenant-role 继承；backfill 时展开到该 tenant 的每个用户角色集合 |
| 组/组角色 | `AUTH_GROUP/GROUP_ROLE`，见 `JdbcGroupStore.java:19-59` | 组迁 Casdoor，coarse scope 继承在 backfill 时展开到成员；未来是否由 Casdoor 原生 group→role 展开待验证 |
| 用户组 | `USER_GROUP`，见 `JdbcUserGroupStore.java:16-46` | 迁 Casdoor group membership，并由 GroupSync 写 SpiceDB |

### 5.2 当前 API/前端的单租户假设

- `/auth/admin/users/{username}` 等 controller 路径只用 username（`AdminController.java:74-127`）。
- 前端 `fetchUser/patchUser/replaceUserRoles/deleteUser` 也只编码 username（`api/admin.ts:71-91`）。
- `UserEditor` 路由 `/admin/users/:username`（`router/index.ts:65-71`），详情页 props 也只有 username。
- `UserEditor.save()` 是 profile→roles→groups 三次独立写，版本逐次递增（`:192-252`）；即使 legacy DB 内部各次事务正确，跨 Casdoor 多对象也不能伪装成原子提交。

因此最终 admin contract 必须显式 tenant，写操作必须返回 operation/plan 状态；不能只把 base URL 从 auth-service 换成 Casdoor 就声称完成。

## 6. auth-platform-admin 和 GroupSync

### 6.1 已有能力

- `CasdoorClient.groupMembers()` 读取单个 `props.organization` 的 users，并从 `id`（默认等于 OIDC sub）或 name 取主体，见 `CasdoorClient.java:27-47`。
- `groupNames()` 读取单 org groups，编码为 `<org>_<group>`，见 `:49-59`。
- `GroupSyncService.sync()` `synchronized`，current/desired diff 后一次 `writeRelationships(updates)`，DELETE 超阈值整轮中止，见 `GroupSyncService.java:53-90`。
- `ReconcileJob.reconcile()` 固定周期调用全量 sync（`ReconcileJob.java:8-24`）。
- `CasdoorSyncController` 有手工 `/admin/casdoor/sync` 与 webhook，后者只按共享 secret 校验（`:33-60`）。

### 6.2 多租户缺口

- `CasdoorProperties.organization` 是单字符串，默认 built-in（`:9-19`），无法遍历所有 tenant org。
- `CasdoorClient` 用 Basic clientId/clientSecret（`:19-24`），权限范围、分页和数百 org 限流未验证。
- `sync()` 的 Java `synchronized` 只保护单实例；多副本仍会并发写。
- 一 org 拉取失败会使整次方法失败；没有 per-org checkpoint/LKG/指标。
- `SecurityConfig.jwtDecoder()` 也只支持一个 optional clientId audience（`SecurityConfig.java:56-70`），未来 admin BFF 需要复用动态 owner-aud registry 或只接受 edge 换发的内部 JWT，二者需二选一。最终方案选择后者，减少第二套 Casdoor validator。

### 6.3 正确职责边界

`GroupSyncService` 继续只管 membership tuple；新增的 tenant lifecycle reconcile 负责 org/app/user/role/permission 和 registry publish。两者共享 tenant manifest、org lock 和 journal ID，但不互相调用远端写，避免部分失败难以定位。

## 7. ReBAC 与身份 ID 耦合

- knowledge 资源 ID 由 `KnowledgeResourceIds.join()` 产生 `<tenant>_<id>`（`:26-28`）；选择 owner=tenant 可零迁移保留资源 ID。
- `RealKnowledgeAuthz.onDocumentCreated()` 把 `ownerUserId` 写为 `user:<id>`（`RealKnowledgeAuthz.java:85-94`），读时用当前内部 JWT userId（`:116-150`）。从 legacy `USER_ID` 切换到 Casdoor `sub` 后，历史 subject 必须 crosswalk，否则 owner/显式 viewer 权限失效。
- `RelationshipUpdate` 已支持同一 batch 的 TOUCH/DELETE（`auth-platform-protocol/.../RelationshipUpdate.java:3-17`）；`SpiceDbAuthzEngine.writeRelationships()` 将整个 list 发给 `/v1/relationships/write`（`:134-147`），可用于“先 TOUCH 新 subject + 同批 DELETE 旧 subject”。远端批次原子性仍应在固定 SpiceDB 版本集成测试验证。
- `GroupSyncService` 新写使用 Casdoor UUID；已有旧 name subject 不会被自动识别为同一人，crosswalk 必须单独执行。

## 8. F1/F3 实况

### F1

`SemanticCache.getOrCompute()` 只以 tenant 查最近项，命中直接返回 reply（`SemanticCache.java:52-69`）；entry 不含 user/sub/授权 revision（`SemanticCacheEntry.java:1-8`）。`ConversationController.chat()` 把整个 RAG+LLM 放在 cache supplier 内（`:73-84`），所以命中会绕过按 user 的文档授权。配置默认 false（`conversation-service/application.yml:120-130`），但没有跨服务配置校验。实施必须增加发布合同，至少在 shadow/enforce 环境强制 false；本次不扩展 per-user cache key。

### F3

- `RemoteAuthzEngine.parseCheckBulk()` 对 results 数量、资源集合、重复和缺字段 fail-closed，见 `:72-105`；`requireAllowed()` 只接受 JSON boolean，见 `:108-122`。
- `SpiceDbAuthzEngine.checkBulk()` 对 pairs 数量、item error 和 permissionship fail-closed，见 `:59-91`。

这些代码已满足当前约束，列为回归对象而非修改对象。

## 9. 配置与部署现状

- Compose 前端在 build args 中烘焙单一 `VITE_CASDOOR_CLIENT_ID`（`docker-compose.yml:440-464`）；动态租户必须改为运行时从 edge 读取公共 registry，保留 issuer/路径作默认/回滚配置。
- Compose edge 通过 env 传静态 `CASDOOR_AUDIENCES`（`:474-504`），并依赖 auth-service（`:510-521`）。
- Edge CORS 已允许 Authorization/If-Match，暴露分页/ETag（`edge application.yml:16-29`），新 registry GET 不需要新增敏感 header。
- Helm values 只有 `EDGE_CASDOOR_MODE`，未见 issuer/JWKS/audiences/tenant registry 的完整生产配置（`values.yaml:143-153`）；auth session/API key secret 仍存在（`:250-270`）。
- auth-platform Compose 使用 `casbin/casdoor:latest`（`deploy/docker-compose.yml:55-61`），上线前必须 pin image digest/version。

## 10. 可复用测试资产

- Edge：`CasdoorTokenExchangeFilterTest` 已覆盖换发、allowlist、DUAL key 剥离、缺 claim、ONLY、伪造内部头；测试方法见文件 `:63-280`。
- Edge live：`CasdoorJwksIntegrationTest.decoder_verifiesRealCasdoorToken()` 与 `filter_endToEnd_realToken_mintsInternalJwtWithScopes()`。
- 前端：`auth/oidc.test.ts`、`stores/auth.oidc.test.ts`、`LoginView.test.ts`、`AuthControl.test.ts`；现有用例覆盖单 manager 的 callback/renew/logout，需参数化两个 tenant。
- Admin：`api/admin.test.ts`、admin stores 和 UserEditor/Tenants/Groups views 测试，可复用 UI 行为但必须更换 `(tenant,username)` key 与 operation 状态。
- IAM：`CasdoorGroupIdsTest`、`GroupSyncServiceTest`、`RemoteAuthzEngineTest`。
- Shell/E2E：`deploy/sso-smoke.sh`、`rag-authz-fixture.sh`、平台 `smoke-rag-tenant-authz.sh`；不能把 skip 当通过。

## 11. 实施期受影响文件清单

### 11.1 langchain4j-platform 前端（必改）

- `capability-showcase-frontend/src/config.ts`：新增 registry URL/缓存 TTL 配置；`CASDOOR_CLIENT_ID` 仅作 legacy fallback，不能再是主路由。
- `src/auth/oidc.ts`：把 `getUserManager()` 单例改为按 tenant/clientId 的 manager map；`startOidcLogin` 接收 tenant；callback/silent/logout/bootstrap 根据 pending/active tenant 恢复 manager；`userFromAccessToken` 断言 owner。
- `src/stores/auth.ts`：增加 selected/pending/active tenant 状态及切租户清理；所有 callback/renew 传 tenant context。
- `src/modules/auth/LoginView.vue`：租户输入/选择、registry lookup、错误和 loading；legacy apikey 表单保持原分支。
- `src/modules/auth/CallbackView.vue`、`src/main.ts`：回调和 silent callback 恢复正确 tenant manager，失败重试保留 tenant。
- `src/components/layout/AuthControl.vue`：显示 active tenant、提供显式切换；oidc 下不恢复 API key UI。
- `src/api/admin.ts`、`src/stores/adminUsers.ts`、`src/stores/adminTenants.ts`、`src/modules/admin/UserEditor.vue`、router 与 types：改接 Casdoor-backed admin adapter，用户 key 包含 tenant，写操作展示 plan/status。
- 对应现有 `*.test.ts` 全部扩展多租户场景；建议新增 `src/auth/tenantRegistry.ts` 及测试（新增文件，名字为计划定义）。

### 11.2 langchain4j-platform edge（必改）

- `CasdoorSecurityProperties.java`：增加 registry source/refresh/stale 配置；保留 DUAL/ONLY、issuer、claim 和 scope 配置。
- `CasdoorDecoderConfig.java`：静态 `audienceValidator()` 替换为 registry-backed `(owner,aud)` validator；ONLY 的 owner 断言保留。
- `CasdoorTokenExchangeFilter.java`：使用已验证 registry binding，补 reason metrics；内部 JWT 协议不改。
- `application.yml`：新增 tenant registry 配置、公共 lookup route（若由 edge controller 提供）和指标；静态 audiences 仅作为紧急 fallback，不能与动态源静默合并。
- 计划新增 `CasdoorTenantRegistry`、`CasdoorTenantRegistryRefresher`、`CasdoorTenantDiscoveryController` 及对应测试（均为未来新增，不冒充现存类）。
- `deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、模板/Secret：registry snapshot/URI/service token、admin adapter URI、Casdoor production config；新租户不再修改 `CASDOOR_AUDIENCES`。
- 既有 Phase B-0 文件只做集成配置/测试，不复制其内部实现。

### 11.3 auth-platform（必改）

- `deploy/casdoor-tenant-provision.sh`、`deploy/casdoor-seed.sh`：变为 manifest reconcile 的兼容入口；严格错误分类、dry-run/apply/journal/lock/postcondition。
- 计划新增无密钥 tenant identity manifest、JSON schema、reconcile 主脚本、legacy export/backfill、SpiceDB subject crosswalk、smoke/contract probe（具体建议名见 FINAL_PLAN）。
- `auth-platform-admin/.../casdoor/CasdoorProperties.java`：单 organization 改为 registry 驱动的 organizations 集合。
- `CasdoorClient.java`：按 org 分页读取，HTTP 状态/响应 schema 严格校验；新增写 API 只能在 contract probe 后实现。
- `GroupSyncService.java`、`ReconcileJob.java`、`CasdoorSyncController.java`：per-org checkpoint、分布式单写/lease、失败隔离、删除阈值按 org；保留 direct membership 语义。
- `SecurityConfig.java`：最终 admin adapter 接受 edge 内部身份或经过同一 registry 校验的 Casdoor token；最终方案选 edge 路由+内部 token，避免复制 validator。
- 新增 Casdoor-backed tenant lifecycle/admin adapter 的 DTO、controller、planner/applier/journal store。其类名/接口均是计划定义，实施时必须先核对 Casdoor contract。

### 11.4 明确不改或只回归

- `platform-security` 的内部 JWT claim/wire 和 `TenantContext.Tenant`；
- `KnowledgeResourceIds` 的 `<tenant>_<id>` 规则；
- `RemoteAuthzEngine`、`SpiceDbAuthzEngine` 的 F3 逻辑；
- 业务 Controller 的 scope 细节（由既有 Phase B-0 负责）；
- 物理删除 legacy 表/源码（本轮不做）。
