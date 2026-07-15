# 代码库分析（codebase-explorer）

## 1. 证据范围与仓库状态

阅读范围：

- `auth-platform/docs/plans/langchain4j-authz-integration-0714-1701/IMPLEMENTATION_PROGRESS.md`
- `MILESTONE-B-STEP3-CASDOOR-SSO-DESIGN.md`
- `PHASE0-RESEARCH.md`
- `auth-platform/deploy/casdoor-seed.sh`
- `auth-platform/auth-platform-admin/.../casdoor/*`
- `langchain4j-platform` 的 edge、platform-security、auth-service、前端、11 个业务服务、Compose/Helm 与测试。

`langchain4j-platform` 当前 main 的相关提交为：`ca13976`（knowledge ReBAC）、`5d8a3bc`（edge Casdoor）、`25a1df0`（前端 OIDC）、`4b3e729`（前端硬化）。两个仓库均存在用户未提交改动，本规划不依赖、不修改这些改动。

## 2. 已实现调用链

```text
Casdoor access token
  Authorization: Bearer ...
        |
        v
CasdoorTokenExchangeFilter.filter()                    order -120, EDGE_CASDOOR_ENABLED
  CasdoorDecoderConfig.casdoorJwtDecoder()             RS256/JWKS + exp/iss/aud
  owner -> tenantId
  sub   -> userId
  permissions[].name ∩ scopeAllowlist -> scopes
        |
        v
InternalToken.mint(Tenant) -> X-Internal-Token
        |
        v
InternalTokenAuthFilter.resolve() -> TenantContext
        |
        +--> controller/service 读取 tenantId/userId
        +--> 少数端点 TenantContext.hasScope(...) -> 403
```

灰度期同链上还有：

```text
session Bearer -> SessionBearerAuthFilter.filter()       order -110
X-Api-Key      -> ApiKeyToInternalTokenFilter.filter()   order -100
```

Casdoor 成功后会同时剥离 `Authorization` 和 dual 模式下的 `X-Api-Key`。`CasdoorTokenExchangeFilter` 在最早位置剥离客户端伪造的 `X-Internal-Token`。内部 JWT 的形状没有改变：`sub=tenantId`、`uid=userId`、`scopes=[...]`。

## 3. 核心类、方法和配置

| 路径 | 类 / 方法 | 现状与复用方式 |
|---|---|---|
| `../langchain4j-platform/edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java` | `filter`、`exchangeAndForward`、`extractScopes`、`extractCandidates` | 直接复用；Phase B 只开配置。建议补低基数认证来源/结果指标，不改判权语义 |
| `.../CasdoorDecoderConfig.java` | `casdoorJwtDecoder`、`audienceValidator` | enabled 时 issuer/JWKS/audience 缺失即启动失败；复用 |
| `.../CasdoorSecurityProperties.java` | `edge.casdoor.*` | 运行时 edge 契约；默认 disabled |
| `.../SessionBearerAuthFilter.java` | `filter` | legacy 人类 session；Phase C 先加独立开关、再停用，观察期后才考虑删除 |
| `.../ApiKeyToInternalTokenFilter.java` | `filter` | 本轮保留纯机器身份，不能随 session 一起删除 |
| `.../EdgeOpenPaths.java` | `isOpen` | 目前放行 5 个 `/auth/*` 和第三方回调；Phase C 收敛 auth 路径，回调保留 |
| `../langchain4j-platform/platform-security/.../InternalToken.java` | `mint`、`verify` | 内部协议不变 |
| `.../InternalTokenAuthFilter.java` | `doFilterInternal`、`resolve` | 只绑定上下文，不拒绝匿名；`allowApiKeyFallback` 为下游直连 dev 兜底 |
| `.../TenantContext.java` | `current`、`Tenant.hasScope` | 未绑定时返回 `ANONYMOUS`；scope 门禁必须由调用者显式执行 |
| `../langchain4j-platform/capability-showcase-frontend/src/auth/oidc.ts` | `getUserManager`、`userFromAccessToken` | OIDC Code+PKCE、sessionStorage、permissions∩allowlist 已实现 |
| `.../src/config.ts` | `AUTH_MODE`、`SCOPE_ALLOWLIST` | build-time `apikey/dual/oidc`，11 项固定表 |
| `.../src/api/client.ts` | `assembleRequest` | dual 同带两种凭证，Casdoor 优先 |
| `.../src/stores/auth.ts` | `bootstrap`、`refresh`、`logout` | oidc/dual 由 OIDC 驱动；apikey 走 legacy |
| `../langchain4j-platform/auth-service/.../SeedRoles.java` | `defaults` | 5 角色→11 scope 的唯一代码基线 |
| `.../SeedUsers.java` | `defaults` | 3 个 dev 账号重建基线 |
| `.../EffectivePermissionResolver.java` | effective permission 合成 | 仅用于理解 legacy；本轮不把它接回在线链路 |
| `auth-platform/deploy/casdoor-seed.sh` | `post`、`perm` | 已 seed role/permission；需演进为可审计 reconcile，而非另写在线授权服务 |

## 4. Casdoor 现有建模和迁移能力

### 已由仓库证明

- 角色 API：脚本调用 `add-role`；角色存在时不覆盖，避免清掉 `role.users`。
- permission API：脚本调用 `delete-permission`、`add-permission`；permission name 等于业务 scope，`roles` 指向拥有该 scope 的角色。
- 用户/组只读：`CasdoorClient.groupMembers()` 调 `GET /api/get-users?owner=...`，`groupNames()` 调 `GET /api/get-groups?owner=...`。
- token API：脚本用 `/api/login/oauth/access_token` password grant 获取管理 token。
- 重要时序：必须先分配角色，再重建 permission，否则新 token 不带业务 scope。

### 当前缺口

- 脚本没有 organization、model、application、user 和 role assignment 的声明式输入；
- 没有 dry-run、current/desired diff、postcondition、审计日志、并发锁或分租户恢复点；
- `post add-role ... || true` 会把“已存在”和鉴权/网络/5xx 混在一起；
- permission 每次 delete+add，有短暂空窗且不具备事务性；
- 本地 `:8000` 在本次分析环境不可达，`add-user/update-user/update-permission` 等写端点及 JSON 字段均为**待验证**，计划不得假定。

`auth-platform-admin` 的 `CasdoorClient`、`GroupSyncService.sync()` 和 `ReconcileJob` 是 Casdoor groups→SpiceDB 的只读差量同步，适合复用其“desired/current diff + fully consistent”思想，但不应直接承担本轮 org/user/role 写入；其 subject 默认取 Casdoor `id`，与 OIDC `sub` 方向一致。

## 5. auth-service 数据模型（仅用于 legacy 盘点）

实际 JDBC store 在 Java 中以 `CREATE TABLE IF NOT EXISTS` 建表，没有 Flyway/Liquibase：

| 表 | 用途 |
|---|---|
| `USERS` | username/password hash/tenant/userId/direct scopes/enabled/legacy roles/version |
| `USER_ROLE` | 用户个人角色，权威关系 |
| `ROLES` | 角色描述与影子 scopes/version |
| `ROLE_SCOPE` | role→scope 权威关系 |
| `TENANT_POLICY`、`TENANT_ROLE` | 租户基础角色与版本 |
| `AUTH_GROUP`、`GROUP_ROLE`、`USER_GROUP` | 组、组角色、成员关系 |

本轮决策明确不读取或迁移这些表。Phase C 先只读封存和备份，物理删除另行审批。

## 6. 服务端 scope 门禁实况

### 已确认存在

| scope | 类 / 方法 | 典型端点 |
|---|---|---|
| ingest/public-ingest | `DocumentController.requireIngest/requireWrite` | `POST /rag/documents`、共享写 |
| ingest | `MultimodalImageSearchController.requireIngest` | `POST /rag/image` |
| approve | `WorkflowController.requireApprove` | `GET /workflow/tasks`、claim/unclaim/complete、purge |
| role-admin | `AdminController.requireRoleAdmin` | `/auth/admin/**` |

### 当前 main 未找到等价门禁

`ConversationController.chat`、`AgentController.run`、`ChannelController`、`EvalController`、`VisionController`、`VoiceController`、`AnalyticsController` 均未调用 `hasScope`；相关前端 capability 的 `requiredScopes` 多数也是空数组。这与任务给定的“11 项全服务 scope 门已就绪”冲突，详见 `01-requirements.md` 的发布阻塞说明。

## 7. 部署与构建现状

### Compose

`../langchain4j-platform/deploy/docker-compose.yml`：

- 前端 build args 已含 `SHOWCASE_AUTH_MODE` 和 Casdoor issuer/client/path；
- edge 已含 `EDGE_CASDOOR_ENABLED`、issuer、JWKS、audiences；
- edge 对 auth-service 有 `AUTH_URI` 和 `depends_on`；
- auth-service 仍常驻；
- edge 与多个下游的 application 默认 `allow-api-key-fallback:true`，下游直接暴露时会接受本地 key；即便关掉 fallback，当前 filter 也不会统一拒绝匿名。

### Helm

`deploy/helm/platform/values.yaml`：

- `AUTH_URI` 仍进入共享 ConfigMap；
- edge 挂 `auth-session-jwt` 和 `edge-gateway-apikeys`；
- auth-service 1 副本并挂 session secret；
- 尚未看到 Casdoor edge 配置和 OIDC 前端构建/发布的完整 production values；
- 模板由 `templates/configmap.yaml`、`workloads.yaml` 泛化渲染，主要修改点在 values/Secret 约定。

### 构建硬约束

edge 必须用：

```bash
mvn -pl edge-gateway -DskipTests \
  -Dmaven.repo.local=/Users/liruijun/personal/repository package
docker build .../edge-gateway
```

不能默认使用 `~/.m2`。

## 8. 已有测试与可复用资产

- `CasdoorTokenExchangeFilterTest`：Casdoor claim→内部 JWT、allowlist、dual 剥 key、伪造内部头、缺 claim 401、验签失败 legacy 透传；
- `CasdoorJwksIntegrationTest`：本地真实 Casdoor/JWKS（环境满足时）；
- `SessionBearerAuthFilterTest`、`ApiKeyToInternalTokenFilterTest`、`EdgeOpenPathsTest`；
- platform-security `InternalTokenTest`/`InternalTokenRs256Test`；
- 前端 OIDC、auth store、dual header、续期、SSE、多标签登出、错误文案等测试；
- `deploy/smoke-rbac.sh` 仅覆盖 legacy RBAC，不是 Casdoor rollout 验收脚本；
- `auth-platform/deploy/sso-smoke.sh` 和 `server-smoke.sh` 可供 Casdoor/SpiceDB基础设施冒烟参考。

## 9. 预计受影响文件（实施期，不是本次修改）

### 必需

- `auth-platform/deploy/casdoor-seed.sh`：改造成严格错误分类、dry-run/reconcile/postcondition；
- `auth-platform/deploy/casdoor-rollout-manifest.json`（新）：无密钥的 org/user/role 期望态；
- `auth-platform/deploy/casdoor-rollout-smoke.sh`（新）：配置契约、token claim、角色矩阵驱动；
- `langchain4j-platform/edge-gateway/src/main/resources/application.yml`：legacy session 独立开关、路由/监控配置；
- `.../SessionBearerAuthFilter.java`：受配置门控并补 legacy 使用指标；
- `.../CasdoorTokenExchangeFilter.java`：补成功/失败/缺 claim 指标，不改变换发协议；
- `.../ApiKeyToInternalTokenFilter.java`：补机器 key 使用指标；保留实现；
- `.../EdgeOpenPaths.java`：Phase C 移除 legacy auth open paths；
- 对应 edge 测试文件；
- `langchain4j-platform/deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、相关 Secret 示例：Casdoor 配置、下游直连收敛、auth-service 缩容；
- `capability-showcase-frontend` 的部署参数/文档：dual/oidc 构建和隐藏旧 RBAC 管理入口；业务 OIDC 代码原则上不改；
- `README.md`、前端 README、平台运维/OIDC/RBAC 文档：真相源和回滚 runbook。

### 条件性阻塞修复

若 Phase B-0 在实际目标分支仍不能得到 11 项 403/200，必须先补统一服务端 scope enforcement。具体类/方法只能在选定实现后确定；当前已知受测控制器包括 `ConversationController.chat`、`AgentController.run`、`ChannelController.capabilities/send`、`EvalController.capabilities/run`、`VisionController.caption`、`VoiceController.transcribe`、`AnalyticsSchemaController.tables`。不得只改前端 catalog。

### 明确不改

- `InternalToken` 的 claim/wire shape；
- `TenantContext.Tenant` 数据结构；
- 下游跨服务 `OutboundTenantForwarder`；
- auth-service JDBC 表结构；
- knowledge 历史 SpiceDB subject。
