# 企业统一登录（SSO）联邦接入指南

> 场景：公司已有一套**统一登录 / OA 单点登录**（员工用它登 OA、邮箱、门户……）。目标是让员工
> **用同一套公司账号登录本平台名下的各系统**（auth-console / recsys / RAG / …），一次登录、全线通行。
>
> 本文讲**怎么把公司统一登录"联邦"进来**，与 [`统一登录平台接入手册.md`](统一登录平台接入手册.md) 是**两个视角**：
>
> | 文档 | 视角 | 读者 |
> |---|---|---|
> | 统一登录平台接入手册 | **下游项目**怎么用 Casdoor 登录（前端 / 后端 / 机器） | 各业务项目的前后端开发者 |
> | **本文** | **平台侧**怎么让 Casdoor 支持"用公司账号登录"（对接上游企业 IdP） | 平台管理员 / 对接公司 IT 的人 |
>
> **下游项目开发者无需读本文**——联邦配好后，你们的接入方式（`统一登录平台接入手册` 的场景 A/B/C）**一行都不用改**。

---

## 目录

1. [核心原则：Casdoor 做"身份联邦中介"，下游零改](#1-核心原则casdoor-做身份联邦中介下游零改)
2. [第一步：先定协议——你们的公司 SSO 说的是什么？](#2-第一步先定协议你们的公司-sso-说的是什么)
3. ["兼容"的真正难点：三件必须处理的事](#3-兼容的真正难点三件必须处理的事)
4. [仓库侧改动清单](#4-仓库侧改动清单)
5. [分协议操作要点](#5-分协议操作要点)
6. [验证方式](#6-验证方式)
7. [生产化 / 安全 Checklist](#7-生产化--安全-checklist)
8. [相关文档](#8-相关文档)

---

## 1. 核心原则：Casdoor 做"身份联邦中介"，下游零改

本平台所有系统都只做一件事——**验签 Casdoor 签发的 JWT，读 `sub` / `owner` / `groups`**
（后端范本 `auth-platform-admin/.../SecurityConfig.java`）。它们**不关心用户当初是"怎么登进 Casdoor 的"**。

所以接公司 SSO 的正确姿势是：**把公司 SSO 当"上游权威身份源（IdP）"，让 Casdoor 做联邦中介
（identity broker），下游各系统只信任 Casdoor 这一个出口。**

```
员工 ──▶ 公司统一登录 / OA SSO  (权威 IdP)
              │  联邦(OIDC / SAML / CAS / LDAP)
              ▼
          Casdoor (:8000, 中介)  ── 签发统一 JWT(sub/owner/groups) ── 各系统只信任它这一个
              │  同一个 access_token
     ┌────────┼──────────────┐
 auth-console   recsys        RAG …        ← 全部零改(验签逻辑不变)
```

**为什么这样最省**：只要 token 仍由**同一个 Casdoor**（同 `issuer` / 同 JWKS）签发，下游的
`issuer` / `jwk-set-uri` / `aud` 校验和 `groups→权限` 归一化**全部不变**，代码一行不动。

> ⛔ **反模式**：让各业务系统**各自直连**公司 OA SSO、绕过 Casdoor。这会击穿现有架构——
> `<org>/<group>` 归一化、`owner` 租户隔离、SpiceDB ReBAC 判权全都建立在 **Casdoor 的 claim** 上；
> 各系统各接一套上游，身份主键（`sub`）不再统一，判权与审计立刻错乱。**联邦只在 Casdoor 一处做。**

---

## 2. 第一步：先定协议——你们的公司 SSO 说的是什么？

这是**决定性的一步**：它决定在 Casdoor 里配哪种 provider、难不难、要不要改平台代码。

| 公司 SSO 协议 | Casdoor 侧怎么配 | 难度 | 平台代码 |
|---|---|---|---|
| **OIDC / OAuth2**（较现代；或企业微信 / 钉钉 / 飞书底座） | 建 OAuth/OIDC provider，填 `issuer` + `client_id`/`secret` | ⭐ 最简单 | 零改 |
| **SAML 2.0**（ADFS / Okta / 大型企业域） | 建 SAML provider，导入 IdP metadata，配 attribute→claim 映射 | ⭐⭐ 中等 | 零改（需确认 groups/owner 映射） |
| **LDAP / AD 域账号**（"共用同一套账密"而非免登跳转） | Casdoor 加 LDAP 身份源，员工用域账号密码登 Casdoor | ⭐⭐ 中等 | 零改（但**不是**"已登 OA 就免密跳转"） |
| **CAS**（国内泛微 / 致远 / 用友等老 OA 常见） | 需先确认 Casdoor 对**上游 CAS 的消费**支持（Casdoor 更擅长做 CAS *server*），必要时加桥接 | ⭐⭐⭐ | 先验证可行性 |
| **自研 ticket / cookie 票据**（最老那种 OA） | 非标准协议，Casdoor 无内置——需写一个小 bridge 把票据换成 OIDC 再喂给 Casdoor | ⭐⭐⭐⭐ | 需写 bridge 服务 |

### 怎么快速判断协议

点一下 OA 里的"统一登录 / 单点登录"，看浏览器地址栏**跳转 URL 的参数**：

| URL 特征 | 协议 |
|---|---|
| `/cas/login?service=…` | CAS |
| 带 `SAMLRequest=…` | SAML 2.0 |
| `/oauth/authorize?client_id=…` 或存在 `/.well-known/openid-configuration` | OIDC / OAuth2 |
| `ldap://` / 只在"输账号密码"、无跳转 | LDAP / AD |

> 拿不准就**直接找公司 IT / OA 供应商要"单点登录对接文档"**——他们通常有现成的
> clientId/secret 申请流程和 endpoint 清单。把协议确定下来再动手，能省掉一半返工。

---

## 3. "兼容"的真正难点：三件必须处理的事

登录协议本身通常一两天就能通。真正的工作量在下面这三件——它们决定"公司账号登进来之后**能不能真的用**"：

### 3.1 账号打通 + 稳定主键（`sub`）

公司员工首次经 SSO 登录时，Casdoor 要 **JIT（首次登录即时建号）**把这个外部身份落成一个 Casdoor 用户。

- **关键**：`sub`（Casdoor 用户的稳定 UUID）必须**稳定映射到公司账号的唯一 id（工号 / employeeId / 域账号）**。
  Casdoor 靠 provider 的**账号绑定**维持这层映射——同一个人下次登录必须命中同一个 Casdoor 用户，
  否则 `sub` 变了，判权（`user:<sub>` 的关系元组）、审计、业务数据关联**全部对不上**。
- 若公司账号唯一键是工号，映射就绑工号；**切忌用 username/邮箱当唯一键**（可改、可重名）。

### 3.2 `groups` / `owner` 契约（否则登进来也是 403）

后端把 `groups` claim 的最后一段当权限（`SecurityConfig.jwtAuthenticationConverter` 取 `lastIndexOf('/')` 之后）。

- **`owner`（租户）**：SSO 用户要落到**正确的 Casdoor org**——它就是 token 的 `owner`，是多租户隔离键。
  确认联邦进来的用户归属的 org 是你预期的租户。
- **`groups`**：SSO 新用户若**没有任何 group**，token 的 `groups` 为空 → 后端**读写端点一律 403**
  （验签过了但没有任何 authority）。所以必须把**公司侧的部门 / 角色**映射成 Casdoor group：
  - OIDC/SAML：在 provider 里配 **claim/attribute → Casdoor group** 的映射规则；
  - 或登录后由平台从公司目录**同步**组关系（见 3.3）。
- 组 id 仍遵循平台约定：经 `CasdoorGroupIds` 编码为 **`<org>_<group>`** 租户前缀，防跨租户同名组串权。

### 3.3 SpiceDB 关系同步（否则细粒度判权空转）

SSO 新用户在判权库（SpiceDB）里**没有任何关系元组**——粗粒度门禁（`hasAuthority`）能过，
但细粒度 ReBAC（"能不能看这一篇文档"）会全拒。让新用户"进来即可判权"要靠现成的差量同步器：

- **组成员（RBAC）**：`casdoor/GroupSyncService` —— `POST /admin/casdoor/sync` + webhook 触发。
- **部门树**：`casdoor/DepartmentSyncService` —— `POST /admin/casdoor/sync-departments`，
  由 `authz.casdoor.department-sync-enabled` 门控 + `ReconcileJob` 定时对账。

> 这两件事（3.2 补组、3.3 同步）通常是接公司 SSO 里**最花时间**的部分，登录协议反而最快。
> 建议先把"登进来、`sub`/`owner` 正确"跑通（§6 验证到这一步即算登录联邦成功），再做补组与同步。

---

## 4. 仓库侧改动清单

改动都很小，且**大多在 Casdoor 配置层，不碰 Java**：

| 位置 | 改动 | 何时必须 |
|---|---|---|
| `deploy/casdoor/app.conf` 的 `socks5Proxy = 127.0.0.1:10808` | **内网公司 IdP → 清空这行**（现值是给翻墙访问海外 IdP 用的，指着 `127.0.0.1` 在容器内还够不到宿主机，会拖累内网直连）。仅当上游是海外 IdP 才保留并改成 `host.docker.internal:10808` | 内网 IdP 必改（清空） |
| **新增 `deploy/casdoor/init_data.json`** + 在 `deploy/docker-compose.yml` 挂载 | 现在 compose **只挂 `app.conf`、没有 init_data**——你在 Casdoor UI 里配的 provider 只存在 Postgres 卷里，`docker compose down -v` 一次就全丢。要可复现 / 团队共享，必须把 `providers` + `application.providers` 固化进 init_data | 想要可复现时必做 |
| 后端 `auth-platform-admin/.../SecurityConfig.java` 的 aud 校验 | **复用现有 Casdoor 应用（推荐）→ 零改**。仅当为 SSO **单建独立 application**（新 `client_id`/`aud`）且开了 aud 校验（`authz.security.client-id` 非空）时才需改——当前 `jwtDecoder` 只支持**单个** aud（`jwt.getAudience().contains(aud)`），多 aud 要改成集合校验 | 仅"新建独立 app + 开 aud 校验"时 |
| 前端 `auth-console`（及其它下游 SPA） | **不改**：授权码 + PKCE 下，SSO 登录按钮由 Casdoor 登录页渲染，前端只 redirect 到 Casdoor 再回调拿 token | 一般不需要 |
| `docs/*`（本文 + 手册指针） | 保持文档同步（本文已建；手册已加指针） | — |

---

## 5. 分协议操作要点

### 5.1 OIDC / OAuth2（最省）

1. Casdoor 后台 New Provider，category 选 **OAuth**（内置 Google/GitHub/企业微信/钉钉/飞书模板）或
   **通用 OIDC**（填公司 IdP 的 `issuer`，Casdoor 自动读其 discovery）。填 `client_id` / `client_secret`。
2. 把 provider 加到目标 **Application 的 `providers` 列表**（如 `auth-console` / 共享应用 `rag-shared`）——
   加了之后 Casdoor 登录页才出现"用公司账号登录"按钮。
3. 在公司 IdP 侧登记回调 `http://<casdoor-host>/callback`（Casdoor 的统一回调）。
4. 配 claim 映射：把公司 IdP 的部门/角色 claim 映射成 Casdoor group（见 §3.2）。

### 5.2 SAML 2.0

1. New Provider，category **SAML**，导入公司 IdP 的 **metadata**（或手填 SSO URL + 证书）。
2. 绑到 Application；在公司 IdP 侧把 Casdoor 登记为 SP（提供 Casdoor 的 SP metadata / ACS URL）。
3. **重点**：配置 SAML **attribute → Casdoor 字段**映射，确保能拿到稳定工号（→ `sub` 绑定键）
   和部门/角色（→ `groups`）。

### 5.3 LDAP / AD

- 适合"**共用公司账号密码**"而非"已登 OA 免密跳转"的诉求。Casdoor 加 **LDAP 身份源**，配 baseDN /
  bindDN / 过滤器；员工在 Casdoor 登录页输**域账号密码**，Casdoor 向 AD 校验。
- 可把 AD 的 `memberOf` / OU 映射成 Casdoor group。

### 5.4 CAS / 自研票据（最麻烦，先评估）

- **CAS**：先确认当前 Casdoor 版本是否支持**消费上游 CAS**（Casdoor 主要作为 CAS *server*）。
  若不支持，选项是：让 OA 侧额外开一个 OIDC/SAML 出口，或写一个 **CAS→OIDC bridge** 小服务
  （校验 CAS ticket → 换成标准 OIDC 交给 Casdoor）。
- **自研 ticket / cookie**：无标准可循，必须写 bridge：验证公司票据 → 建立/查到对应身份 →
  以标准 OIDC 形式喂给 Casdoor。**动手前务必拿到 OA 的票据校验接口文档。**

> §5.4 两类**先做可行性验证再排期**，别默认一定能"零代码"接上。

---

## 6. 验证方式

联邦是否成功，**逐层验**（与手册 §9 同一套思路，可直接复用 `deploy/sso-smoke.sh`）：

1. **能登进来**：浏览器走一遍"用公司账号登录" → 成功回跳、拿到 Casdoor 的 access_token。
2. **claim 契约正确**（最关键）：解 token payload，断言
   - `sub` 是稳定 UUID，且**同一员工重复登录不变**（这是 §3.1 的核心）；
   - `owner` = 预期的租户 org；
   - `groups` 形如 `["<org>/<group>", …]`（若为空，回去补 §3.2 的组映射）。

   ```bash
   # 拿到 token 后解 payload(不验签，只看形状)
   echo "$AT" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{sub, owner, groups, aud, iss}'
   ```
3. **后端验签 + 授权**：复用 `deploy/sso-smoke.sh` 的 Layer 2（无 token→401 / 篡改→401 / 合法→200 或 403）。
   合法 token 得 **403 而非 401** 说明"登录联邦成功、只是还没补组/权限"——回到 §3.2 / §3.3。
4. **细粒度判权**：跑组/部门同步后，用该用户校验一条真实资源的 ReBAC 判定（见判权侧冒烟）。

---

## 7. 生产化 / 安全 Checklist

- [ ] **上游联邦只在 Casdoor 一处做**，各下游系统不直连公司 SSO（§1 反模式）。
- [ ] **`sub` 映射稳定**：绑公司唯一键（工号），验证同一员工多次登录 `sub` 不变（§3.1）。
- [ ] **`owner` 落到正确租户 org**，不被外部输入覆盖。
- [ ] **组映射到位**：SSO 用户有正确的 `groups`（否则全 403），组 id 走 `<org>_<group>` 前缀。
- [ ] **代理**：内网 IdP 清掉 `app.conf` 的 `socks5Proxy`；生产整体去 dev 代理。
- [ ] **配置固化**：provider / application.providers 写进 `init_data.json` 并纳入 compose，避免重建卷即丢。
- [ ] **HTTPS**：生产 Casdoor 用 `https://sso.yourcorp.com`；下游 `issuer`/`jwk-set-uri`/前端 `authority` 同步换。
- [ ] **secret 密文存储**（env / secret manager），不进 git、不进前端 bundle。
- [ ] **JWKS 高可用**：Casdoor-only 阶段 SSO 挂 = 全线登录不了；JWKS 需可达 + decoder 缓存兜短时抖动。
- [ ] **同步兜底**：`GroupSyncService`/`DepartmentSyncService` + `ReconcileJob` 定时对账，
      `authz.casdoor.delete-threshold` 删除熔断防误删。
- [ ] **fail-closed 复核**：`sub`/`owner` 缺失、验签失败、JWKS 不可达 → 一律拒绝，不降级放行。

---

## 8. 相关文档

| 你要做的 | 看这个 |
|---|---|
| 下游项目怎么用 Casdoor 登录（前端 / 后端 / 机器） | [`统一登录平台接入手册.md`](统一登录平台接入手册.md) |
| 后端 JWT 验签 + groups→权限 范本 | `auth-platform-admin/.../SecurityConfig.java` |
| Casdoor 组 / 部门同步到 SpiceDB | `auth-platform-admin/.../casdoor/`（`GroupSyncService`、`DepartmentSyncService`） |
| 组 id 租户前缀编码 | `CasdoorGroupIds`（`<org>_<group>`） |
| 一键开通租户（org+user+成员组） | `deploy/casdoor-tenant-provision.sh` |
| SSO 接入自检（Layer 0-2 一键断言，可复用） | `deploy/sso-smoke.sh` |
| 从登录到判权 / 一致性语义 | 仓库根 `CLAUDE.md`、[`统一登录平台接入手册.md` §8](统一登录平台接入手册.md) |

---

*本文为"平台侧对接上游企业 SSO"的联邦指南，与下游接入手册互补。协议细节以公司 IT 的对接文档
与 Casdoor 版本文档为准；CAS / 自研票据两类务必先做可行性验证再排期。*
