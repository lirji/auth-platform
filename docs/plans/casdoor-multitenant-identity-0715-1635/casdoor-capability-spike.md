# Casdoor 能力查证（A vs C 定夺）— 2026-07-15

> 目的：确认 Codex 标为"待验证/unproven"的 **Solution C（单 app 跨 org 登录）** 到底 Casdoor 支不支持，从而在 A（一租户一 app）与 C（Google 式单 app + org 选择）之间定夺。方法：context7 拉官方文档 + 探本地活实例（authz-casdoor :8000, casbin/casdoor:latest）。

## 1. 官方文档（context7 /casdoor/casdoor-website）确认

- **Shared application（共享应用）**："A shared application can be used by multiple organizations. Only the built-in organization can create shared applications. Each organization uses the same application with an org-specific identifier by appending `-org-<organizationName>` to the client ID or application name." → **一个 app 可跨多 org 登录**，须 built-in 拥有，每 org 派生 client id `<clientId>-org-<org>`。
- **Org select mode（指定登录组织）**：应用编辑页有 `Org select mode`：`None`（禁用）/`Input`（用户输入 org 名）/`Select`（下拉选 org）。在 `app-built-in` 上开启后，用户选组织并被重定向到 `/login/<organization>`。→ **登录页原生支持选/输组织**（≈ Google HRD）。
- **登录 URL 可按组织组织**：`/login/<organization>`（org 走 URL path，可子域名化）。
- **User 属于且仅属于一个 org**：API 标识 `<org>/<username>`，稳定 id 是 UUID。→ **owner=tenant 语义在 C 下保持不变**（用户仍按 org 隔离，只是共享登录入口）。

## 2. 本地活实例探测（admin API）

- **组织**：`built-in` / `acme` / `beta`（3 个）。
- **用户**：`built-in/admin`；`acme/alice`、`acme/bob`；`beta/carol`。→ 用户确实按 org 隔离。
- **应用**：
  | app | organization | clientId | isShared | orgChoiceMode |
  |---|---|---|---|---|
  | auth-console（前端默认用） | **built-in** | cad5642b16071c3513d4 | **false** | **""** |
  | rag-acme | acme | ragacme0client0acme01 | false | "" |
  | rag-beta | beta | ragbetaclient01 | false | "" |
- 前端 compose：`VITE_CASDOOR_CLIENT_ID=${SHOWCASE_CASDOOR_CLIENT_ID:-cad5642b16071c3513d4}`（默认 auth-console）；前端 `.env.local` 只设 `VITE_AUTH_MODE=oidc`，无 client_id 覆盖。

## 3. 根因（前端只能登某一个租户）——确认

前端被钉在**单一 application/org**（默认 auth-console=built-in；或环境覆盖到某租户 app），**登录不带 org 选择**，且现有 app 全部 `isShared=false`、`orgChoiceMode=""`。所以只有那一个 org 的用户能登。这与 app 是 auth-console 还是 rag-acme 无关——机制都是"单 app/单 org 钉死"。

## 4. 对 A vs C 的结论

**Codex 把 C 判为"能力未证明"是错的——Casdoor 原生支持 C**（Shared Application + Org select mode + `/login/<org>`），且 **C 下 `owner=tenantId` 保持不变**（用户仍按 org 隔离），因此 C **没有** B/D 的"owner 不再等于租户"缺陷。C 更接近 Google GCIP/HRD 的业界主流，运维成本远低于 A（一个 app，新增租户=建 org，不必每租户建 app / 配 client_id / 重启 edge）。

**建议：从 A 改为 C（共享 app + org 选择）为主方案。**

**C 的残留"待验证"（属实现级，非决策级，列为实现第一步）**：
1. 共享 app 每 org 的 **token `aud` 到底是 `<base>` 还是 `<base>-org-<org>`**（决定 edge aud 校验写法）。
2. **oidc-client-ts 是否需要用 org 专属 client_id `<base>-org-<org>`**（即前端是否仍需"登录前先知道 org"，还是可交给 Casdoor 的 org-select 页处理）。
   - 需一次真实 authorization_code/PKCE 流程实测确认（password grant 在这些 app 上未启用，`unsupported_grant_type`，无法用密码流替代）。

**A 仍更优的场景**：需要每租户**完全隔离/独立品牌**的登录页、每租户独立 redirectUris、或合规要求 app 级隔离。对内部/showcase 平台，C 更合适。

## 5. C 方案下的改动要点（相对 FINAL_PLAN 的 A）

- Casdoor：把登录 app 改为 **built-in 拥有的 Shared Application**（或新建），开 `orgChoiceMode=Input/Select`；各租户只需建 org + 用户 + 角色/permission。
- 前端：authority 指向共享 app；**要么**用 org-select 页（用户在 Casdoor 选 org，前端几乎不用按租户切 client_id）**要么**按 org 用 `<base>-org-<org>` 构造（取决于 §4 实测）。仍要处理回调/续期。
- edge：aud 校验从"单值 membership"改为"接受共享 app 的 `<base>[-org-*]` 家族 + 校验 `owner` 与 org 一致"（仍是 `(owner,aud)` 绑定思想，但只有一个 base client）。
- 开通脚本：不再每租户建 app + 追加 audiences + 重启 edge；只建 org/user/role/permission。运维大幅简化。
