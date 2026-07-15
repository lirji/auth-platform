# ③ Casdoor SSO 身份切换 — 详细实现设计（待审）

> 关联 [FINAL_PLAN.md](./FINAL_PLAN.md) §8.2、[PHASE0-RESEARCH.md](./PHASE0-RESEARCH.md)（含真实 Casdoor claim 实测）。
> **状态**：设计稿，**审过再写代码**。③ 是完整 B 的身份地基——让 `TenantContext.userId = Casdoor sub`，② 的组绑定才判权对齐。

## 1. 目标与非目标

**目标**：edge-gateway 接受 Casdoor 签发的 access token，换发**形状不变**的内部 JWT，下游全部服务零改动。切换后 `Casdoor sub = 内部 JWT uid = TenantContext.userId = SpiceDB user id` 四者一致。

**非目标**：
- 不改内部 JWT 形状（仍 `sub=tenantId, uid=userId, scopes`）、不改下游 `InternalTokenAuthFilter`/`TenantContext`。
- 不在本步做 ②（组同步）——③ 完成后 ② 才有对齐的 subject。
- 不一次废除 legacy（api-key/session）——灰度期三模并存。

## 2. 现状调用链（edge，已核实）

```
Authorization: Bearer <session-jwt>  --SessionBearerAuthFilter(-110)--\
                                                                        > InternalToken.mint(Tenant) -> X-Internal-Token -> 下游
X-Api-Key: <key>                     --ApiKeyToInternalTokenFilter(-100)/
```
- 两 filter 都是 `@Component implements GlobalFilter, Ordered`，Spring Cloud Gateway 自动发现、按 order 串。
- `InternalToken.mint(Tenant(tenantId, userId, scopes))`：`subject=tenantId, uid=userId, scopes=[...]`；HS256/RS256 可选。
- `EdgeOpenPaths.isOpen`：放行 actuator/.well-known/health/`/auth/login|register|refresh|logout|public-config`/两个第三方 webhook。

## 3. 目标架构（新增第三个 filter，最前）

```
Authorization: Bearer <casdoor-access-token>
   --CasdoorTokenExchangeFilter(-120)--> [用 Casdoor JWKS 验签成功?]
        是 -> 提取 owner/sub/groups -> 映射 scopes -> InternalToken.mint -> X-Internal-Token -> 剥离 Authorization -> 下游
        否 -> 透传（交给 -110/-100，灰度期 legacy 仍可用）
```
Casdoor-only 期：移除 -110/-100 两个 legacy filter，Casdoor 验不过即 401。

## 4. 关键设计决策

### D1 三 token 如何区分（灰度期共存）
Casdoor token 与 session token 都走 `Authorization: Bearer`。**区分靠验签来源**：
- CasdoorTokenExchangeFilter 用 **Casdoor JWKS（RS256, issuer=Casdoor）** 尝试 `decode`。
- **成功**（签名+issuer+aud 通过）→ 认定 Casdoor token，换发内部 JWT。
- **失败**（不是 Casdoor 签的 / 无 Bearer）→ **静默透传**，由下游 -110（session）/-100（api-key）处理。
- order **-120**（早于 -110/-100）。

### D2 验签方式：reactive OAuth2 resource server
edge 是 WebFlux（Spring Cloud Gateway）。用 `spring-boot-starter-oauth2-resource-server` 的 **`ReactiveJwtDecoder`**（`NimbusReactiveJwtDecoder.withJwkSetUri(jwks_uri)`）验 Casdoor RS256 + 自动缓存/轮转 JWKS。**不手动解析 JWKS**（多 key + rotation 易错）。
- 注意：这里**只用 ReactiveJwtDecoder 这个组件做验签**，不启用 Spring Security 的整条 `SecurityWebFilterChain`（避免与现有 GlobalFilter 链冲突）——decoder 在自定义 GlobalFilter 里手动调用。

### D3 scope 来源：Casdoor 展开、edge 透传（不在 edge 维护 role→scope 表）
> 修正（2026-07-14）：早期设想在 edge 配 `role/group→scope` 映射表——**否决**，因为角色规模增长会让 edge 配置膨胀、每加角色改 edge，不可扩展。

**原则**：`role→能力` 的展开是**权限中心（Casdoor）的职责**，不是 edge 的。
- Casdoor 里把业务 scope 建模为 permission（或 role 关联），赋给角色/用户；实测 Casdoor `permission` 表（`users/groups/roles × resources × actions`）+ token 自动携带 `roles`/`permissions` claim 支持这点。
- **edge 只做两件事**：① 从 token 的能力 claim（`permissions`/`roles`，Casdoor 已展开）提取业务 scope；② 用一个**固定的 scope allowlist** 过滤——allowlist = 业务 scope 全集（11 个：`chat/ingest/approve/agent/channel/eval/vision/voice/analytics/role-admin/public-ingest`），几乎不变、**与角色数量无关**。
- **可扩展性**：角色从 5 → 5000，都在 Casdoor 维护 role→permission，**edge 代码/配置零改动**。

**edge 侧约定**：`edge.casdoor.scope-claim`（默认取展开后的能力 claim）+ `edge.casdoor.scope-allowlist`（固定 11 个）。token 能力 → scope 的具体提取规则（permission.name=scope，或 role→scope 由 Casdoor 内建 permission 承载）在 ② 迁移配 Casdoor 时定；edge 只依赖"token 带一个可提取出 scope 列表的 claim"。
> ⚠️ 仍需在 ② 迁移前落实：把现有 `SeedRoles` 的 5 角色→scope（一次性）初始化进 Casdoor 的 role/permission，保证切换前后有效 scope 集相等（P0-6）。这是 Casdoor 数据准备，**不是 edge 运行时逻辑**。

### D4 身份 crosswalk（历史数据）
里程碑 A 写入 SpiceDB 的关系 subject 是 auth-service userId（如 `alice`）。切 Casdoor 后 userId=`sub`(UUID)。**生产迁移**需把历史关系的 subject 从 username crosswalk 到 sub（一次性受控迁移，见 PHASE0 P0-4）。本步（③）只切 token 通路；历史数据迁移随 ② 一并做。dev/试点数据可重建。

### D5 tenant 来源与防越权
`tenantId ← token.owner`（Casdoor org，已验签）。**edge 拒绝任何客户端 header/query 覆盖 tenantId**。多租户用户的 active-tenant 规则见 P0-7（本步先支持单 org=单 tenant；多 org 用户延后）。

## 5. edge-gateway 精确改动

### 拟新增
- `edge/CasdoorSecurityProperties.java`（`@ConfigurationProperties("edge.casdoor")`）：`enabled`、`issuer`、`jwkSetUri`、`audiences`、`tenantClaim`(默认 owner)、`subjectClaim`(默认 sub)、`groupsClaim`(默认 groups)、`scopeMapping`(Map<String,List<String>>)。
- `edge/CasdoorDecoderConfig.java`（`@Configuration`, `@ConditionalOnProperty("edge.casdoor.enabled")`）：`@Bean ReactiveJwtDecoder casdoorJwtDecoder()` = `NimbusReactiveJwtDecoder.withJwkSetUri(props.jwkSetUri)`，加 issuer/audience validator。
- `edge/CasdoorTokenExchangeFilter.java`（`@Component`, `@ConditionalOnProperty("edge.casdoor.enabled")`, `GlobalFilter`, order **-120**）：decode 成功→构造 `Tenant(owner, sub, mappedScopes)`→`internalTokens.mint`→注入 `X-Internal-Token`、剥离 Authorization；decode 失败/无 Bearer→透传。open path 放行。
- 测试：`CasdoorTokenExchangeFilterTest`（mock `ReactiveJwtDecoder` 返回构造的 `Jwt`，验 tenant/uid/scopes 映射 + 透传分支）；`CasdoorSsoJwksIntegrationTest`（用实测 token / 本地 Casdoor JWKS，`@EnabledIf` server 可达）。

### 修改
- `edge-gateway/pom.xml`：加 `spring-boot-starter-oauth2-resource-server`（提供 `NimbusReactiveJwtDecoder`）。
- `edge-gateway/src/main/resources/application.yml`：加 `edge.casdoor.*`（默认 `enabled:false`，灰度期开）；保留现有 apiKeys/session（灰度）。
- `edge/EdgeOpenPaths.java`：Casdoor-only 阶段移除 `/auth/login|register|refresh|logout|public-config`（改由 Casdoor 托管登录）——**分阶段**，灰度期先不动。
- `platform-security`：`InternalTokenAuthFilter`/`TenantContext`/`InternalToken` **不改**（只补 sub 一致性测试）。

## 6. 前端（capability-showcase-frontend）改动（后半步，可与 edge 并行准备）
- 引入成熟 OIDC client（authorization-code + PKCE），复用 auth-console 已验证的 Casdoor app（client_id `cad5642b…`）。
- `src/api/auth.ts`/`src/stores/auth.ts`：本地 login/refresh → OIDC bootstrap/callback/silent-renew/logout；`sessionStorage`。
- router 加 `/callback`；LoginView 重定向 Casdoor；移除本地 Register 主路由（Casdoor-only 阶段）。
- 业务请求继续发 `Authorization: Bearer <casdoor-access-token>`（edge 换取内部 JWT）。

## 7. 分阶段实施步骤
1. **edge 验签+换发（后端，可先行）**：pom + Properties + DecoderConfig + Filter + 单测。`enabled:false` 合并，不影响现状。
2. **本地联调**：`enabled:true`，用 PHASE0 实测的 password-grant token `curl` 打 edge → 断言下游拿到正确 `X-Internal-Token`（tenant=built-in, uid=sub, scopes=映射结果）。
3. **scope 映射对齐**：按 P0-6 导出各租户 group→scope 基线，填 `scope-mapping`，验证切换前后有效 scope 集相等。
4. **前端 OIDC**：接 Casdoor 登录 → 拿 token → 走 edge。
5. **灰度**：三模并存（Casdoor+session+api-key）→ Casdoor 为默认 → 移除 legacy filter + open path 收敛为 Casdoor-only。
6. **③ 完成后**才做 ②（组同步）+ 历史 subject crosswalk。

## 8. 测试方案
- **单元**：Filter 用 mock `ReactiveJwtDecoder`（`Mono.just(jwt)` / `Mono.error`）覆盖：Casdoor token→mint 正确 claim & 剥离 Authorization；非 Casdoor→透传；open path 放行；scope 映射（含未知 group 不授权）；tenant 来自 owner、拒绝 header 覆盖。
- **集成**：本地 Casdoor JWKS 真实验签（实测 token），断言 edge→下游内部 JWT。
- **回归**：`enabled:false` 时 174+ 现有行为不变；下游 TenantContext 合同不变。

## 9. 风险与回滚
| 风险 | 控制 |
|---|---|
| Casdoor token 与 session token 混淆 | 靠验签来源区分；issuer+audience validator；decode 失败静默透传 |
| scope 缺失致下游功能门禁失效 | allowlist 映射；切换前后 scope 集对齐校验；未知 group fail-closed（不授权） |
| 合法用户伪造 tenant | tenant 仅取已验签 `owner`；拒绝 header/query 覆盖 |
| JWKS 不可达 | decoder 缓存；不可达时该 token 判失败→透传（灰度期 legacy 兜底）；Casdoor-only 期需 Casdoor 高可用 |
| 历史 subject=username 与新 sub 不一致 | ③ 只切通路；crosswalk 随 ② 迁移；dev 数据重建 |
- **回滚**：`edge.casdoor.enabled=false` 即回到 legacy（配置回滚）；不动下游、不动 SpiceDB。

## 10. 待确认项 → 已定（2026-07-14，附依据）

### 10.1 scope 映射表 ✅ = 复刻 auth-service `SeedRoles` 的「角色→scope」
实测 `SeedRoles.defaults()` 即现成映射；Casdoor SSO 沿用同一张表 → **切换前后有效 scope 集天然相等**（满足 P0-6）：

| 角色 | scopes |
|---|---|
| viewer | chat |
| editor | chat, ingest |
| analyst | chat, analytics |
| approver | chat, approve |
| admin | chat, ingest, approve, agent, channel, eval, vision, voice, analytics, role-admin, public-ingest |

- ⚠️ 这张表是「② 迁移时一次性初始化进 Casdoor role/permission」的内容，**不是 edge 运行时表**（见 D3：edge 不维护 role→scope，只透传 Casdoor 展开结果 + 固定 allowlist）。edge 的固定 allowlist = 上表并集（11 个，与角色数量无关）。
- **前提**（② 迁移动作）：把这 5 角色→scope 初始化进 Casdoor + 给用户分配（实测 `admin.roles=[]` 尚未配）。

### 10.2 audience ✅ = auth-console（前端）+ 机器 service app
- 前端 authorization-code+PKCE 用 `auth-console` app（client_id `cad5642b…`）。
- 机器身份 client_credentials 用单独 service app（复用 `app-built-in` 或新建）。
- `edge.casdoor.audiences = [auth-console, service-app]`（多 audience 白名单）。

### 10.3 多 org 用户 ✅ = 单 org=单 tenant
Casdoor user 天然单 `owner`(org)，`tenantId←token.owner`；edge 拒绝 header/query 覆盖。多租户用户延后（P0-7）。

### 10.4 前端 OIDC 库 ✅ = oidc-client-ts（Vue3），走 /frontend-plan
③ 后半步；authorization-code+PKCE，复用 auth-console app。前端改造按全局规则走 `/frontend-plan`。

### 10.5 scope 展开归属 ✅ = Casdoor（不在 edge）
早期"A(roles)/B(groups) 在 edge 查表"的纠结**作废**——两者都会让 edge 随角色膨胀。定为：**role/permission→scope 的展开在 Casdoor**（权限中心），edge 只透传展开结果 + 固定 allowlist（见 D3）。Casdoor 侧用 role 还是 permission 建模属 ② 迁移细节，edge 无感、零增长。
