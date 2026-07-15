# 实施进度 — 部门层级知识隔离

> 计划见本目录 `FINAL_PLAN.md`（Expand-Migrate-Contract）。决策已锁：登录=C；`edit=owner`（V-06）；部门=Casdoor 嵌套 group。恢复先读本文件。

## Phase 0 — 探活（read-only 本地 Casdoor :8000）✅
- V-01 Casdoor group 父字段 = **`parentId`**（`built-in/engineers` parentId="" 为根）。
- **greenfield**：acme/beta 无 group，alice `groups=[]` → **无历史部门数据，无需 backfill**（仍需建部门树来测/用）。
- V-03 部门管理员**无原生 group 字段** → 需约定（GroupSync 阶段定；候选：Casdoor role / `<dept>_admins` 子组 / user property）。
- V-02 分页参数 `p`/`pageSize` 被接受。

## 发现→已决策：SpiceDB 跨项目【每项目独立实例】（方案 B）
dev SpiceDB(:8543) 现为**多项目并集**（knowledge + 另一项目 `dept/encounter/patient`）。**用户 2026-07-15 定方案 B：每项目独立 SpiceDB**（见 casdoor-multitenant-identity §B）。
- → 之前"整体 `schema/write` 会删他项目定义"的隐患**在 B 下消失**：langchain4j 独占自己的 SpiceDB，`schema/write knowledge.zed` 整体替换即正确，无需合并部署。
- **dev 待办**：拆分当前共享实例，langchain4j 用独占 SpiceDB；本轮功能验证暂用现有实例（additive 合并，未删他项目定义）。
- `department` 与 `dept` 不同名，B 下更无冲突。

## Phase 1 — schema（Expand）✅ 完成并功能验证
- 改 `auth-platform-core/.../schemas/knowledge.zed`：新增 `department{parent,member,admin; doc_reader=member+parent->doc_reader; doc_admin=admin+parent->doc_admin}`；`document` 新增 `home_dept/public` + 新 `view=owner+viewer+public+home_dept->doc_reader`、`share=owner+home_dept->member+home_dept->doc_admin`、`edit=owner`；**兼容窗口保留** `parent_space/parent_folder/editor/commenter/public_viewer` 旧 relation（供回滚/auth-console）。
- **校验**：additive 合并进共享 schema 写入 dev SpiceDB **HTTP 200**（他项目 dept/encounter/patient 保留）。
- **功能测**（live SpiceDB，例：研发中心→技术平台部→电商组 + 平级产品部）：**15/15 全过**——view 本部门+上级=T/平级=F；share owner+本部门成员+本/上级管理员=T/上级普通成员+平级=F；edit 仅 owner；单篇 viewer=T；public→平级=T。测试关系已清理。
- 未提交（在分支 `sdk-strict-authz-response` 工作树；提交/分支策略待定）。

## Phase 2 — 判权核心（knowledge-service）— 待
`RealKnowledgeAuthz.onDocumentCreated` 加 department 参数写 home_dept（替代 parent_space=default）；`KnowledgeResourceIds.department`；`@CheckAccess(edit→share)`；`filterReadable` 回归。**依赖 Phase 3 提供 department 值。**

## Phase 2 — 判权核心（knowledge-service）✅ 完成，测试绿
- [x] `KnowledgeResourceIds.department(tenantId, deptId)`。
- [x] `KnowledgeAuthz.onDocumentCreated` 加 `departmentId`；`RealKnowledgeAuthz` 写 owner + **home_dept**（+ 兼容窗口 parent_space 双写）；`NoopKnowledgeAuthz` 空实现。
- [x] `DocumentService.upload` 传 `TenantContext.current().department()`。
- [x] `@CheckAccess(edit→share)`（share/unshare）。
- [x] 测试更新并全绿：**RealKnowledgeAuthzTest 17、KnowledgeQueryServiceAuthzTest 4、CheckAccessShareTest 3**（含新增 no-department 用例；CheckAccess 改断言 `share`）。

## Phase 3 — 身份与同步适配 — 进行中
- [x] **3a `TenantContext.Tenant` 加可空 `department`**（第 4 字段 + 三参兼容构造）。全 langchain4j 离线 `mvn compile` 绿。
- [x] **3b `InternalToken` mint/verify 加可选 `dept` claim**（mint 非空才带；verify 缺失→null；旧 token 兼容）。test-compile 绿。
- [x] **3c edge 从 token `groups` 取唯一部门**：`CasdoorSecurityProperties.groupsClaim`；`CasdoorTokenExchangeFilter.extractDepartment`（同 org、去重、恰好一个→部门；0/>1→null 不猜，不因部门异常拒登录）→ 4 参 `mint`。**edge CasdoorTokenExchangeFilterTest 11/11 绿**（存量行为不变）。
- [x] **3d `DepartmentSyncService`（新）**：`CasdoorClient.departmentSnapshot`（users→member、groups.parentId→parent、role `<dept>-admin`→admin，用户名经用户表解析 subject）→ SpiceDB `department` 差量同步(member/parent/admin) + 删除熔断，与旧 `GroupSyncService` 并存。**DepartmentSyncServiceTest 2/2、GroupSyncServiceTest 4/4 绿**。
  - 探活确认：Casdoor group 父字段=`parentId`；role 有 `users`(=`<org>/<username>`)。V-03 约定：role 名 `<group>-admin` 标该部门管理员。
  - [x] **接线**：`CasdoorProperties.departmentSyncEnabled`(默认 false)；`CasdoorConfig` 加 `DepartmentSyncService` bean（`@ConditionalOnProperty department-sync-enabled`）；`ReconcileJob` 注入 `ObjectProvider<DepartmentSyncService>` 一并定时对账。编译+测试绿。
  - **待办**：多 org 分页/per-org 熔断；CasdoorSyncController 部门 webhook（可选）。

## enforce 守卫 ✅
- [x] `DocumentService.upload`：enforce 下新建文档若无法确定上传人部门 → **403 拒绝**（不写孤儿文档）；disabled/shadow 不拦。**DocumentServiceTest 7/7 绿**（存量用例走 disabled，不触发）。

## Phase 5 文档 ✅
- [x] `docs/authz-department-model.md`：模型/schema/身份链路/V-03 admin 约定/开关灰度/seed/多项目/上线待办 一份完整设计+运维文档。

## Phase 5（部分）— seed 脚本 ✅
- [x] `deploy/dept-authz-fixture.sh`（**取代 D3 的 rag-authz-fixture**）：seed 部门树(rnd→platform→ecom + 平级 product)+成员+管理员+文档 home_dept；APPLY 后强一致自校验。**`bash -n` + APPLY=1 live SpiceDB 实跑 9/9 断言绿**（view 本部门+上级=YES/平级=NO、share owner+祖先管理员=YES/上级成员=NO、edit 仅 owner），已清理。

## 端到端状态
schema(1)✅ + knowledge-service(2)✅ + 身份链路(3a/b/c)✅ + 部门同步(3d)✅ + seed✅，**全部编译/单测/功能验证绿**。**离生产就绪剩**：3d 接线 + 强制态"无部门上传拒绝"守卫 + Phase4 集成/E2E(需活栈) + Phase5 contract(停 parent_space 双写)/auth-console 控制台兼容/文档 + **跨两仓提交整理**。

> 注：`knowledge-service` 测试树有一处**与本任务无关的既有编译问题**（`KnowledgeQueryControllerTest` 引用 `KnowledgeRuntimeView.RagRuntime`，属未安装的 protocol 改动）——用 `-am` 从源码构建 protocol 即绕过，非本轮引入。

## Phase 4/5 — 测试 / seed+文档 — 待
seed 部门树脚本（替代 D3 fixture）；单测矩阵 + 集成；shared-SpiceDB 合并部署步骤；灰度回滚。
