# 阶段0 前置调研清单 —— 解锁里程碑 B（Casdoor SSO）/ C（RBAC 迁移）

> 关联 [FINAL_PLAN.md](./FINAL_PLAN.md) §10 阶段0、§6.3 已知弱点、§12 风险。
> **性质**：这些是「靠写代码解决不了、必须在真实 Casdoor + auth DB 环境实测/确认」的硬前置。里程碑 A（①ReBAC）**不依赖**本清单，可独立推进。**任一硬项失败即阻塞对应里程碑，不允许在实现时凭经验猜字段名或长期回退到 auth-service 做动态权限计算。**
> **谁来跑**：需要真实 Casdoor 应用凭据与 auth DB 只读访问——请你（或运维）提供环境后，我可协助解析/生成报告。

## 交付物总览

| 编号 | 调研项 | 阻塞谁 | 硬/软 |
|---|---|---|---|
| P0-1 | Casdoor access token claim 契约 | B（SSO） | 硬 |
| P0-2 | Casdoor 组/用户 **写** API + 鉴权 | C（组 import） | 硬 |
| P0-3 | 机器身份 grant（client_credentials） | B/脚本换 token | 硬 |
| P0-4 | auth DB 数据质量 + username→sub crosswalk | C（迁移） | 硬 |
| P0-5 | role → space relation 映射表（业务确认） | C | 硬 |
| P0-6 | EffectivePermissionResolver scope 基线 + Casdoor claim 映射 | B（粗粒度 scope） | 硬 |
| P0-7 | 多租户用户 active-tenant 规则 | B | 硬 |

---

## ✅ 已实测结论（本地 docker Casdoor :8000，2026-07-14）

> 方法：`app-built-in` client（client_id `ea46d9a8…`）+ `admin/123` password grant 拿真实 token 实测。Casdoor 表实际建在 postgres `spicedb` 库。P0-1 / P0-2(读) / P0-3 有结论；P0-2(写) / P0-4/5/6/7 仍待。

### P0-1 ✅ claim 契约
| 语义 | claim | 实测值 |
|---|---|---|
| userId（SpiceDB subject） | `sub` | 稳定 UUID `e56eeac5-101d-…` |
| tenantId | `owner` | Casdoor org = `built-in` |
| username | `name` | `admin` |
| groups | `groups` | `<org>/<group>` 数组，如 `built-in/authz-admin` |
| roles | `roles` | 数组（admin 为空） |
| scopes | `scope` / `permissions` | **仅标准 OIDC scope（openid/profile/…），无业务 scope（ingest/chat）** |

- issuer `http://localhost:8000`；jwks `/.well-known/jwks`；RS256；`token-exchange`/`client_credentials` grant 均支持。
- **结论**：edge token exchange 映射 `tenantId←owner`、`userId←sub`、`groups←groups`。**scopes 不能直接取**（Casdoor 无业务 scope）→ P0-6 未解决前是 ③ 的阻塞点。

### P0-2 读 ✅ / 写 待
- 读：`GET /api/get-groups?owner=<org>`、`/api/get-users?owner=<org>` 可用（现有 `GroupSyncService.groupMembers/groupNames` 依赖）。
- ⚠️ 实测不一致：`admin.groups=["built-in/authz-admin"]` 但 `get-groups` 只返回 `engineers`——**组成员引用可能指向 get-groups 不返回的组**，reconciler 差量对账必须容错（不能把"读不到定义"当成"该删成员"）。
- 写 API（add-group / update-user.groups）未测（涉及修改 Casdoor，待确认后实测）。

### P0-3 ✅ 机器身份
discovery 支持 `client_credentials` + `token-exchange`；password grant 实测可用。

### 🔑 关键结论：③ 身份切换是 B 的前提
Casdoor `sub`（UUID）**≠** 当前 langchain4j 的 auth-service userId。B 要判权对齐，必须让 `TenantContext.userId = Casdoor sub`（③）。故 ② 的组/space 绑定在 ③ 落地前**判权时空转**（写的是 `user:<CasdoorSub>`，而 knowledge 判权用 auth-service userId）。→ **B 的第一块应是 ③ 的 edge token exchange**；且 scope 映射（P0-6）是 ③ 的先决。

---

## P0-1 Casdoor access token claim 契约 ｜阻塞 B

- **目的**：edge 拟新增的 `CasdoorTokenExchangeFilter` 要从已验签 JWT 构造 `TenantContext.Tenant(tenantId, userId, scopes)` 再调现有 `InternalToken.mint`。必须先知道每个字段落在哪个 claim。
- **怎么做**：用真实 Casdoor 应用走一次 authorization-code+PKCE 登录，抓下 access token，解码（`jwt.io` 或 `jq`）记录：
  - `iss` / `aud` / `exp` / `sub`（→ 未来 `userId`，须全局稳定、非 username）；
  - **tenant 在哪个 claim**（`owner`？`organization`？自定义 claim？）；
  - **groups 在哪个 claim**（数组？含 owner 前缀？）；
  - **scopes/permissions 在哪个 claim**（`scope` 字符串？`permissions` 数组？角色名？）；
  - JWKS/issuer discovery URL（`/.well-known/openid-configuration`）。
- **完成判据**：产出一份「claim → 语义」映射表 + 一个可复现的 contract 测试样本 token。`sub`、tenant claim 缺失时的行为约定为 **fail closed**。
- **失败即阻塞**：若 Casdoor 无法稳定签发 tenant/scopes claim → B 阻塞，升级设计决策，不得在线回查 auth-service 掩盖。

## P0-2 Casdoor 组/用户写 API + 鉴权 ｜阻塞 C

- **目的**：现仓库 `CasdoorClient` **只有读**（`groupMembers/groupNames`）。②要把 legacy 组/角色 import 进 Casdoor，需要写能力。
- **怎么做**：实测 Casdoor Admin API：`POST /api/add-group`、`POST /api/update-group`、用户加入组（`update-user` 的 groups 字段或专用 endpoint）、分页 `GET /api/get-groups`。确认鉴权（client id/secret basic？access token？）。
- **完成判据**：能用脚本幂等创建一个 tenant-scoped 测试组并加成员、可重跑无副作用；记录确切 endpoint/字段/鉴权，冻结为 `CasdoorGroupImportService` 的实现合同。
- **失败即阻塞**：无写 API → C 的「import 进 Casdoor」方案需改为「Casdoor 之外维护组」，重评。

## P0-3 机器身份 grant（client_credentials）｜阻塞 B / 脚本换 token

- **目的**：eval-service 与 `deploy/*.sh` 现用 `X-Api-Key`/`EVAL_API_KEY`。目标态改用 Casdoor service account 短期 token。
- **怎么做**：确认 Casdoor 支持 `client_credentials`（或等价 service account token）；实测拿到一个可被 edge 接受的 service token；确认其 sub/claim 能表达「机器调用方 + 目标 tenant」。
- **完成判据**：一个 service token 能通过 edge 验证并被下游还原为可用 Tenant；记录 grant 方式与轮转策略。

## P0-4 auth DB 数据质量 + crosswalk ｜阻塞 C

- **目的**：把 auth-service 的 `USERS/USER_ROLE/TENANT_ROLE/AUTH_GROUP/GROUP_ROLE/USER_GROUP`（四层 RBAC）迁到 Casdoor/SpiceDB 前，须先体检数据并建立 `username → Casdoor sub` 映射。
- **怎么做**（auth DB 只读）：统计 用户数；重复/缺失 userId；**跨租户同名组**（组现为全局定义、成员按 username 存）；未知/自定义角色；无法 crosswalk 到 Casdoor sub 的用户。
- **完成判据**：一份数据质量报告 + crosswalk 覆盖率；明确「无可审计 owner manifest 的历史文档保持 unknown、不伪造 owner」。
- **失败即阻塞**：crosswalk 覆盖不全 → 迁移 dry-run 会漏人，需先补齐身份对齐。

## P0-5 role → space relation 映射表（业务确认）｜阻塞 C

- **目的**：把 auth-service 角色映射到 SpiceDB space relation（绑定为 role group）。
- **待业务拍板**（FINAL_PLAN §7.3 初始建议，**未确认不得 apply**）：

  | auth-service role | 建议 space relation | 需确认点 |
  |---|---|---|
  | admin | admin | ✓ |
  | editor | editor | ✓ |
  | viewer | viewer | ✓ |
  | analyst | viewer | analytics scope 是否应给知识编辑权？ |
  | approver | viewer | approve scope 是否应给知识编辑权？ |
  | custom | 无默认 | 必须逐个显式配置，否则报告并跳过 |

- **完成判据**：业务签字的映射表；未知角色策略＝报告并跳过、不自动映射。

## P0-6 scope 基线 + Casdoor claim 映射 ｜阻塞 B

- **目的**：ReBAC 不替代现有 scope 门禁（如 `ingest`/`public-ingest`）。SSO 切换后，edge 只把 Casdoor 已签发且在允许词表内的 scope 写入内部 JWT。切换前后同一用户有效 scope 集必须相等。
- **怎么做**：用 auth-service `EffectivePermissionResolver` 导出每 tenant/user 的有效 scope 基线；设计到 Casdoor roles/permissions 或可签名 claim 的映射；产出差异报告。
- **完成判据**：允许 scope 词表冻结；切换前后 scope 集一致（经审批废弃项除外）。

## P0-7 多租户用户 active-tenant 规则 ｜阻塞 B

- **目的**：一个用户属多个租户时，单次请求的 active tenant 必须来自**已验签且能证明 Casdoor organization/membership** 的 claim 或受控 token exchange；edge **不接受**客户端 header/query 覆盖 tenantId（防越权）。
- **完成判据**：明确 active-tenant 来源 claim 与校验规则；跨租户同 ID 的 E2E 用例设计。

---

## 与里程碑 A 的关系

里程碑 A（shadow）用现成的 `TenantContext.userId()` 作 SpiceDB subject，**完全绕开本清单**。A 上线并观察 shadow 差异指标的同时，本清单可并行推进；P0-1 与 P0-4 建议最先做（分别解锁 B、C 的主路径）。
