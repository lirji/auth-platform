# FINAL_PLAN — 部门层级知识隔离·实施计划（Expand–Migrate–Contract）

> Codex 多视角规划（01-requirements / 02-codebase-analysis / 03-06 solution A–D）在写 comparison/test-plan 前到 10 分钟超时被杀；本 FINAL_PLAN 由 Claude 依据其产物 + 对两仓真实代码的核验合成。**设计已锁定**在 `casdoor-multitenant-identity-0715-1635/FINAL_PLAN.md §A`（本文件只做“怎么落地”，不改模型），但 **§0 的 V-06 是真实代码与锁定模型的接口缺口，需用户拍板**。

## 0. 采用方案 + 必须用户确认的一处缺口

**采用方案 A：原地 Expand → Migrate → Contract + Pull Reconcile**（03-solution-a）。即先加“兼容 schema”（department + 新 view/share 与旧 document relation/edit **并存**）、双写 + backfill + shadow 验证，再切 enforce，稳定后才 contract 掉旧 relation。否决 B/C/D（大爆炸/蓝绿/事件平台——对本任务过度或回滚差）。理由：最贴现有运行架构、每步可逆、backfill/shadow/幂等可证明。

**⚠️ V-06（必须你确认，否则实施阻断）**：锁定的 §A `document` 只有 `view` + `share`，**没有 `edit`**；但当前**同名覆盖**（`DocumentService.java:211-217`）和**删除**（`:332-340`）判的是 `edit`。**不能把它们直接改判 `share`**——因为 `share = owner + 本部门全体成员 + 管理员`，那会让**本部门任何人都能删/覆盖别人的文档**。所以 `document` 需要补一个**删除/覆盖用的权限**，与 share 分开。

> **V-06 已定稿（用户 2026-07-15）：`permission edit = owner`** —— **删除/覆盖仅上传人**（部门成员/管理员都不行；他们至多能 `share` 只读）。`document` 最终为 `view`/`share`/`edit(=owner)` 三权限。兼容窗口先保留旧 `edit` 不动，contract 阶段替换为 `edit=owner`。

## 1. 精确到文件的改动清单（对照真实代码，详见 02-codebase-analysis）

### 1.1 auth-platform（本仓库）
- **`auth-platform-core/.../schemas/knowledge.zed`**：加 `department`；`document` 加 `home_dept/public` 与新 `view/share`（+待确认的 `edit`）。**兼容窗口保留** `space/folder` 定义与 document 旧 `parent_space/parent_folder/public_viewer/editor/commenter` relation（供回滚），但**新 `view` 不再走 `parent_space->view`**（方向相反，不能混入同一表达式）。
- **`auth-platform-admin/.../casdoor/`**：`CasdoorProperties`（单 `organization`→organizations 列表 + page-size + per-org deleteThreshold + `writer-enabled` 开关，旧单值作 fallback）；`CasdoorClient`（`groupMembers/groupNames` 分页完整、读 group 的 **parent + admin**）；`GroupSyncService`（同步 `department` 的 **parent/member/admin**，读**全量 department direct tuple 按 `<org>_` 前缀分区**以发现被删部门，per-org 熔断，单写者）；`CasdoorGroupIds` 复用为 department id codec（更新注释）。
- **`deploy/rag-authz-fixture.sh`**：删除 D3 的 `space:<t>_default#viewer@group` 绑定，改 seed 部门树（parent/member/admin）+ 文档 `home_dept`；APPLY 后校验改为“本部门/上级可读、平级/下级/其他 deny”矩阵。
- **`deploy/casdoor-tenant-provision.sh`**（`WIRE_SPICEDB=1`）、**`deploy/spicedb-smoke.sh`**、**`deploy/server-smoke.sh`**：停止写/断言 D3 与旧 space/folder 继承，改部门模型（否则新租户继续制造待清理旧边）。
- **`auth-console`（IAM 控制台，React）**：`src/domain/lexicon.ts`、`src/pages/SpacesPage.tsx` 硬编码 `public_viewer` 且 document landing permission 固定 `edit`——兼容窗口需同步，否则控制台对不存在的最终 permission 发 check。
- **`auth-platform-protocol`**：**无需改**（`ResourceRef(type,id)` 自由字符串，`ResourceRef.of("department",id)` 即可；已核验）。**F3/core 严格校验只回归、不动**。

### 1.2 langchain4j-platform
- **`knowledge-service/.../authz/KnowledgeAuthz.java` + `RealKnowledgeAuthz.java` + `NoopKnowledgeAuthz.java`**：`onDocumentCreated` 签名加 `departmentId`；Real 改写 `owner + home_dept`（兼容窗临时也写 `parent_space=<t>_default` 供回滚）；Noop 仍无副作用。`onDocumentDeleted`/`grant/revokeDocumentViewer`/`filterReadable` **不改**。
- **`knowledge-service/.../authz/KnowledgeResourceIds.java`**：加 `department(project, deptId)`（复用 `join()`，与 `CasdoorGroupIds.encode` 同产 `<org>_<group>`）。
- **`knowledge-service/.../lifecycle/DocumentService.java`**：`upload`（~208）一次快照 `TenantContext`，把 department 传进 `onDocumentCreated`；`category` 仍只是 metadata。**覆盖(:215)/删除(:339) 暂留判 `edit`（V-06）**。
- **`knowledge-service/.../controller/DocumentShareController.java` + `authz/KnowledgeAccessApplicationService.java`**：`@CheckAccess(permission="edit")` 改 `"share"`（resourceType/resourceIdParam 不变）。
- **`knowledge-service/.../KnowledgeQueryService.java`**：**不改**，只补回归测试（新 `view` 语义下 shared 短路、无 docId fail-closed 仍正确）。
- **`platform-security` `TenantContext`**：`Tenant(tenantId,userId,scopes)` 加**可空 department** + **保留三参兼容构造器**（默认空）。
- **`edge-gateway` `CasdoorTokenExchangeFilter` + `CasdoorSecurityProperties` + `InternalToken`**：从 token `groups`（可配置 claim）取**唯一**部门→内部 JWT 加**可选 `dept` claim**→下游 `TenantContext` 重建。**发布顺序：先 reader 后 writer**（旧 token 缺 dept 兼容、旧 reader 忽略新 claim）。一人多 group→按 §6.3 规则取唯一或标 ambiguous，**部门异常不整体拒绝有效 token**，仅知识写路径按 mode 处理。

## 2. 身份链路（groups → department → TenantContext）
`Casdoor token.groups` →(edge `CasdoorTokenExchangeFilter` 提取唯一部门)→ 内部 JWT `dept` claim →(下游 `InternalTokenAuthFilter`)→ `TenantContext.department`。**一人一部门**：edge 去重后恰好 1 个候选才写；0=missing、>1=ambiguous（不猜）。缺部门时：disabled/shadow 不拦、enforce 的**上传/新建**明确拒绝（无法定 home_dept），**读**仍按现有 SpiceDB 关系 fail-closed。

## 3. 分阶段实施（每阶段：只做范围内 → 编译+测试 → 查 diff → 更新 IMPLEMENTATION_PROGRESS.md → 自检）

- **Phase 0 探活（实施第一步，本地活 Casdoor :8000）**：核实 V-01 group parent 字段/方向、V-02 分页、V-03 admin 标记、V-04 token `groups` 形状、V-05 SpiceDB 版本递归行为、V-07 存量 document group-viewer/editor/public_viewer inventory。未过的项**不实现对应映射、不猜字段**。
- **Phase 1 schema+领域模型（expand）**：knowledge.zed 加 department + 新 view/share(+edit)，旧 relation 并存；`KnowledgeResourceIds.department`；`knowledge.zed` 灌入 dev SpiceDB 验证编译。完成标准：新旧 schema 并存加载成功；`spicedb-smoke` 部门矩阵基础断言过。
- **Phase 2 判权核心**：`onDocumentCreated` 加 department + home_dept 写入（兼容双写）；share 判 `share`；`filterReadable` 回归。完成标准：单测部门 view/share 矩阵（owner/本部门/上级/平级/下级/公共/单篇 share）全绿；disabled 逐字不变。
- **Phase 3 身份与同步适配**：`TenantContext` 加 department（兼容构造）；edge 提取 dept→内部 JWT（先 reader 后 writer）；`GroupSyncService` 同步 parent/member/admin（per-org 分页/熔断/单写者）。完成标准：多 org 同步重复两次零 diff；缺/多部门按 §mode 姿态；DUAL/ONLY 不破坏。
- **Phase 4 测试**：单测矩阵 + 集成（真实 schema owner/本部门/上级/平级/下级/公共/单篇 share）+ 故障注入 + E2E（需活栈）。完成标准：01-requirements §9 验收 1–10 达成（可本地跑者全绿，需活栈者标注）。
- **Phase 5 backfill + contract + 文档**：backfill owner→唯一部门 home_dept（幂等、异常进 manifest、不用 category 猜）；disabled→shadow→enforce 灰度；观察窗后停双写、contract 旧 relation（**V-06 未定则不 contract、保留旧 edit**）；更新文档。完成标准：backfill 第二次零 diff、每文档恰一个 home_dept；灰度可回退。

## 4. 迁移 / 兼容 / 回滚（level=large 重点）
- **Expand-Migrate-Contract**：兼容窗口旧 relation 只供回滚、不进新 `view`；新文档临时双写 `home_dept`+`parent_space`。
- **backfill**：owner 的唯一 direct Casdoor 部门→home_dept；owner 缺失/无部门/多部门→异常 manifest 人工处理；旧 `public_viewer@user:*`→`public@user:*`；旧 `viewer@user` 原样；旧 `viewer@group#member` 与目标不兼容→盘点后显式展开或撤销，**禁止静默保留**。dev 跳过 backfill 须**证明** registry 无文档且 SpiceDB 无 document 关系。
- **灰度/回滚**：`app.rag.authz.mode` disabled↔shadow↔enforce；schema 可回旧（兼容窗口未 contract 前）；不物理删新旧 tuple；per-org 删除熔断。
- **兼容红线**：内部 JWT claim 加法字段（先 reader 后 writer）；`TenantContext` 三参兼容构造；不改 checkBulk 协议/F3/检索融合；不碰 casdoor-authz-rollout 的 Phase B-0（正交）。

## 5. 测试方案（详见待补 test-plan）
- **单测**：部门树 view 矩阵（BR-05/06）、share 矩阵（BR-09）、mode 三态 + missing/ambiguous/unsynced、GroupSync parent/member/admin + per-org 熔断 + 幂等、`KnowledgeResourceIds.department` 与 `CasdoorGroupIds.encode` 同构。
- **集成**（真实 schema/SpiceDB）：三层树 owner/叶/父/根 allow，平级/下级/旁支/其他租户 deny；share 权限矩阵；viewer grant/revoke 强一致即时；public。
- **回归**：F3/core（不改期望）、`filterReadable` shared 短路 + 无 docId fail-closed、disabled 逐字不变。
- **E2E**（需活栈 Casdoor+SpiceDB+edge enforce）：groups→dept→上传归部门→跨部门 deny→申请单篇 share→可见。

## 6. 验收清单
见 `01-requirements.md §9`（10 条）。补充：**V-06 已由用户拍板并落入 schema**；探活 V-01..V-07 全部有结论或明确“未过→不实现该项”。

## 7. 待用户确认 / 待验证
- **【阻断·需你定】V-06**：删除/覆盖用哪个权限?（我提议 `edit = owner + home_dept->doc_admin`）。
- 探活 V-01..V-05、V-07（实施 Phase 0，本地 Casdoor 已在 :8000，可现做）。
- 存量数据：是否 dev 空库（可跳 backfill）还是有历史文档需迁 home_dept。
