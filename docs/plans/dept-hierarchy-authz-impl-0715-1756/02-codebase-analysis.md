# 代码库分析：部门层级授权影响面

## 1. 核验范围与工作树边界

本文件是 `codebase-explorer` 视角。行号基于 2026-07-15 当前工作树，实施前应以 `nl -ba` 重新定位。

- auth-platform：`/Users/liruijun/personal/LLM/auth-platform`，当前分支 `sdk-strict-authz-response`。F3/core 严格校验已在提交 `f3bbfc6`、`0161fb1`；admin Casdoor 同步相关文件和测试仍有未提交/未跟踪改动。
- langchain4j-platform：`/Users/liruijun/personal/LLM/langchain4j-platform`，`main` 当前为 `7f70ef7`，工作树干净；rag-tenant-authz 已落为提交，不再是该仓未提交改动。
- `casdoor-authz-rollout` 的 Phase B-0 是 edge scope 门禁，和本计划的 department claim/knowledge ReBAC 正交；不得在同一修改中重复实现或改写它。

实施 Agent 必须先保存两仓基线状态，不覆盖 auth-platform 下列现有用户改动：

- `auth-platform-admin/.../CasdoorClient.java`、`CasdoorConfig.java`、`CasdoorProperties.java`、`GroupSyncService.java`；
- 未跟踪的 `CasdoorGroupIds.java`、`auth-platform-admin/src/test/...`；
- 既有计划、`deploy/casdoor-tenant-provision.sh` 和文档改动。

## 2. auth-platform 当前模型和协议

### 2.1 `knowledge.zed`

`auth-platform-core/src/main/resources/schemas/knowledge.zed:5-55` 当前包含：

- `group` 可嵌套（7-10）；
- `organization`（12-16）；
- `space` 的 owner/admin/editor/commenter/viewer/public_viewer（18-31）；
- `folder` 从 parent folder/space 向下继承 edit/view（33-41）；
- `document` 从 parent folder/space 向下继承 edit/view，另有 direct owner/editor/commenter/viewer/public_viewer（43-55）。

现方向是“有权访问上层容器 → 有权访问下层文档”。目标 department 是“文档所属叶部门的 member 加祖先部门 member”，递归发生在 department 自身，方向不同。

结论：

- `space/folder` 定义应并存，避免破坏其独立对象和控制台；
- 最终 `document` 不再用 `parent_space/parent_folder` 推导 `view`；兼容窗口才保留旧 relation/permission 和临时双写；
- 旧 `public_viewer`、document group viewer、editor/commenter 和 parent tuple 都是 schema 收缩前必须盘点的数据。

### 2.2 协议无需为 department 改型

`auth-platform-protocol/src/main/java/com/lrj/authz/protocol/ResourceRef.java:4-12` 是自由字符串 `record ResourceRef(String type, String id)`；`ResourceRef.of("department", id)` 已可表达部门，无 enum 或 allowlist，协议无需修改。

`RelationshipFilter.java:3-12` 支持 `resourceId/relation=null`，可读取一个资源类型的全部 direct tuple；`SubjectRef.java:7-18` 已支持 direct user 和 `group#member`。`RelationshipUpdate.java:3-17` 已支持 CREATE/TOUCH/DELETE。

### 2.3 F3/core 边界

`SpiceDbAuthzEngine.java:31-92` 对 single/bulk permissionship 严格校验；`checkBulk` 要求响应基数完全对齐，错误不会伪装成 deny。`readRelationships` 在 178-207 可用于同步/backfill inventory。此次只复用，不修改其判权协议或放宽校验。

## 3. Casdoor 同步现状

### 3.1 配置和客户端

- `CasdoorProperties.java:9-19`：单一 `organization`、`subjectField`、reconcile interval、一个全局 `deleteThreshold`；没有 org 列表、分页、single-writer 或 page-size 配置。
- `CasdoorClient.java:31-47`：`groupMembers()` 只调用一次 `/api/get-users?owner=<organization>`，从 user `groups` 聚合成员；无分页完整性证明。
- `CasdoorClient.java:49-60`：`groupNames()` 只调用一次 `/api/get-groups`；只读 owner/name，不读取 parent/admin。
- `CasdoorClient.java:66-74`：`scopedGroupId()` 能处理 `<org>/<group>` 或短名。
- `CasdoorGroupIds.java:20-54`：未跟踪的新 codec 已把 id 固定为 `<organization>_<group>`，并对 `_`、`/` 等 fail-closed。该实现正好可复用为 department object id，但注释仍只描述 SpiceDB group。

Casdoor parent 字段、admin 标记、分页参数和 token groups 形状均未被当前代码证明，必须保留“待验证”。

### 3.2 reconcile

`GroupSyncService.java:53-91` 当前：

1. 拉 `groupMembers/groupNames`；
2. 对每个 group 调 `directMembers()`（94-103），形成 N+1 relationship reads；
3. 缺失 TOUCH、多余 DELETE；
4. 全局 DELETE 数超过阈值则整轮零写；
5. `sync()` 是 `synchronized`，只保护单 JVM；
6. 一次 `writeRelationships(updates)`，未分批。

隐藏缺口：当前循环只覆盖本轮 Casdoor 返回的组。被 Casdoor 删除、因而不再出现在 `groupNames()` 的旧组不会被看到，其旧 tuple 会残留。多 org 版本应读取所有 `department` direct tuple，再按 `<org>_` 前缀分区，才能发现被删除部门。

入口：

- `CasdoorConfig.java:17-25` 构建一个 client/service；
- `ReconcileJob.java:20-24` 定时调用全局 `sync()`；
- `CasdoorSyncController.java:33-60` 手工和 webhook 都调用全局 `sync()`；
- `application.yml:12-20` 当前没有 `authz.casdoor` 默认块。

现有 `GroupSyncServiceTest.java:31-109` 已覆盖 direct tuple、嵌套 group 忽略、删除熔断和幂等；`CasdoorGroupIdsTest.java:12-54` 覆盖租户化和碰撞拒绝，可扩展而非重写。

## 4. seed、smoke 和控制台影响

- `deploy/rag-authz-fixture.sh:18-49` 构造 default space、成员 group 和 `parent_space`；57-87 的 APPLY 门禁只证明 D3 链。必须整体改成 department manifest/tuple/matrix。
- `deploy/casdoor-tenant-provision.sh:17-20,92-98` 的 `WIRE_SPICEDB=1` 仍写 D3 space viewer，也必须停止该语义；否则新租户还会制造待清理旧边。
- `deploy/spicedb-smoke.sh:22-70` seed/断言全是旧 space/folder/document/public_viewer 模型，目标 schema 后会失败；应改为部门 view/share 矩阵。
- `deploy/server-smoke.sh:14-40` 经 AP server seed `parent_space` 并断言旧继承；应改为 department，同时保留对 F3 API 形状的覆盖。
- `auth-console/src/domain/lexicon.ts:4-76` 没有 `department`，document 仍暴露旧 relation/permission。
- `auth-console/src/pages/SpacesPage.tsx:17-31,82-111,203-225` 将 `public_viewer` 写死并把 document landing permission 固定为 `edit`。兼容窗口若不更新，控制台会继续写旧 tuple或对不存在的最终 permission 发 check。

这些是实际 schema 消费方，不能只改用户点名的 fixture 后忽略。

## 5. langchain4j knowledge 写读链

### 5.1 资源 ID

`KnowledgeResourceIds.java:7-28`：

- `document(tenantId, docId)` 和 `space(tenantId, spaceId)` 都走 `join()` 得 `<tenant>_<id>`；
- `organization(tenantId)` 保持裸 tenant；
- 尚无 `department(project, deptId)`。

计划新增 department 方法只复用 `join()`，document/space/organization 命名不变。

### 5.2 创建、删除和分享

`RealKnowledgeAuthz.java`：

- `onDocumentCreated`（86-95）写 owner CREATE + `parent_space` TOUCH；
- `onDocumentDeleted`（98-101）按 document resource 清全部 tuple，可原样保留；
- `grant/revokeDocumentViewer`（104-113）写 direct user viewer，可原样保留；
- `filterReadable`（135-178）按 `view` 调 `checkBulk`、fully-consistent、分批、协议缺项默认 deny，机制无需改。

接口 `KnowledgeAuthz.java:22-43` 和 `NoopKnowledgeAuthz.java:13-40` 的创建签名只有 owner，需要增加 department 参数；Noop 必须仍无副作用。

`DocumentService.upload`（188-279）实际顺序是多存储写入 → registry.put → 新建时 `onDocumentCreated`（263-267）。`category` 只在 232-234 写 metadata。计划在 208-209 一次快照 `TenantContext`，将其 department 传到新签名；shared 公共库仍跳过这条 ReBAC 写路径。

重要兼容缺口：

- 同名覆盖在 `DocumentService.java:211-217` 判 `edit`；
- delete 在 332-340 判 `edit`；
- 目标 document schema 没有 `edit`。

不能把这些调用机械改成 `share`，因为那会允许本部门所有成员覆盖/删除文档。兼容 schema 必须暂留旧 edit，最终 contract 前由设计负责人确认目标权限。

分享 AOP：

- `KnowledgeAccessApplicationService.java:35-43` 的两个方法实际持有 `@CheckAccess(permission="edit", resourceType="document", resourceIdParam="documentResourceId", fullyConsistent=true)`；应只把 permission 改为 `share`。
- `DocumentShareController.java:35-52` 本身没有 `@CheckAccess`，它校验 grantee 并构造完整 document ID后调用 service；resourceType/resourceIdParam 不在 controller，路由和参数无需改，只更新注释。

### 5.3 查询无需改

`KnowledgeQueryService.query` 在 250-302 快照 tenant/user、融合后调用私有 `filterReadable`。私有方法 327-355：

- disabled 直通；
- non-shared 且有 docId 才加入 bulk；
- shadow 不拦；
- enforce 下 shared 短路放行、无 docId fail-closed。

新 schema 仍暴露 `document.view`，所以该文件不改，只补回归测试。公共共享库是 `__public__` 分区机制，不等于 SpiceDB `document#public` writer；两条路径都应测试，不能混为一谈。

## 6. 身份与内部 JWT 链

### 6.1 当前形状

- `TenantContext.java:16-44`：`Tenant(tenantId,userId,scopes)`，没有 department。
- `InternalToken.java:98-113` mint `sub=tenantId`、`uid`、`scopes`；115-139 verify 重建三字段 Tenant。
- `InternalTokenAuthFilter.java:59-70` 从内部 JWT或 API-key binding 重建 Tenant。
- `CasdoorTokenExchangeFilter.java:106-132` 从已验 token 取 owner/sub/scopes，115 行 mint 内部 JWT；没有读取 groups。
- `CasdoorSecurityProperties.java:42-58` 可配置 aud/tenant/sub/scope claim，没有 department/groups claim。
- `edge application.yml:111-129` 固定 owner/sub/permissions 配置。

### 6.2 构造点与兼容策略

生产 `new TenantContext.Tenant(...)` 还存在于：

- `auth-service/.../SessionTokenIssuer.java:51`；
- `edge-gateway/.../ApiKeyToInternalTokenFilter.java:57`；
- `platform-security/.../InternalToken.java:136`、`InternalTokenAuthFilter.java:68`；
- `channel-service` 的 `DingtalkMessageBridge.java:64`、`FeishuMessageBridge.java:60`；
- `interop-service/.../A2aPushForwarder.java:77`；
- `workflow-service/.../ServiceTaskDelegates.java:174`、`WorkflowService.java:376`。

为避免一次性修改全平台和破坏 DUAL/legacy，`Tenant` 增加 department component 时应提供原三参数兼容构造器，默认 department 为空。新增内部 JWT `dept` claim 是可选加法字段：新 reader 接受旧 token 缺 dept，旧 reader忽略新 claim。发布顺序必须先 reader 后 edge writer。

`InternalSecurityProperties.KeyBinding.java:105-116` 可增加可选 department，使 dev API-key 能跑部门写路径；没有配置时仍为空。session/消息桥接身份不应猜部门。

### 6.3 groups 唯一性

真实 token 的 `groups` 形状尚未由代码/现有 spike 证明。edge 应：

1. 读取可配置 claim；
2. 只接受与 token owner 同 org 的合法 group 引用；
3. 去重；
4. 恰好一个候选时写 department；0 个标 missing，>1 标 ambiguous；
5. 不因 department 异常把有效 token 整体降为无效，知识写路径再按 mode 拒绝。

若探活证明 token 同时带 direct group 和祖先 group，则“恰好一个”规则需改为基于已验证数据选唯一 direct/最深叶节点；在验证前不得按字符串长度或顺序猜。

## 7. 配置、数据和消息变更汇总

| 类型 | 当前 | 计划 |
|---|---|---|
| SpiceDB schema | document→space/folder | 新 department；document view/share 按 §A；兼容窗口留旧 relation |
| SpiceDB tuple | parent_space、public_viewer | parent/member/admin、home_dept、public；viewer user 不变 |
| SQL/业务表 | 无 department 字段 | 本计划不新增 SQL 表；department 来自可信身份和 SpiceDB |
| Casdoor API | 单 org、单页 users/groups | 多 org、完整分页、原始 parent/admin 探活后映射 |
| Java 接口 | `onDocumentCreated(tid,doc,owner)` | 增加 `departmentId` 参数 |
| TenantContext | tenant/user/scopes | 增加可空 department + 三参兼容构造 |
| 内部 JWT | sub/uid/scopes | 可选 `dept` claim；缺失向后兼容 |
| edge 配置 | tenant/sub/scope claim | 增加 department/groups claim 名 |
| admin 配置 | organization 单值、全局阈值 | organizations 列表、page-size、per-org 阈值、writer 开关；旧单值作兼容 fallback |
| backfill 输入 | D3 env/manifest | owner→唯一部门映射 + 文档异常 manifest |

## 8. 可复用代码与测试

- 复用 `KnowledgeResourceIds.join`、`CasdoorGroupIds.encode` 的租户前缀规则。
- 复用 `AuthzEngine.readRelationships/writeRelationships` 和 TOUCH/DELETE 差量。
- 复用 `RealKnowledgeAuthz.filterReadable/checkDocument` 的 fully-consistent、批次、metrics、shadow/enforce 故障姿态。
- 复用 `DocumentShareController` 路由与 direct viewer grant/revoke。
- 复用 `GroupSyncServiceTest` 的 direct tuple、delete fuse、idempotency 结构，扩成 parent/member/admin/per-org。
- 复用 `RealKnowledgeAuthzTest.java:34-231`、`CheckAccessShareTest.java:89-124`、`KnowledgeAuthzIntegrationTest.java:70-150`，替换旧 D3 断言为部门矩阵。
- `RemoteAuthzEngineTest` 与 `SpiceDbAuthzEngine` F3 测试只回归，不修改期望。
