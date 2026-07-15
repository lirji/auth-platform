# FINAL_PLAN — langchain4j-platform 全面接入 Casdoor：多租户登录/身份 + 部门层级知识隔离 + 全平台 scope 强制

> Codex 多视角规划（requirements / codebase-analysis / solution A–D / comparison / test-plan）在写完 test-plan 后到 10 分钟超时被杀，**未及写本文件**；本 FINAL_PLAN 由 Claude 依据 Codex 产物 + 对两仓库的逐条核验合成，并加入 §0 的 **MVP/Full 分级**（Claude 复核认为完整动态注册表对当前诉求属过度设计，拆成两级）。
> 关联既有计划 `docs/plans/casdoor-authz-rollout-0715-1101/`（②服务端 scope 强制＝其 Phase B-0，本文件复用不重造）。Casdoor 能力查证见 `casdoor-capability-spike.md`。
>
> **2026-07-15 与用户澄清后的重大修订（务必先读 §A）**：本任务实为**两条耦合工作流**——(I) **多租户登录/身份**（A/C，见 §0/§3，`tenant=项目`；**A/C 尚未最终拍板**，spike 证明 C 亦可行）；(II) **部门层级知识隔离**（§A，**已与用户逐条定稿**）。二者经 **“部门=Casdoor 嵌套 group”** 相连。§A **取代**先前 rag-tenant-authz 的 **D3「同租户默认可见」**（与「其他部门读不到」相反）。

## 0. 结论先行（务必先读）

**问题一（用户直接问的）：前端只能登录 acme 租户** —— 根因已核实：本部署是「一租户=一 Casdoor org=一 app（`rag-<tenant>`, client_id `rag<tenant>client01`）」，但**前端 `oidc.ts` 的 `UserManager` 写死单一 client_id `cad5642b16071c3513d4`、登录不带 org 选择**，且 **edge `audiences` 是静态单值 + `audienceValidator` 只做 membership、不绑定 `(owner,aud)`**。所以登录页只认那一个 app 所属 org 的用户，其它租户的 app 前端从不指向、edge 也不接受。

**采用方案：Solution C（Casdoor Shared Application + Org select，用户 2026-07-15 定）**。经 `casdoor-capability-spike.md` 实测：Casdoor **原生支持** Shared Application（built-in 拥有、一个 app 跨多 org、每 org 派生 client id `<base>-org-<org>`）+ Org select mode（`None`/`Input`/`Select`，或 `/login/<org>` 路由），且 **`owner=tenantId` 保持不变**（用户仍按 org 隔离）——故 C 无 B/D 的“换 owner 语义”缺陷。C 比 A（一租户一 app）**运维大幅省**：新增项目/租户＝只建 org（+用户/角色），**不必每租户建 app、不必往 `CASDOOR_AUDIENCES` 加 client_id、不必重启 edge**，贴近 Google GCIP/HRD。原 Codex 评分 A=29>C=23 是在“C 能力未证明”的错误前提下打的，实测推翻。

**C 的落地形态**：
| 维度 | 做法 |
|---|---|
| Casdoor | 建/改一个 **built-in 拥有的 Shared Application**（`isShared=true`），开 `orgChoiceMode=Input`（或 `Select`）；各租户只建 org + 用户 + 角色/permission。|
| 前端 | authority 指向共享 app；登录时确定 org（子域名/选择/HRD）→ 用 `client_id=<base>-org-<org>` 构造 `UserManager`（**仍需登录前知道 org**，与 A 前端成本相近）；回调/续期按 org 恢复。|
| edge | `audienceValidator` 从“静态单值 membership”改为 **接受 `<base>-org-*` 家族 + 绑定 `owner`==aud 后缀 org**（`(owner,aud)` 绑定思想，但只有一个 base client，无需逐租户 audiences 列表 / 无需重启）。|
| 开通 | `casdoor-tenant-provision.sh` 从“每租户建 app”简化为“只建 org/user/role”；不再动 edge audiences。|

**C 的唯一残留待验证（实施 Phase 0 用本地 :8000 实测钉死，非决策阻断）**：共享 app 每 org 的 token **`aud` 精确值**（`<base>` 还是 `<base>-org-<org>`）与 **oidc-client-ts 用哪个 client_id**——建个 shared app 开 password/authz_code 跑一次真 token 看 `aud/owner` 即可确定 edge 家族校验与前端 client_id 构造的确切写法。文档强指向 `<base>-org-<org>`。

> 注：先前基于 A 的 “MVP/动态注册表分级” 已作废（那是 A 的多 audience + 免重启子系统）；C 天然免重启（edge 按 aud 家族 + owner 校验，新 org 无需改 edge 配置），不需要动态注册表。

**②全平台服务端 scope 强制** = 直接复用 `casdoor-authz-rollout` 的 **Phase B-0 `EdgeScopeEnforcementFilter`**（route 前缀→scope，off/shadow/enforce）。本文件只负责与①接线，不重造。**它是「都接入」的实质工作量大头**，且与①正交，可并行或后置。

## A. 部门层级知识隔离模型（已与用户逐条定稿，取代 D3）

> 这是本轮**知识库授权模型的定稿**。与 §0 的登录（A/C）**正交但耦合**：部门 = Casdoor 嵌套 group。

### A.1 已确认业务规则（2026-07-15 与用户定稿）

1. **层次**：`项目(=租户=Casdoor org) ⊃ 部门树 ⊃ 文档`。登录边界在“项目/租户”这层（对应 A/C）；**部门树是项目内部**的细分。
2. **部门是一棵层级树**（如 `研发与数据中心 → 技术平台部 → 电商组`）；**一个用户只属于一个部门**。
3. **部门树 = Casdoor 嵌套 group**（Casdoor group 原生支持父子 + 用户归属）；用户的部门 = 其 Casdoor group，token `groups` claim 带出。
4. **文档默认可见 = 模型 A（纵向自动、横向申请）**：文档归属 = 上传人部门；可读者 = **本部门成员 + 所有上级（祖先）部门成员**（自动）；**平级、下级、其他分支读不到**。即“上级能看下级产出，反之不行”。
5. **公共文档**：所有人可读。
6. **跨部门 = 申请**：申请通过后**只把这一篇文档**开给某个人（复用现有 `document.viewer` + `DocumentShareController`）。
7. **谁能开权限/审批**：**文档 owner + 本部门成员（“这个组里的人”）+ 本部门管理员 + 所有上级部门管理员**；**不走 workflow 审批流**，有权者直接点同意（即直接写 `viewer` 授权）。
8. **可见性自动**：上传即自动归到上传人部门，无需每篇手动设可见范围。

### A.2 定稿 SpiceDB 模型（`knowledge.zed`）

```
definition user {}

definition department {
    relation parent: department              // 上级部门（部门树；根部门无 parent）
    relation member: user                    // 本部门成员（一人一部门）
    relation admin:  user | group#member     // 部门管理员

    // 能自动读“本部门文档”的人 = 本部门成员 ∪ 所有上级部门成员（递归向上）
    permission doc_reader = member + parent->doc_reader
    // 能审批/管理“本部门文档”的人 = 本部门管理员 ∪ 所有上级部门管理员（递归向上）
    permission doc_admin  = admin  + parent->doc_admin
}

definition document {
    relation home_dept: department           // 归属部门（= 上传人部门）
    relation owner:  user                     // 上传人
    relation viewer: user                     // 申请通过后单独开给某人（复用现有 share）
    relation public: user:*                   // 公共文档：所有人可读

    permission view  = owner + viewer + public + home_dept->doc_reader
    permission share = owner + home_dept->member + home_dept->doc_admin   // owner + 本部门成员 + 本/上级管理员
    permission edit  = owner                                              // 删除/覆盖：仅上传人（用户 2026-07-15 定 V-06）
}
```

> **V-06 定稿**：删除/覆盖文档判 `edit = owner`（**仅上传人**，非部门成员/管理员）。原 `DocumentService` 覆盖/删除判的 `edit` 语义收敛为此。

**验证**（树 `研发与数据中心 → 技术平台部 → 电商组`；另有平级 `产品部门`）：电商组上传的文档 `view = owner + viewer + public + doc_reader(电商组)`，而 `doc_reader(电商组)=电商组 + 技术平台部 + 研发与数据中心`。→ 本部门 + 一路上级可读；产品部门/下级/平级读不到。✓ `share` = owner + 电商组成员 + (电商组∪上级)管理员。✓

> 递归 `parent->doc_reader` / `parent->doc_admin` 是 SpiceDB 标准的自引用权限（与 folder 层级同构），支持。**方向与现有 `space/folder` 的“向下继承”相反**（现 `document.view=…+parent_space->view` 是“能看空间→能看其中文档”），故这是真实的模型新增，不是纯配置。

### A.3 复用 vs 改动

**大量复用（几乎零新增）**
- **单篇分享/申请落地** = 现有 `document.viewer` + `DocumentShareController`（share/unshare）+ `KnowledgeAccessApplicationService`。仅把授权判权从 `edit` 改为新 `share` 权限。
- **公共文档** = 现有 `public_viewer` 语义。
- **检索判权机制不变**：RAG 读路径仍是“融合后 `filterReadable` 调 `checkBulk(view)` 过滤”，只是 `view` 背后 schema 从“租户单桶”换成“部门层级”——`checkBulk`/`filterReadable`/F3/core 那套原样复用。
- **部门树同步** = 现有 `admin GroupSyncService` + `CasdoorGroupIds`，扩成“连 group 父子关系也同步”。

**必改**
- `auth-platform-core/.../schemas/knowledge.zed`：加 `department` 定义；`document` 改为 `home_dept`/上述 `view`/`share`（保留/清理 space·folder 视迁移而定）。
- `knowledge-service` `KnowledgeResourceIds`：加 `department(project, deptId)`；`onDocumentCreated` 写 `home_dept=上传人部门`（替代 `parent_space=<租户>_default`）。
- **身份把 department 带进 `TenantContext`**：edge `CasdoorTokenExchangeFilter` 从 token `groups` 取用户部门 → 内部 JWT → `TenantContext`（platform-security 加 `department` 字段）。**一人一部门**，多 group 时需定唯一部门规则。
- `DocumentShareController`/`KnowledgeAccessApplicationService`：`@CheckAccess` 从 `edit` 改判 `share`。
- `admin GroupSyncService`/`CasdoorClient`：同步 Casdoor group **父子（parent）+ admin** 到 SpiceDB `department`（现仅同步 membership）。
- **推翻 D3**：删除 `rag-authz-fixture.sh` 的 default-space viewer 绑定语义，改为按部门 seed。

### A.4 与登录（A/C）的耦合点
部门 = Casdoor 嵌套 group，用户登录后 token 的 `groups` 决定其部门。**所以“接入 Casdoor”在知识库这块的落点就是 §A**。A/C 决定“用户从哪个 Casdoor org/app 登进来（项目/租户层）”，§A 决定“他在项目内能看哪些文档（部门层）”。两者可分开推进，但身份链路（token→部门→TenantContext）是共用的。

### A.5 待确认/实现期核验
- SpiceDB 固定版本下 `parent->doc_reader` 递归深度与性能（部门树深度通常个位数，风险低，仍压测）。
- Casdoor group 的 parent/子级 API 字段与 admin 标记方式（实施首步对本地 Casdoor 探活确认，勿臆测字段）。
- 历史数据：既有挂在 `<租户>_default` 的文档需 backfill 到正确 `home_dept`；无历史数据的 dev 可跳过（显式确认）。

## B. 多项目支持：每项目独立 SpiceDB（用户 2026-07-15 定 = 方案 B）

> 背景：Casdoor 是**跨项目共享**的公共 IAM；后续会接入多个项目（langchain4j-platform、EMR/his-platform…），每个项目复用同一套判权平台。实测发现 dev **SpiceDB 当前是共享实例**（并集 schema：knowledge 的 `document/space/…` + 另一项目的 `dept/encounter/patient`），SpiceDB 定义名是**全局**的，多项目共享一个实例会有定义名冲突 + 部署互相覆盖的隐患。

**决策：B —— 每个项目一个独立 SpiceDB 实例。** Casdoor 仍**单一共享**（一个 IAM 管所有项目的身份 + scope）。

**部署拓扑**：
```
Casdoor（1 个，共享） ── 身份 + scope（每项目一组 org/角色/permission）
每个项目 = 自己的 auth-platform-server + 自己的 SpiceDB + 自己的 <project>.zed
  langchain4j-platform → 其 authz 栈（server+SpiceDB），schema = knowledge.zed
  EMR/his-platform     → 其 authz 栈，schema = 其自己的 .zed（dept/encounter/patient）
消费方 SDK/edge：<项目>的服务 → <项目>的 auth-platform-server → <项目>的 SpiceDB
```

**B 的影响（多数是简化）**：
- **定义名不再跨项目冲突** → knowledge 的 `document/department/space/folder` 无需加项目前缀。
- **`schema/write <project>.zed` 整体替换是正确的**（每项目独占自己的 SpiceDB schema）→ 之前"共享 SpiceDB 上整体替换会删他项目定义"的隐患**在 B 下消失**；现有 `deploy/spicedb-smoke.sh` 的整体写在**隔离后**即正确。
- **对象 id 仍保留 `<tenantId>_<id>` 前缀**：用于该项目**内部**的租户维度（如 acme/beta 作为该产品的租户/org），部门树在其内。
- **Casdoor 侧**（仍共享）：多项目的 scope/permission 名建议按项目前缀或靠 org 隔离；edge 的 scope allowlist 按项目扩展。
- **dev 待办**：当前 :8543 是共享实例（含 EMR 定义）→ 需拆分，让 langchain4j-platform 用**独占 SpiceDB**（EMR 另起一个）。拆分前本轮用现有实例做了功能验证（additive 合并，未删他项目定义）。

**非目标**：不在本轮实现跨 SpiceDB 的联邦查询/聚合；不把 Casdoor 拆成多实例（IAM 保持共享）。

## 1. 目标 / 非目标

**目标**
- （多项目，§B 定稿）每项目独立 SpiceDB；Casdoor 共享；项目内 `<tenant>_` 前缀 + 部门树。
- （知识隔离，§A 定稿）文档按**部门层级**隔离：本部门+上级自动可读、其他部门申请、公共全可读、单篇可授权；**取代 D3**。
- 让**任意已开通租户（项目）**的用户都能经 Casdoor 登录前端（修复「只能登 acme」）；租户间登录互不串。
- edge 对多租户 token 做**安全的 `(owner,aud)` 绑定**校验（不只是 aud membership）。
- 保持 `owner=tenantId` 这一已验证安全合同**不变**（视 `owner` 为不可变安全字段，非可配置随意 claim）。
- （②，复用 Phase B-0）11 项 scope 在全平台被真正强制（5 角色×11 能力确定的 403/200）。
- 全程灰度可逆：前端 `apikey→dual→oidc`、edge `EDGE_CASDOOR off→on`、scope `off→shadow→enforce`。

**非目标**
- 不改内部 JWT claim 形状 / `X-Internal-Token` 传播协议 / 只 edge 验签（下游不重装 OIDC）。
- 不改 `KnowledgeResourceIds` 的 `<tenant>_<id>` 规则、不改 F3（`RemoteAuthzEngine`/`SpiceDbAuthzEngine` 严格校验，仅回归）。
- 不选 B/D（换 `owner` 语义，牵动全平台 tenant 来源与 ReBAC 前缀）；不选 C（单 app 跨 org 能力未经证明）。
- 本轮不做物理删 legacy 表/源码；auth-service 收敛（Phase C）沿用既有计划、单独审批。
- Stage 2 动态注册表**默认不在本轮实施范围**（除非用户明确要「免重启」）。

## 2. 已确认业务规则（逐条已核验，带证据）

1. **一租户=一 Casdoor org=一 app**：`deploy/casdoor-tenant-provision.sh:60-110`（org=`TENANT`、app=`rag-<tenant>`、clientId=`rag<tenant>client01`，并要求新 clientId 加入 `CASDOOR_AUDIENCES` 后**重启 edge**）。
2. **token claim**：`owner→tenantId`、`sub→userId`、`name→username`、`permissions[].name→scope`（edge 提取 ∩ 11 项 allowlist）。前端 `oidc.ts:83-99` 亦 `owner→tenant`。
3. **前端单 manager**：`config.ts:56-62`（issuer/client_id 构建期单值，默认 `cad5642b16071c3513d4`）、`oidc.ts:30-49`（`getUserManager()` 全局单例，固定 authority/client_id）、`LoginView.vue`（单一「用 Casdoor 登录」按钮，无租户选择）。**已核验。**
4. **edge aud 校验**：`CasdoorSecurityProperties.audiences` 是 `List<String>=List.of()` 静态；`CasdoorDecoderConfig.audienceValidator()` **只做 membership**（任一 aud ∈ allowed 即过），**不绑定 owner↔aud**；ONLY 模式仅断言 `tenant-claim=owner`。**已核验（本次读源码确认）。**
5. **edge 模式**：`Mode{DUAL,ONLY}`，DUAL 验签失败透 legacy、ONLY 401（`CasdoorTokenExchangeFilter`）。复用不破坏。
6. **admin 现状**：前端 admin（`api/admin.ts` → `/auth/admin/**`）打 **auth-service（legacy）**，不是 Casdoor；controller/前端只用 username、单租户假设（`AdminController.java:74-127`、`api/admin.ts:71-91`）。
7. **ReBAC 主体**：`RealKnowledgeAuthz.onDocumentCreated` 写 `user:<userId>`；legacy `USER_ID`→Casdoor `sub` 切换需**主体 crosswalk**，否则历史 owner/viewer 失效（`02-codebase-analysis §7`）。
8. **F1**：`SemanticCache` 仅按 tenant 分桶、pre-RAG 短路，authz shadow/enforce 时**必须关**（跨用户绕权），默认 false 但无跨服务校验。

## 3. 候选方案对比与评分（详见 comparison.md）

| 方案 | 租户边界 | tenant claim | 正确性 | 回滚 | 总分 | 结论 |
|---|---|---|---:|---:|---:|---|
| **A 一org一app+(owner,aud)绑定（+可选动态注册表）** | org | `owner`（不变） | 5 | 5 | **29** | **采用**：兼容/回滚最好，复用 owner→tenant 与 `<tenant>_` 前缀 |
| B 全局org+custom property | 用户属性 | 自定义 tenantId(待验证) | 3 | 2 | 19 | 否决：换 owner 来源，全平台双读迁移，claim 未验证 |
| C 单app多org路由 | org | `owner` | 2 | 4 | 23 | 否决：单 app 跨 org 登录能力**未由仓库/运行证明**，正确性不可给高分 |
| D 平台org+tenant group | group | groups 中唯一 tenant group | 3 | 2 | 19 | 否决：group 多值/并发/token 体积，一个错 membership 即跨租户 |

吸收：C 的登录别名/子域**体验**（不依赖其未验证能力）、B 的「`owner` 作不可变安全合同」思想、D 的 group 治理（GroupSync 仍只做 membership，不把 group 升成 tenant 真相源）、既有 rollout 的 manifest/journal/单写者 + Phase B-0。

## 4. 精确到文件的改动清单

> 图例：🆕 新增 ✏️ 改 ♻️ 仅回归。Stage 标注：**[S1]**=MVP 必做，**[S2]**=动态注册表（本轮可不做），**[B0]**=复用 Phase B-0。

### 4.1 langchain4j-platform 前端
- ✏️**[S1]** `capability-showcase-frontend/src/auth/oidc.ts`：`getUserManager()` 单例 → **按 tenant/clientId 的 manager map**；`startOidcLogin(tenant, returnTo)`；callback/silent/logout/bootstrap 按 pending/active tenant 恢复对应 manager；`userFromAccessToken` 保留 `owner→tenant` 并做 UX 级 `owner===expectedOrg` 断言（安全仍在 edge）。
- 🆕**[S1]** `capability-showcase-frontend/src/auth/tenantRegistry.ts`：tenant→`{clientId, organization, issuer?}` 解析。S1 用静态运行时配置（内置 map 或从 edge 读静态 JSON）；S2 改为 edge discovery 精确 lookup。
- ✏️**[S1]** `src/stores/auth.ts`：新增 selected/pending/active tenant 状态 + 切租户清理（先清 active User/api-key 再发起新登录）；callback/renew 传 tenant context。
- ✏️**[S1]** `src/modules/auth/LoginView.vue`：租户输入/选择 + lookup + loading/错误；legacy apikey 分支不变。
- ✏️**[S1]** `src/modules/auth/CallbackView.vue`、`src/main.ts`：回调/silent 恢复正确 tenant manager，失败重试保留 tenant（现状 `CallbackView.vue:19-32` 无租户重试是 bug）。
- ✏️**[S1]** `src/components/layout/AuthControl.vue`：显示 active tenant + 显式切换；oidc 下不恢复 api-key UI。
- ✏️**[S1]** `src/config.ts`：`CASDOOR_CLIENT_ID` 降为 legacy fallback；新增 registry 配置（S1 静态 URL/内置，S2 TTL）。
- ✏️ 对应 `auth/oidc.test.ts`、`stores/auth.oidc.test.ts`、`LoginView.test.ts`、`AuthControl.test.ts` **参数化两个租户**。
- ✏️**[后置]** `src/api/admin.ts`、`stores/adminUsers.ts`、`adminTenants.ts`、`modules/admin/UserEditor.vue`、router、types：admin key 含 tenant、写操作展示 plan/status（仅当把 admin 迁 Casdoor 时；见 §4.4）。

### 4.2 langchain4j-platform edge
- ✏️**[S1]** `edge-gateway/.../CasdoorDecoderConfig.java`：`audienceValidator(List)` → **`(owner,aud)` 绑定校验**（token 任一 aud 对应一个启用的 tenant 记录，且 JWT `owner`==该记录 organization）；issuer/timestamp/JWKS 不变；ONLY 的 owner 断言保留。
- ✏️**[S1]** `edge-gateway/.../CasdoorSecurityProperties.java`：`audiences` → 支持**多租户 `{tenant,org,clientId,enabled}` 列表**（S1 静态注入）；保留 DUAL/ONLY/issuer/claim/scope。
- ✏️**[S1]** `edge-gateway/.../CasdoorTokenExchangeFilter.java`：补 unknown/disabled tenant、owner-aud mismatch 的**原因分类指标**；内部 JWT 协议不改。
- ✏️**[S1]** `edge-gateway/src/main/resources/application.yml`：多租户 registry 配置 + 指标；静态 `CASDOOR_AUDIENCES` 保留为 break-glass fallback（与动态源**互斥**，不静默合并）。
- 🆕**[S2]** `CasdoorTenantRegistry` / `CasdoorTenantRegistryRefresher` / `CasdoorTenantDiscoveryController`（内存双索引 tenant↔record / clientId↔record，`AtomicReference` 热替换、LKG、拒重复）+ 测试。
- 🆕**[B0]** `EdgeScopeEnforcementFilter` + `edge.authz.route-scopes/enforce`（**复用 casdoor-authz-rollout Phase B-0**，本文件不重设计）。

### 4.3 auth-platform（开通 / IAM）
- ✏️**[S1]** `deploy/casdoor-tenant-provision.sh`：`set -uo`→严格错误分类 + `--fail-with-body`、current/desired diff、journal/补偿、登记 callback+silent+post-logout URI、token postcondition 断言（`owner/aud/sub/permissions`）；secret 改外部管理不再可预测派生。
- ✏️**[S1]** `deploy/casdoor-seed.sh`：`add-role ... || true` 改按 HTTP 码分类；`perm()` 若实测有 `update-permission` 则原位更新避免 delete+add 空窗（**待验证 Casdoor 写端点**）。
- 🆕**[S2]** 无密钥 `casdoor-rollout-manifest.json` + reconcile 主脚本 + registry snapshot publish + `casdoor-rollout-smoke.sh`（复用 rollout Phase A）。
- 🆕**[条件]** legacy export/backfill 脚本 + **SpiceDB subject crosswalk**（legacy `USER_ID`→Casdoor `sub`，同批 TOUCH new + DELETE old；仅当存在真实历史 tuple，见 §6）。
- ✏️**[S2/条件]** admin `CasdoorProperties.organization`（单值→organizations 集合）、`CasdoorClient`（按 org 分页 + 严格 HTTP 校验）、`GroupSyncService`/`ReconcileJob`/`CasdoorSyncController`（per-org checkpoint、分布式单写 lease、删除阈值**按 org**）——仅当启用多 org 组同步。

### 4.4 明确不改 / 仅回归
- ♻️ `platform-security` 内部 JWT claim/wire、`TenantContext.Tenant`；`KnowledgeResourceIds` 前缀；`RemoteAuthzEngine`/`SpiceDbAuthzEngine` F3。
- ♻️ 业务 Controller 的 scope 细节（由 Phase B-0 负责）。
- admin 迁 Casdoor（§4.1 后置 + §4.3 admin）为**较大且可延后**块：先保留 legacy `/auth/admin/**` 做真相源，登录先走 Casdoor；admin 迁移与 legacy 收敛按既有 rollout Phase C，本轮可不动。

## 5. 分阶段实施（每阶段：只做范围内 → 编译+测试 → 查 diff → 更新 IMPLEMENTATION_PROGRESS.md → 自检）

### Phase 1 — 身份模型与配置契约（不改运行行为）
- 定义 tenant registry 数据形状（`{tenant, organization, clientId, enabled}`，无密钥）；S1 落为前端 `tenantRegistry.ts` 静态 + edge 多租户配置。
- edge `CasdoorSecurityProperties` 扩多租户列表（默认空=行为不变）。
- **完成标准**：配置为空时 edge/前端行为与现状逐字一致（引入即安全）；单测覆盖配置解析与「空=旧行为」。

### Phase 2 — 核心逻辑（edge `(owner,aud)` + 前端多 manager）
- edge `audienceValidator`→`(owner,aud)` 绑定；`CasdoorTokenExchangeFilter` 补原因指标。
- 前端 `oidc.ts` manager map + `startOidcLogin(tenant)` + auth store tenant 状态 + callback/silent/logout 按 tenant 恢复。
- **完成标准**：edge 单测（合法 (owner,aud)、owner≠aud org 拒、未知/停用租户拒、DUAL/ONLY 语义不变）；前端单测（两租户各自登录/回调/续期/登出、切租户清理）。均绿。

### Phase 3 — 接口与开通脚本
- `LoginView` 租户选择 + lookup；`AuthControl` active tenant/切换；`config.ts` fallback 化。
- `casdoor-tenant-provision.sh`/`casdoor-seed.sh` 硬化（错误分类、journal、URI 全登记、token postcondition）。
- **完成标准**：脚本 `bash -n` + dry-run；对本地 Casdoor **探活确认写端点/字段**后跑通两个租户开通；配置契约预检（前端 clientId ∈ edge 多租户列表、11 scope 逐字一致）。

### Phase 4 — 测试（多租户 E2E + scope 矩阵）
- 多租户登录 E2E：≥2 租户各自能登、互不串、`(owner,aud)` 正确、无 token→401（ONLY）。
- （②）5 角色×11 能力 scope 矩阵（复用 Phase B-0 的 shadow→enforce）。
- 故障注入：owner-aud mismatch、停用租户、registry stale（S2）、DUAL 回退、F1 缓存互斥。
- **完成标准**：`smoke` 全绿不 skip；矩阵确定 403/200。部分需活栈（SpiceDB+Casdoor+edge enforce）——无法本地跑者显式标注。

### Phase 5 — 文档与终检 + （可选）Stage 2 动态注册表
- 更新部署/回滚/多租户开通文档 + F1 互斥。终检 git diff 只含批准范围。
- 若用户要「免重启」：再上 S2 registry/refresher/discovery + reconcile manifest。

## 6. 配置 / claim / 数据变更
- **claim**：不变（`owner/sub/name/permissions`）。**这是采用 A 的核心红利**。
- **edge 配置**：`edge.casdoor.audiences`（静态单值）→ `edge.casdoor.tenants:[{tenant,org,clientId,enabled}]`（S1 静态；S2 registry 驱动）；旧 `audiences` 保留为 break-glass，与新源互斥。
- **前端**：`VITE_CASDOOR_CLIENT_ID` 单值 → tenant→clientId 解析（S1 静态 map / 静态 JSON；S2 edge lookup）。Compose `docker-compose.yml:440-464` 前端 build-arg 单 clientId → 运行时读 registry。
- **数据迁移（条件）**：若存在用 legacy `USER_ID` 写入的 SpiceDB 历史 tuple，需 `USER_ID→Casdoor sub` crosswalk（同批 TOUCH+DELETE，固定 SpiceDB 版本集成测试验证原子性）。**dev 全新种子租户无历史 tuple 时此项可跳过，但须显式确认。**

## 7. 风险 / 监控 / 灰度 / 回滚（隐藏场景见 comparison §4）
- **owner-aud 错配**（隐藏#1）：必须成对校验，不能只 membership。← S1 Phase 2 核心。
- **切租户残留**（#4/#5/#6）：callback 丢 pending tenant、silent renew 持旧 manager、admin 按 username patch 错行 → 前端必须按 tenant 隔离 manager/事件、切租户先清理。
- **撤权 TTL**（#17）：admin 写成功 ≠ 即时撤权，旧 token TTL 内仍有效——文档写明。
- **F1**（#18）：authz shadow/enforce 环境强制 `CONVERSATION_SEMANTIC_CACHE_ENABLED=false`（已有 fixture/smoke 门禁）。
- **Casdoor `latest`**（#20）：写端点/claim 可能漂移——上线 pin image digest；reconcile 首步探活确认字段。
- **灰度**：前端 `apikey→dual→oidc`、edge `off→on`、scope `off→shadow→enforce`，每步观测窗 + 反向开关。
- **回滚**：S1 全可逆（前端改回 dual/apikey 重构建、edge 多租户配置清空回单 aud、`EDGE_CASDOOR_MODE=DUAL/off`）；已建 Casdoor 对象无需删。**注意**：回滚前端到 apikey **不能**单独恢复账密登录，须同时恢复 auth-service route/session（既有计划已指出）。

## 8. 最终验收清单
- [ ] ≥2 个租户各自能经 Casdoor 登录前端，租户间互不串（登录/回调/续期/登出/切换）。
- [ ] edge 对每个租户 token 校验 `(owner,aud)` 绑定：owner≠aud 所属 org → 拒；未知/停用租户 → 拒。
- [ ] `owner→tenantId`、`<tenant>_` 前缀、内部 JWT claim、F3 逻辑均未变（回归绿）。
- [ ] DUAL/ONLY 语义不变；无 token ONLY→401；DUAL 下 Casdoor/session/api-key 并存正确。
- [ ] 配置为空时行为与接入前逐字一致（引入即安全）。
- [ ]（②）5 角色×11 能力矩阵确定 403/200（复用 Phase B-0）。
- [ ] F1：authz 非 disabled 环境语义缓存实际关闭。
- [ ] 新租户开通脚本幂等、错误分类、token postcondition 断言通过；URI 全登记。
- [ ]（条件）历史 SpiceDB 主体已 crosswalk 或确认无历史 tuple。
- [ ] 灰度顺序与回滚已演练；Casdoor image 已 pin。

## 9. 交付实施 Agent 的执行顺序
先读本文件 + `02-codebase-analysis.md` + `comparison.md` + `test-plan.md` + 既有 `casdoor-authz-rollout` 计划。**先做 Stage 1 MVP（Phase 1→4）直接修复多租户登录**；②scope 强制复用 Phase B-0，可并行或后置；Stage 2 动态注册表与 admin 迁 Casdoor 仅在用户明确要求时做。遇 Casdoor 写端点/字段、SpiceDB 批原子性、historical subject 数据缺失，标「待验证」并阻断对应步骤，不臆测。
