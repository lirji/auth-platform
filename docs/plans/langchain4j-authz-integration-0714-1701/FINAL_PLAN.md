# langchain4j-platform 接入 auth-platform 最终实施方案

> 文档性质：架构与实施计划。本文中的“现有”均已从两仓库代码核实；“拟新增”是交给实施 Agent 的明确设计，不表示当前仓库已有该类/表/接口。

## 0. 跨模型复核结论与交付切片修正（Claude，2026-07-14）

> 本节由 Claude 在 Codex 产出后对照两仓库真实代码复核追加。§1–§13 是 Codex 设计的“生产终态”，作为北极星保留；本节修正“落地顺序”，避免把可立即动手的 ①ReBAC 与被阻塞的 ②③ 绑成一次性大爆炸。

### 0.1 事实核验（通过）

对照真实仓库逐条验证，Codex 断言的“现有”几乎全部属实、无臆造：已有未提交 ReBAC 草稿（`knowledge-service/.../authz/{KnowledgeAuthz,RealKnowledgeAuthz,NoopKnowledgeAuthz,KnowledgeAuthzConfig}.java`，且 `DocumentService/KnowledgeQueryService/pom.xml` 已改）；`InternalToken.mint(Tenant)/verify` 与 `ApiKeyToInternalTokenFilter` 调 mint 属实；AP server **确无 SecurityConfig、`/v1/**` 裸奔**；`CasdoorClient` **只有读方法、无写 API**；`DocumentInfo` 无 owner 字段；4 个 query 调用方均在；`KnowledgeQueryRequest` 为 4 参。→ 设计事实基础可信。

### 0.2 交付切片：三个可独立交付的里程碑（关键修正）

**修正原因**：②（RBAC 组迁移）③（Casdoor SSO）被 §10 阶段0 **硬阻塞**——Casdoor 的 tenant/scopes claim 契约未知、`CasdoorClient` 无写 API 需实测、auth DB 数据质量未知；这些靠写代码解决不了，需真实 Casdoor 环境。而 ①ReBAC **完全不依赖 Casdoor**（只用现成的 `TenantContext.userId()`），已有草稿已跑通。故拆分：

| 里程碑 | 内容 | 阻塞前置 | 现在可否动手 |
|---|---|---|---|
| **A：①ReBAC 最小闭环** | 基于已有草稿生产化：SubjectResolver + `@CheckAccess` + SpaceInfo/default space + resource-id codec + AP server 最小 service-credential + **shadow 模式** | 无 | ✅ 可立即做 |
| **B：③Casdoor SSO** | edge Casdoor token 校验→复用现有 `InternalToken.mint`；前端 OIDC | 阶段0 固化 claim 契约 | ⛔ 阻塞 |
| **C：②RBAC→SpiceDB group 迁移** | AP admin dry-run/reconcile + Casdoor group import | 阶段0 + Casdoor 写 API 实测 | ⛔ 阻塞 |

→ **建议本轮只批里程碑 A**；B/C 待阶段0 的书面结论出来再单独规划。§10 的阶段0 提前为“启动 B/C 的前置调研”，与 A 并行推进、互不阻塞。

### 0.3 里程碑 A 内的四点技术修正

1. **删掉草稿里的单进程 ZedToken 水位**：`RealKnowledgeAuthz` 的 `AtomicReference<String> watermark` 在多副本下不可靠（副本 A 写、副本 B 读不到）。首期最简单且正确的做法不是引入分布式水位缓存，而是**读过滤 `filterReadable` 直接用 `FULLY_CONSISTENT`**（与 Codex“首期 fully-consistent”一致），牺牲少量延迟换正确，有性能问题再优化。
2. **AP server `/v1/**` 鉴权是里程碑 A 的前置，不能推到 §2**：knowledge 一旦经 SDK 写关系，`/v1/relationships` 就是可任意提权的裸端点。里程碑 A 至少加一个**共享 service credential（Bearer）+ 网络隔离**；Codex 主张的“按 check/write 能力分级 + resource/relation allowlist”推迟到里程碑 C（enforce 前）。
3. **public/shared 库保持现状、不在里程碑 A 动**：现有 `DocumentController` 的 `visibility=public|shared` 走 `__public__` 租户 + `public-ingest` scope 的 bypass。里程碑 A 的 ReBAC **只管租户内文档**；`public_viewer@user:*` 的 ReBAC 化与 shadow 双算单列（里程碑 A 后半或独立小里程碑），避免一上来就动 public 逻辑。
4. **shadow 期用最小实现，暂不引入重型健壮性设施**：`DocumentLifecycleReconciler`、`KnowledgeAuthzReconciler`、`AuthzProjectionState` 五态状态机、Redis pending sorted set、分布式 lease 是 **enforce 生产态**才需要的。shadow 期读路径只“双算 + 记差异指标、不真正拦截”，写路径失败记日志/告警即可——把里程碑 A 的改动面从 ~30 文件压到 ~10 文件。这些设施在“shadow→enforce 切换前”再引入。

### 0.4 存疑/待澄清（需业务确认，不臆造）

- **role→space relation 映射表**（§7.3）：`admin/editor/viewer` 直观，但 `analyst/approver/custom` 是否授知识库权限须业务拍板——里程碑 C 才用到，A 不涉及。
- **历史文档 owner**：`DocumentInfo` 无 owner 字段，历史归属不可从代码恢复。里程碑 A 采纳 Codex 结论——历史文档只靠 `parent_space`（default space）授权，**不伪造 owner**；仅新建文档写真实 `owner@user:<userId>`。
- **default space 授权语义**：所有租户内旧文档挂 `<tid>_default`，则“同租户任意登录用户”默认可 view 该租户全部文档（草稿现状即如此）。这是否符合预期需确认；如需更严，default space 的 viewer 应绑到迁移后的 role group（依赖里程碑 C）。

## 1. 背景

LP 当前已有两套外部认证入口：`SessionBearerAuthFilter` 验 auth-service 本地会话 JWT，`ApiKeyToInternalTokenFilter` 验 `X-Api-Key`；两者最终都调用 `InternalToken.mint`，下游通过 `InternalTokenAuthFilter` 恢复 `TenantContext`。这个内部传播接缝成熟且被多服务复用，应保持。

knowledge-service 当前以 tenant metadata 隔离文档，但 `DocumentController.list/get/delete` 没有逐文档 ReBAC。工作树中已有未提交的 AP SDK 草稿：`RealKnowledgeAuthz` 写 owner/parent_space、查询后 `checkBulk`；它证明技术兼容，但还缺 SubjectResolver、`@CheckAccess`、space 实体、故障状态机、部署配置和 SSO/RBAC 整合。

AP 已有 grpc-free SDK、`knowledge.zed` 和 Casdoor→SpiceDB `GroupSyncService`。现有能力足够作为基础，但 group 短名碰撞、嵌套组 direct tuple 对账、多副本并发、server 写端鉴权仍需补齐。

## 2. 目标与非目标

### 2.1 目标

1. Casdoor 是唯一外部用户身份源；`userId = Casdoor access token sub`。
2. edge 校验 Casdoor token 后仍签发既有内部 JWT；内部 claims/头名/TenantContext 不变。
3. space/document 由 SpiceDB 执行细粒度权限，资源 ID 为 `<tenantId>_<spaceId|docId>`。
4. 单资源操作使用 AP SDK `@CheckAccess`；关系生命周期使用 `writeRelationships/deleteRelationships`；query/list 使用 checkBulk/lookup。
5. 把 auth-service 的既有角色、组、成员与租户角色经可审计迁移映射为 tenant-scoped SpiceDB groups，并绑定到 space relation。
6. 支持幂等迁移、并发保护、PENDING 补偿、shadow/enforce 灰度和可验证回滚。

### 2.2 非目标

- 不改内部 JWT 形状：继续 `sub=tenantId`、`uid=userId`、`scopes`。
- 不让 knowledge 直连 SpiceDB gRPC，不替换 `AuthzEngine` port。
- 不一次性把其他所有业务资源做 ReBAC。
- 不在本期完整产品化 folder/comment/public-link 管理。
- 不通过分布式事务协调向量、Redis、ES、图谱和 SpiceDB；采用状态机与补偿。
- 不长期保留本地密码/session/API key 双模入口。

## 3. 已确认的业务规则

1. space=知识库，document=文档；document 权限继承 parent_space。
2. 旧文档归属每租户 `default` space；旧 API 不传 spaceId 时也使用 default。
3. document key 精确为 `<tenantId>_<docId>`；space key 为 `<tenantId>_<spaceId>`；只由服务端构造。
4. `SubjectResolver.currentSubject()` 返回 `SubjectRef.user(TenantContext.current().userId())`；anonymous 在 enforce 模式拒绝。
5. 上传要求 space `edit`；详情/list/query 要求 document `view`；删除/替换要求 document `edit`；space policy 管理要求 `manage`。
6. 未授权详情建议对外返回 404，transport failure 返回 503；审计保留真实 deny/error。
7. relation 与 scope 不等价。role→space relation 必须显式配置，未知角色不得自动映射。
8. 角色/组 SpiceDB ID 必须 tenant-scoped；跨租户同名 group 不合并。
9. Casdoor sub、内部 JWT uid、TenantContext userId、SpiceDB user id 四者完全一致。
10. 公共库目标态用 schema 既有 `public_viewer@user:*`；shadow 期间与当前 shared bypass 双算。
11. auth-service 的本地密码、refresh session 和 RBAC 写在迁移后退出权威路径；最多只读保留一个发布周期。
12. 现有 `DocumentInfo` 没有上传者字段，历史 owner 不可从代码现状可靠恢复；无可审计日志/导入 manifest 时保持 unknown，不把迁移执行者或管理员伪造成 owner。
13. active tenant 必须来自已验签且能证明 Casdoor organization/membership 的 claim 或受控 token exchange；edge 不接受客户端 header/query 覆盖 tenantId。

## 4. 当前代码与调用链分析

### 4.1 授权

```text
@CheckAccess public method
 -> CheckAccessAspect.around
 -> SubjectResolver
 -> method parameter resourceId
 -> RemoteAuthzEngine /v1/check
 -> AuthzController
 -> SpiceDbAuthzEngine
 -> SpiceDB
```

现有限制：AOP 只识别参数名、默认 minimize-latency；LP 尚无 SubjectResolver/403 advice；AP server `/v1/**` 未鉴权；SDK 无 timeout/service credential。

### 4.2 文档

```text
upload -> scope check -> vector/mirror/ES/graph -> registry.put
       -> [草稿] write owner + parent_space

query -> 多源召回 -> fusion -> [草稿] checkBulk(view) -> rerank
list/get -> registry 直出
delete -> 业务数据/registry 删除 -> [草稿] 删除 SpiceDB relationships
```

现有限制：无 space 实体；双写无状态；delete 顺序不安全；同名重传可能累积 owner；进程内 ZedToken 不适合多副本/外部组变更；`docId==null` 在草稿中直接放行。

### 4.3 身份

```text
local session Bearer --SessionBearerAuthFilter--\
                                             -> InternalToken -> TenantContext
X-Api-Key --------ApiKeyToInternalTokenFilter-/
```

目标只替换左侧凭证验证：

```text
Casdoor access token -> Casdoor token validation/claim mapping
                     -> InternalToken.mint(existing Tenant)
                     -> existing downstream seam
```

### 4.4 RBAC

auth-service 真实权威数据为 `USERS/USER_ROLE/TENANT_ROLE/AUTH_GROUP/GROUP_ROLE/USER_GROUP`，有效 scope 是个人直配 + 个人角色 + 租户角色 + 组角色。Group 是全局定义，成员以 username 存储；迁移到 Casdoor/SpiceDB 前必须 crosswalk 到 Casdoor sub 并按 member tenant 拆组。

## 5. 候选方案与评分

评分 1–5 且越高越有利；加权：正确性 25%、改动风险 20%、可维护性 15%，其余各 10%。

| 方案 | 正确性 | 风险 | 复杂度 | 维护性 | 扩展性 | 测试 | 回滚 | 加权 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 业务同步桥接 | 3 | 4 | 4 | 2 | 3 | 4 | 5 | 3.45 |
| B IAM 中央拉取对账 | 4 | 4 | 3 | 4 | 4 | 3 | 4 | **3.80** |
| C Outbox 事件投影 | 5 | 2 | 1 | 4 | 5 | 1 | 3 | 3.20 |
| D 一次性权威切换 | 5 | 2 | 2 | 5 | 5 | 2 | 1 | 3.45 |

详细论证见 `03-solution-a.md` 至 `06-solution-d.md` 和 `comparison.md`。

## 6. 最终方案与选择原因

采用“B 的中央 dry-run/reconcile + D 的单一权威目标 + A 的资源即时写”。

### 6.1 目标架构

```text
Browser / service identity
          |
          | Casdoor access token
          v
edge-gateway (verify iss/aud/exp, sub + tenant + scopes)
          |
          | existing X-Internal-Token
          v
knowledge-service -> @CheckAccess/checkBulk -> AP server -> SpiceDB
          |                 |
          +-- resource relationship write + independent authz record/reconcile

Casdoor groups ----\
legacy RBAC snapshot -> AP admin desired-state reconciler -> SpiceDB group/space tuples
space snapshot -----/          (legacy source removed after cutover)
```

### 6.2 选择原因

- 与 AP 现有 GroupSync 差量模型最贴合，可 dry-run、分租户应用、先加后删、阈值熔断。
- 目标态只有 Casdoor（身份/成员）和 SpiceDB（资源授权）两个权威，避免 A 永久双写。
- 新 document/space 仍同步 TOUCH，创建者无需等待下一轮 reconcile。
- 不在 pilot 引入 C 的 Kafka/DLQ/重放全套成本；现有多存储写本来也不能靠消息自动获得事务。
- 相比 D big-bang，多一个可审计 reconcile 层和只读过渡期，显著降低迁移风险。

### 6.3 已知弱点

- Casdoor group 变更到 SpiceDB 有 webhook/reconcile 延迟。
- fully-consistent 判权在首期有延迟/负载成本。
- 多仓库、多服务、多客户端发布编排复杂。
- Redis projection state 不是向量/ES/图谱/SpiceDB 的 ACID 事务。
- tenant/scopes 的真实 Casdoor claim 尚待 M0 真实 token 固化；这是阻塞项，不允许凭空指定字段。
- auth-service 只读窗口如果不按期关闭，会重新成为双权威源。
- AP 目前没有按调用方/操作类型隔离的服务权限；只加一个共享万能 token 会把关系写入面扩大，必须在 enforce 前补能力分级。

## 7. 资源、组和权限映射

### 7.1 资源 ID

- `organization:<tenantId>`
- `space:<tenantId>_<spaceId>`
- `document:<tenantId>_<docId>`

拟新增 `KnowledgeResourceIds` 是唯一编码入口，禁止 controller 接受完整 tenant-prefixed ID。若 ID 中允许 `_`，无需反向通用 split；application service 只按已知 `tenantId + "_"` 前缀验证并截取余部。

### 7.2 关系

space 创建：

- `space:T_S#owner@user:SUB`
- `space:T_S#parent_org@organization:T`

document 创建：

- `document:T_D#owner@user:SUB`
- `document:T_D#parent_space@space:T_S`

public space/document：按业务规则写 `public_viewer@user:*`，而不是永久在代码里 bypass。

每个 tenant 建立 `default` space。历史 default space 不补造 owner，先通过迁移后的 role groups 绑定 viewer/editor/admin；新建 space 才把真实创建者写为 owner。历史文档只有存在可审计 owner manifest 时才写 document owner，否则依赖 parent_space 授权。

### 7.3 RBAC group

拟定规范：

- 原组：`group:lc4j_<tenant>_group_<groupName>`
- 角色组：`group:lc4j_<tenant>_role_<roleName>`
- 租户全员组：`group:lc4j_<tenant>_all`（由该 tenant 的 Casdoor 用户成员生成）
- 原组成员：`...#member@user:<CasdoorSub>`
- 组继承角色：`roleGroup#member@originalGroup#member`
- 个人角色：把用户导入 Casdoor 的 tenant-scoped role-assignment group，由 GroupSync 投影为 roleGroup direct user member。
- 租户基础角色：写 `roleGroup#member@tenantAllGroup#member`，避免为每个用户复制边；组角色写 `roleGroup#member@originalGroup#member`。
- space 只绑定 roleGroup：`space:T_S#<relation>@group:...role...#member`。

迁移时先以 manifest 幂等创建/匹配 Casdoor 原组、role-assignment group 及成员，并记录 Casdoor object ID；切换后 Casdoor 只负责用户与 direct group membership，AP auth-console/space policy API 负责 role group 的 nested edge 和 space relation。auth-service snapshot 只用于首轮核对，观察期结束后不再参与 desired state。这样后续成员变更不会重新引入 legacy 权威。

初始 role mapping（**待业务确认后才可 apply**）：

| auth-service role | space relation | 备注 |
|---|---|---|
| admin | admin | 可 manage/edit/view |
| editor | editor | 可 edit/view |
| viewer | viewer | 只读 |
| analyst | viewer | 其 analytics scope 不自动提升知识编辑权 |
| approver | viewer | approve scope 不自动提升知识编辑权 |
| custom | 无默认 | 必须显式配置，否则报告并跳过授权 |

### 7.4 粗粒度 scopes 迁移

ReBAC role mapping 不替代现有 scope 门禁。迁移先以 auth-service `EffectivePermissionResolver` 的结果生成逐 tenant/user 基线，再把 `ROLE_SCOPE`、个人 direct scopes、tenant role、group role 的有效结果映射到 Casdoor roles/permissions 或可签名 claim。edge 只接受允许词表内的已签名 scope，并把它原样写入现有内部 JWT `scopes`。同一用户切换前后的有效 scope 集必须相等（经审批的废弃项除外）；若 Casdoor 不能稳定签发该 claim，阶段 0 阻塞，不用在线回查 auth-service 掩盖缺口。

## 8. 精确修改清单

本节路径均为仓库根目录相对路径；AP 指 `auth-platform`，LP 指 `langchain4j-platform`。类名/现有方法已从代码核实；标注“拟新增”的文件和方法是实施合同。

### 8.1 AP

#### SDK/server（修改现有）

- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/AuthzClientProperties.java` / `AuthzClientProperties`
  - 增加 connect/read timeout、按服务/能力签发的 credential 和 consistency 默认策略属性。
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/RemoteAuthzEngine.java` / `RemoteAuthzEngine.check/checkBulk/lookupResources/lookupSubjects/writeRelationships/deleteRelationships/readRelationships`
  - RestClient 注入 service credential/timeout；保留现有 REST payload；增加错误分类。
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/AuthzSdkAutoConfiguration.java` / `authzEngine/checkAccessAspect`
  - 用完整 `AuthzClientProperties` 构造 transport/AOP，使 credential、timeout、consistency 真正生效；缺 credential 的 production profile 启动失败。
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/CheckAccessAspect.java` / `CheckAccessAspect.around/resolveParam`
  - 拒绝 null/blank resource ID；允许配置 consistency；保持参数名契约并补测试。
- `auth-platform-server/pom.xml`、`auth-platform-server/src/main/resources/application.yml`
  - 引入 server 安全依赖/属性；health 放行，`/v1/**` 仅 service credential/mTLS 网络身份可用；credential 至少区分 check/read 与 relationship-write，并限制可写 resource type/relation。
- `auth-platform-server/src/main/java/com/lrj/authz/server/AuthzController.java` / `check/checkBulk/lookupResources/lookupSubjects/write/delete/schema/expand/readRelationships`
  - 业务方法契约不变；补入参校验、批量上限和安全测试。

#### Admin/Casdoor（修改现有）

- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorProperties.java` / `CasdoorProperties`
  - 增加多 tenant/organization mapping、group namespace、分页、dry-run/delete-enabled/max-change、锁配置。
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorClient.java` / `CasdoorClient.groupMembers/groupNames/shortName`
  - 保留完整 owner/path 信息并分页；输出“本轮完整成功”状态，不能部分失败当空集。
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/GroupSyncService.java` / `GroupSyncService.sync`
  - 拆为 fetch desired / read direct current / diff / apply；current 改用 `readRelationships` 直接元组；支持 user 与 group#member；去除仅靠 JVM synchronized 的并发假设。
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/ReconcileJob.java` / `ReconcileJob.reconcile`
  - 使用分布式锁/leader；记录 runId、source version、结果指标。
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorSyncController.java` / `CasdoorSyncController.sync/webhook`
  - 增加 dry-run；webhook 升级 timestamp+nonce+HMAC，触发异步/合并 reconcile，避免请求线程长跑。
- `auth-platform-admin/src/main/resources/application.yml`
  - 增加完整 Casdoor/reconcile/RBAC snapshot/role mapping 示例，默认 delete=false。

#### Admin（拟新增）

以下拟新增 Java 文件基目录为 `auth-platform-admin/src/main/java/com/lrj/authz/admin/`：

- `casdoor/SpiceDbGroupIdMapper.java` / `originalGroupId/roleGroupId/tenantAllGroupId`：tenant-scoped canonical ID。
- `casdoor/DirectRelationshipReconciler.java` / `plan/apply`：直接 tuple 差量与批处理。
- `integration/langchain/LangchainRbacClient.java` / `readSnapshot`：迁移期只读 snapshot client。
- `integration/langchain/LangchainRbacDesiredStateMapper.java` / `map`：四层 RBAC→role/original groups。
- `integration/langchain/CasdoorGroupImportService.java` / `dryRun/apply/resume`：按 manifest 幂等创建/匹配 tenant-scoped Casdoor groups 与 direct membership；具体 Casdoor mutation endpoint/SDK 方法在阶段 0 实测后冻结，当前仓库 `CasdoorClient` 只有读取能力，不能假定已有写 API。
- `integration/langchain/LangchainSpaceClient.java` / `readSpaces`：读取 space snapshot。
- `integration/langchain/LangchainAccessReconcileService.java` / `dryRun/apply`：dry-run/apply/checkpoint/阈值熔断。
- 对应 `src/test/java` 单元、MockWebServer 和真实 SpiceDB required integration tests。

#### Schema/部署

- `auth-platform-core/src/main/resources/schemas/knowledge.zed` 原则上不改 definition；本方案现有 relation 足够时不执行 schema write。AP 同一 SpiceDB 还存在 `auth-platform-core/src/main/resources/schemas/his.zed` 的合并约束，生产禁止只写 `knowledge.zed` 覆盖共享 schema；确需变更时先生成并验证完整合并 schema，再做 compatibility diff 与向后兼容发布。
- `deploy/spicedb-smoke.sh` 加 tenant-scoped role/original nested group、撤权断言。
- `deploy/server-smoke.sh` 携 service credential。
- `deploy/docker-compose.yml`/`dev.sh` 补 server/admin 应用部署与安全配置（当前 compose 只有基建）。

### 8.2 LP edge/platform-security

#### 拟新增

- `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorSecurityProperties.java`（拟新增）：issuer、JWKS、audience、tenant/groups/scopes claim mapping。
- `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java` / `filter`（拟新增）：从经验证 Jwt 构造 `TenantContext.Tenant` 并调用现有 `InternalToken.mint`，剥离 Authorization。
- `edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorSecurityConfig.java` / `SecurityWebFilterChain` bean（拟新增）：WebFlux resource server/JWK validation、401/403。
- `edge-gateway/src/test/java/com/lrj/platform/edge/CasdoorTokenExchangeFilterTest.java`（拟新增）与 JWKS integration test。

#### 修改/退役

- `edge-gateway/pom.xml`：增加 reactive OAuth2 resource server/Jose。
- `edge-gateway/src/main/java/com/lrj/platform/edge/SessionBearerAuthFilter.java` / `filter`、`edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java` / `filter`：只在明确 `legacy` profile 注册；Casdoor-only 不注册，观察期后删除。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeOpenPaths.java` / `isOpen`：移除本地 login/register/refresh/logout/public-config；保留 actuator/well-known/已验签第三方 webhook。
- `edge-gateway/src/main/resources/application.yml`：增加 Casdoor 配置；移除生产 apiKeys/session secret；CORS 仍允许 Authorization。
- `platform-security/src/main/java/com/lrj/platform/security/InternalTokenAuthFilter.java` / `resolve/doFilterInternal`：增加生产“无有效内部 token 即 401”的配置；`allow-api-key-fallback=false`。
- `platform-security/src/main/java/com/lrj/platform/security/InternalSecurityProperties.java`：保留内部 JWT；session/api-key 属性标 deprecated 后按退役阶段删除。
- `platform-security/src/main/java/com/lrj/platform/security/TenantContext.java` 与 `InternalToken.java` / `mint/verify` 的 contract 不改，仅补 sub 一致性测试。

### 8.3 LP auth-service

- `auth-service/src/main/java/com/lrj/platform/auth/AuthController.java` / `login/register/refresh/logout/publicConfig`：Casdoor-only profile 不暴露；`me` 可保留为内部身份诊断。
- `auth-service/src/main/java/com/lrj/platform/auth/AuthService.java`、`SessionTokenIssuer.java` 及 Password/RefreshSession 相关类：迁移期保留但不在主 profile 装配，最终删除。
- `auth-service/src/main/java/com/lrj/platform/auth/AdminController.java`、`AdminService.java`：迁移冻结后写端 disabled；新增**拟定**只读 `rbacSnapshot` endpoint/service method，必须 service-auth 且分页/versioned，不暴露 password hash/session。
- `auth-service/src/main/java/com/lrj/platform/auth/JdbcUserAccountStore.java`：不直接改 USER_ID 当作普通管理写；使用一次性受控 migration crosswalk。
- `auth-service/src/main/resources/application.yml`：新增 `app.auth.mode=legacy|migration-readonly|disabled`；disabled 为目标。

拟新增 snapshot DTO 字段只含：snapshotVersion、tenant、username、legacyUserId、roles、groups、tenantRoles、groupRoles；Casdoor sub 由独立 crosswalk 合并。接口的确切路径可为 `/auth/internal/rbac-snapshot`，但这是新合同，实施前做安全评审。

现有 LP RBAC 管理 UI 在 migration-readonly 期只读展示并标注权威源已迁移，Casdoor-only 后移除写入口：身份/组成员在 Casdoor 管理，space relation 在 AP auth-console 或新 space policy API 管理，禁止继续写旧表形成第三权威源。

### 8.4 LP knowledge-service

以下 Java 路径基目录为 `knowledge-service/src/main/java/com/lrj/platform/knowledge/`；每项给出相对该目录的精确文件。

#### 拟新增领域/适配层

- `lifecycle/SpaceInfo.java`：spaceId、tenantId、name、ownerSubjectId、version、createdAt/updatedAt；内部投影状态不进入 space API DTO。
- `lifecycle/SpaceRegistry.java`、`InMemorySpaceRegistry.java`、`RedisSpaceRegistry.java` / `put/get/list/delete/compareAndSet`，Redis hash `rag:spaces:<tenantId>`。
- `controller/SpaceController.java` / `create/list/get/delete/updatePolicy`：最小入口；旧 document API 不依赖 UI 即可使用 default。
- `authz/KnowledgeResourceIds.java` / `organization/space/document`：唯一 ID codec。
- `authz/TenantContextSubjectResolver.java` / `currentSubject`（或在 Config 中 lambda bean）。
- `authz/KnowledgeAccessApplicationService.java`（拟新增）/ `uploadToSpace(String spaceResourceId, ...)`、`getDocument(String documentResourceId, ...)`、`deleteDocument(String documentResourceId, ...)`、`replaceDocument(String documentResourceId, ...)`、`updateSpacePolicy(String spaceResourceId, ...)`：被 controller 调用的独立 Spring bean；完整 resource ID 由 `TenantContext.tenantId + KnowledgeResourceIds` 生成后传入，客户端只提供 raw id；各 public method 使用 `@CheckAccess` 并封装实际操作，禁止仅做一次 guard 后在 bean 外执行敏感读取，也避免 self-invocation。
  - 注解合同固定为：upload `space/edit/resourceIdParam="spaceResourceId"`；get `document/view/resourceIdParam="documentResourceId"`；delete/replace `document/edit/resourceIdParam="documentResourceId"`；policy `space/manage/resourceIdParam="spaceResourceId"`。
- `authz/AuthzProjectionState.java`：PENDING/PENDING_MIGRATION/ACTIVE/DELETE_PENDING/ERROR。
- `authz/DocumentAuthorizationRecord.java`：tenantId、docId、spaceId、nullable ownerSubjectId、state、attempts、lastError、version；授权内部状态不混入公共文档 DTO。
- `authz/SpaceAuthorizationRecord.java`：tenantId、spaceId、nullable ownerSubjectId、state、attempts、lastError、version；default 历史 space 的 owner 可为空。
- `authz/KnowledgeAuthorizationRegistry.java`、`InMemoryKnowledgeAuthorizationRegistry.java`、`RedisKnowledgeAuthorizationRegistry.java` / `put/get/compareAndSet/scanPending/delete`：space/document 授权状态 CAS 更新、待处理扫描和按资源删除；Redis hash `rag:authz:spaces:<tenantId>`、`rag:authz:docs:<tenantId>`，pending sorted set `rag:authz:pending:<tenantId>`；hash+index 更新用 Lua/事务保证原子，按 expected version 拒绝旧任务覆盖新状态。
- `authz/KnowledgeAuthzReconciler.java` / `reconcile/reconcileOne`：扫描非 ACTIVE，幂等 TOUCH/DELETE，以带 TTL 的分布式 lease + record version 防多实例重复/乱序；进程崩溃后 lease 自动释放。
- `lifecycle/DocumentLifecycleReconciler.java`（拟新增）/ `reconcileUpload/reconcileDelete`：协调 registry、vector、mirror、ES、graph 的补偿/续作；它调用 `KnowledgeAuthzReconciler` 而不是让授权组件直接操作业务 sink。每步以 doc version 幂等，旧版本 job 不得删除新版本数据。
- `controller/AuthzExceptionHandler.java` / exception handler methods：deny 403/404、transport 503。

#### 修改现有

- `knowledge-service/pom.xml`：保留用户未提交 SDK 依赖，改由属性/dependencyManagement 管版本；确保 `-parameters`；增加测试依赖。
- `authz/KnowledgeAuthz.java` / 现有 `enabled/onDocumentCreated/onDocumentDeleted/grantDocumentViewer/revokeDocumentViewer/filterReadable` 及拟新增 space/create/delete/bind methods：增加 document projection/read consistency port；把 enabled boolean 演进为 mode。
- `authz/RealKnowledgeAuthz.java` / `enabled/onDocumentCreated/onDocumentDeleted/grantDocumentViewer/revokeDocumentViewer/filterReadable/key`：移除进程 `AtomicReference` 作为全局保证；首期 check 用 fully consistent；所有 key 走 codec；批量限制；关系写返回 token 供同请求验证。
- `authz/NoopKnowledgeAuthz.java`：只允许 disabled/dev；production enforce 缺 bean 启动失败。
- `authz/KnowledgeAuthzConfig.java` / Spring bean methods：注册 SubjectResolver、mode、reconciler、fail-fast health；配置 `authz.client.*`。
- `lifecycle/DocumentInfo.java` / `DocumentInfo`：只在末尾增加公开领域字段 `spaceId`；compact canonical constructor 将 null/blank 规范化为 default，保留现有参数形状的 convenience constructor；旧 JSON 缺字段解释为 default，不把 owner/内部投影错误暴露给文档 API。
- `lifecycle/DocumentRegistry.java`、`InMemoryDocumentRegistry.java`、`RedisDocumentRegistry.java`：支持按 space 列表和条件版本更新；授权投影扫描由独立 `KnowledgeAuthorizationRegistry` 承担。
- `lifecycle/DocumentService.java` / `DocumentService.upload`
  - 先解析/校验 space；经 application service AOP 检查 space edit；写 PENDING 授权记录；完成 sinks；TOUCH relations；转 ACTIVE。
  - 同名版本保持原 owner，不让 editor 重传夺权；default space 继续现有 `SHA-256(tenantId + ":" + displayName)`，仅非 default space 使用 `SHA-256(tenantId + ":" + spaceId + ":" + displayName)`，避免历史 ID/版本链断裂。
- `lifecycle/DocumentService.java` / `DocumentService.delete`
  - AOP 检查 edit；先把状态置 DELETE_PENDING 并撤关系，再删 sinks/registry；业务 sink 半失败由 `DocumentLifecycleReconciler` 收敛，授权 tuple 半失败由 `KnowledgeAuthzReconciler` 收敛。
- `controller/DocumentController.java` / `DocumentController.uploadFile/uploadJson/list/get/delete`
  - 可选 spaceId，默认 default；list/get 走 authorized application service；保留 scope 作为粗门禁但不能替代 ReBAC。
- `KnowledgeQueryService.java` / `KnowledgeQueryService.query/filterReadable`
  - 接受可选 space；过滤 docId null；checkBulk 分批；shadow 对比；授权记录非 ACTIVE 不返回；记录 topK underfill。
- `controller/KnowledgeQueryController.java` / `KnowledgeQueryController.query`
  - 传 spaceId；错误契约稳定。
- `knowledge-service/src/main/resources/application.yml`
  - `app.rag.authz.mode=disabled|shadow|enforce`、consistency、batch-size、reconcile、AP URL/credential；生产关闭 api-key fallback。

### 8.5 Protocol 与调用方

- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeQueryRequest.java` / record canonical constructor：拟追加可选 `spaceId`，并保留旧四参构造。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeHit.java` / record canonical constructor：拟追加可选 `spaceId`，并保留旧九参构造。
- 更新真实调用点与测试：`conversation-service/src/main/java/com/lrj/platform/conversation/RagPromptAugmenter.java`、`agent-service/src/main/java/com/lrj/platform/agent/actions/RagSearchAction.java`、`eval-service/src/main/java/com/lrj/platform/eval/retrieval/HttpRetrievalClient.java`、`channel-service/src/main/java/com/lrj/platform/channel/dingtalk/DingtalkMessageBridge.java`，默认传 null/default。
- 机器身份配置同时修改 `eval-service/src/main/resources/application.yml`、`deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、`deploy/helm/platform/templates/externalsecret-sample.yaml`：以 service identity token provider 取代 `EVAL_API_KEY`。
- 至少更新已核实的 `deploy/rag-demo.sh`、`seed-kb.sh`、`smoke-a2a.sh`、`smoke-es-hybrid-rag.sh`、`smoke-qdrant-rag.sh`、`smoke-nl2sql.sh`，从 `X-Api-Key` 改为短期 Bearer token；再以全仓 `rg 'X-Api-Key|EVAL_API_KEY'` 结果作为清零验收。

### 8.6 前端

- package 增加成熟 OIDC client（优先复用 AP auth-console 已验证的 authorization-code+PKCE 配置思想；框架适配 Vue）。
- `capability-showcase-frontend/src/api/auth.ts` 改为 OIDC manager adapter；移除本地 login/register/refresh cookie 合同。
- `capability-showcase-frontend/src/stores/auth.ts` 改为 OIDC bootstrap/callback/silent renew/logout；采用与 AP auth-console 一致的 `sessionStorage` 会话级存储，不用 `localStorage`。
- edge 只复制 Casdoor 已签发且在允许词表内的 scopes/permissions 到内部 JWT；目标态不在每次请求查询 legacy auth-service 计算 scope。
- router 新增 `/callback`；LoginView 改重定向 Casdoor；移除 RegisterView 主路由。
- `capability-showcase-frontend/src/api/authorizedFetch.ts` 的 401 single-flight 改 OIDC renew；业务 client 继续发 Bearer。
- `AuthControl`/`ApiKeyInput` 移除 API key 覆盖入口。
- Docker/nginx 增加 callback SPA 回退与 Casdoor build args；不再反代 `/auth/login|refresh`。

## 9. 数据库、接口、配置与消息变更

### 9.1 数据

- auth MySQL：不新增长期权威表；生成一次性导出/crosswalk 与校验报告。旧 `AUTH_SESSION` 不再产生新数据，保留期后清理需另行审批（本计划不授权删除）。
- knowledge Redis：
  - 新 `rag:spaces:<tenantId>` hash；
  - `rag:docs:<tenantId>` 的 JSON 只增加 `spaceId`；
  - 新 `rag:authz:spaces:<tenantId>`、`rag:authz:docs:<tenantId>` 保存独立授权记录，`rag:authz:pending:<tenantId>` sorted set 建索引，避免 `KEYS` 全扫。
- segment metadata 增加 `spaceId`；旧 segment 缺失时解释为 default，后台可重建索引。
- SpiceDB 增加 organization/space/document、tenant-scoped original/role group tuples；schema 类型大体不变。

### 9.2 接口

- 旧 `/rag/documents`、`/rag/query` 路径保留；spaceId 是可选加法字段/参数。
- 新 space CRUD/policy API 路径在实现前冻结 OpenAPI；建议 `/rag/spaces`。
- 新 auth-service internal snapshot 仅迁移期存在，service-auth、分页、版本化，结束后删除。
- AP admin sync 增加 dry-run/status；现有 `/admin/casdoor/sync` 可保持并扩充响应，避免破坏 console。
- 本地 `/auth/login|register|refresh|logout` 退出主 profile；前端 callback 是静态 SPA route，不路由到 auth-service。

### 9.3 配置/Secret

新增：Casdoor issuer/JWKS/audience/clientId、tenant/groups/scopes claim 名；AP server service credential；authz URL/mode/timeout/batch/reconcile；role mapping；webhook HMAC/nonce store。

删除目标：`SESSION_JWT_SECRET`、edge api-key Secret/mount、部署中的 `EVAL_API_KEY`、前端 demo password、下游 api-key fallback，以及脚本/文档中的生产 `X-Api-Key` 示例。eval/脚本改用 Casdoor service identity 短期 access token；内部 JWT RS256 keypair不变。

### 9.4 消息

首期不新增 Kafka topic。webhook payload 只触发 reconcile，不直接信任其成员内容。预留的投影 envelope 见方案 C，但不作为本期交付。

## 10. 分阶段实施步骤与依赖

### 阶段 0：前置验证（任何代码实施前）

1. 用真实 Casdoor 应用/token 固化 issuer、aud、sub、tenant、groups、scopes/permissions claim。
2. 确认机器身份 grant；确认 role→relation 表；确认多 tenant 用户规则。
3. 导出 auth DB 数据质量报告：用户数、重复/缺 userId、跨租户组、未知角色、crosswalk 缺失。
4. 导出每个 tenant/user 的 `EffectivePermissionResolver` scope 基线，完成 Casdoor permissions/claim 映射与差异报告。
5. 发布 AP SDK 到可复现制品仓库或固定 CI install 顺序。

完成标准：A1–A11 均有书面结论；真实 token contract test 通过；Casdoor 能提供现有粗粒度门禁需要的 scopes/permissions，group import mutation 合同与机器身份 grant 均实测可用。任一身份硬条件失败即阻塞并升级设计决策，不能长期回退到 auth-service 作为动态权限计算器；未决角色不允许进入 apply。

### 阶段 1：数据结构与领域模型

依赖阶段 0。

1. AP：canonical group ID、direct relationship desired/current/diff 模型、dry-run 报告模型。
2. LP：SpaceInfo/Registry、DocumentInfo 的 `spaceId` 加法字段、独立 Space/DocumentAuthorizationRecord 与 KnowledgeAuthorizationRegistry、resource ID codec、projection state/index。
3. migration reader、legacy identity→Casdoor sub crosswalk 与可重跑 Casdoor group import manifest；旧 docs/segments default space 兼容读取；历史 owner 无证据时保持 unknown。
4. schema compatibility test 与 Redis/MySQL/Casdoor import manifest 备份/恢复演练。

完成标准：旧 DocumentInfo 可读；相同 tenant/ID 输出稳定；跨租户组无碰撞；migration dry-run 不写数据且异常清单完整。

### 阶段 2：核心业务逻辑

依赖阶段 1。

1. knowledge `SubjectResolver`、AOP application service、RealKnowledgeAuthz fully-consistent checks。
2. space/document create/delete 状态机与关系写；query/list bulk filter。
3. AP GroupSync direct tuple refactor、role/original group projection、space binding、锁/阈值/reconcile。
4. AP server service auth 与 SDK timeout/credential。

完成标准：真实 SpiceDB required tests 覆盖 owner/direct viewer/group inheritance/revoke/cross-tenant；所有半失败可 reconcile；无 N+1。

### 阶段 3：接口与适配层

依赖阶段 2，可在后半并行准备但不能提前 enforce。

1. edge Casdoor validation + token exchange，保留 legacy profile。
2. frontend OIDC/callback/renew/logout；机器调用方 bearer。
3. document API 可选 spaceId、新 space API、protocol 兼容构造和所有调用方更新。
4. auth-service 冻结写、只读 snapshot；部署/Secret/NetworkPolicy 配齐。

完成标准：真实浏览器与 service account E2E 通过；内部 JWT/TenantContext 合同未变；Casdoor-only profile 下 local session/API key 全拒绝；旧 RAG 请求默认 space 可用。

### 阶段 4：测试与迁移/灰度

依赖阶段 3。

1. 执行 `test-plan.md` 全套；required integration 禁止 skip。
2. AP sync 先 dry-run，再只 TOUCH，最后经审批开启 DELETE。
3. knowledge disabled→shadow→按 canary tenant enforce→全量 enforce。
4. edge legacy+Casdoor 对照→Casdoor default→Casdoor-only；auth-service migration-readonly。

完成标准：身份四处一致；权限矩阵/迁移数量一致；延迟/错误/deny/topK 指标达门槛；回滚演练成功。

### 阶段 5：文档与最终检查

依赖阶段 4 稳定一个观察窗口。

1. 更新两仓库 README、架构、运维、部署、RBAC/public KB 文档。
2. 删除/禁用过期路由、Secret、API key UI；给 auth-service 退役建立独立变更单（删除 DB 需另批）。
3. 固化 dashboard、alert、runbook、owner、SLO 和制品版本。

完成标准：配置清单无旧 secret 活跃引用；文档示例只使用 Casdoor Bearer；全仓测试和 Helm/compose 渲染通过；最终验收签字。

## 11. 测试方案摘要

详细见 `test-plan.md`。发布门包括：

- SDK/AOP/exception、ID codec、projection state、direct tuple diff 单测。
- mock JWKS 的 edge token validation 与真实 Casdoor PKCE E2E。
- 真实 SpiceDB/AP required integration（不可 assumption skip）。
- auth RBAC→groups migration dry-run/apply/reapply/rollback。
- vector/registry/ES/graph/AP 各阶段 fault injection。
- 全仓 protocol consumer 编译回归、public KB/tenant isolation/RBAC admin 回归。
- 并发上传、reconcile 多副本、批量 check 性能与撤权 SLO。

## 12. 风险、监控、灰度与回滚

### 12.1 风险与控制

| 风险 | 控制 |
|---|---|
| token claim 误映射 | M0 真实 token contract；缺 tenant/sub fail closed；按 tenant canary |
| 合法用户伪造 active tenant | tenant claim 与已验证 organization/membership 交叉校验；拒绝 header/query tenant override；跨租户同 ID E2E |
| 跨租户同名组 | canonical tenant-scoped ID；迁移碰撞报告 |
| Casdoor 部分页失败触发大撤权 | 本轮 complete 标记；任何分页失败禁止 DELETE；变更量熔断 |
| 关系写与业务写半失败 | PENDING 状态、撤权优先删除、幂等 reconcile |
| 多副本水位/任务冲突 | 首期 fully consistent；分布式锁/版本；不依赖 AtomicReference |
| query 延迟/underfill | checkBulk 分批、over-fetch、p95/topK 指标；不以未授权结果补齐 |
| AP server 越权写 | 每服务/能力 credential、服务端 resource/relation allowlist、mTLS + NetworkPolicy + audit；credential 轮转，不能共享万能 token |
| 只发布 knowledge schema 覆盖共享 SpiceDB schema | 无变更时不写 schema；有变更时发布完整合并 schema，先 compatibility diff、备份和回滚演练 |
| legacy 长期不退 | `app.auth.mode`、到期告警、发布清单强制移除 route/secret |
| SDK SNAPSHOT 漂移 | 固定版本、制品仓库、consumer contract CI |

### 12.2 监控

- edge：OIDC success/failure by reason、JWKS refresh、claim missing、legacy credential attempts。
- knowledge：authz check latency/error/deny、bulk size、filtered hits、topK underfill、PENDING age/count、reconcile attempts。
- AP admin：run duration、desired/current/add/delete、skipped delete、source incomplete、lock contention、webhook lag。
- SpiceDB：check/write latency/error、fully-consistent QPS、relationship cardinality。
- 审计以 `traceId + tenantId + actorSub + resourceRef + decision` 关联；不记录 token/正文。

### 12.3 灰度

1. AP desired state dry-run。
2. 只 TOUCH、不 DELETE；抽样权限矩阵。
3. knowledge shadow 双算，响应沿旧逻辑；delta 归零。
4. canary tenant enforce；再按 10%/50%/100% tenant。
5. edge 先接受两类 token但遇到双凭证拒绝并告警；Casdoor 成为默认；最后 Casdoor-only。
6. auth-service 写冻结→只读→摘 route/deployment。

### 12.4 回滚

- knowledge：enforce→shadow/disabled 是配置回滚；不删 SpiceDB tuple，不回滚数据 schema。若已引入 space，旧 API仍 default。
- AP reconcile：先关 DELETE，再停 apply；用 apply 前 direct tuple 快照恢复误删。schema 只做向后兼容版本切换。
- edge：回滚到保留的 compatibility image/profile；旧 session secret/api-key 只在限定回滚窗口的 Secret 中封存。回滚不把 Casdoor sub tuple改回 username。
- migration：源 auth DB 只读备份、Casdoor import manifest、SpiceDB before/after tuple manifest；按 runId 只撤本次新建对象/关系，不能无过滤批量删除。
- 删除 auth DB、用户或 Git 历史不属于自动回滚动作，必须另行审批。

## 13. 最终验收清单

- [ ] 两仓库 Spring Boot/Java 构建与依赖树验证通过，AP SDK 制品可复现。
- [ ] Casdoor access token contract（iss/aud/sub/tenant/groups/scopes）真实验证通过。
- [ ] `sub == internal uid == TenantContext.userId == SpiceDB user id`。
- [ ] 生产 profile 的 API key/local session/local login 全部拒绝。
- [ ] 下游内部 JWT contract 与现有跨服务调用不变。
- [ ] space/document ID、parent、group binding tuple 符合规范；新资源 owner 正确，历史资源无证据时不存在伪造 owner tuple。
- [ ] upload/list/get/delete/query 的 allow/deny/故障矩阵通过。
- [ ] public KB 的 `public_viewer` 与现有行为核对通过。
- [ ] 旧文档全部归 default space；旧接口/消费者兼容。
- [ ] RBAC 源/迁移/crosswalk/SpiceDB 数量与抽样权限矩阵一致。
- [ ] 跨租户同名 group、同 docId/spaceId 无越权。
- [ ] reconcile 重跑零副作用；部分源失败不执行 DELETE；多副本不重复冲突。
- [ ] AP/SpiceDB/Redis/向量/ES/graph 半失败均有收敛路径。
- [ ] required integration tests 无 skip，全仓回归、前端、Helm/compose 渲染通过。
- [ ] 性能、撤权延迟、PENDING age、topK 满足率达到门槛并有告警。
- [ ] canary、全量、回滚演练均通过，tuple/data/配置备份可恢复。
- [ ] auth-service 退役日期、owner、runbook、监控和文档完成签字。
