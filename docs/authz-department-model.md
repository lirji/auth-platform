# 部门层级知识隔离授权模型（取代 D3）

> 知识库文档按**部门层级**隔离：本部门 + 所有上级部门自动可读，平级/下级/其他部门读不到；公共文档全可读；跨部门经**单篇分享**开权限。取代旧的 D3「同租户默认可见」。设计定稿见 `docs/plans/casdoor-multitenant-identity-0715-1635/FINAL_PLAN.md §A`，实施台账见 `docs/plans/dept-hierarchy-authz-impl-0715-1756/`。

## 1. 业务规则

- **部门是一棵树**（如 `研发与数据中心 → 技术平台部 → 电商组`），一个用户属于**一个**部门。
- **文档归属 = 上传人部门**（`home_dept`）。可读者 = **本部门成员 + 所有上级部门成员**（自动）；平级/下级/其他分支读不到。
- **公共文档**：所有人可读。
- **跨部门 = 申请**：批准后只把**这一篇**开给某人（写 `document#viewer@user`，复用现有 share）。
- **谁能分享**：文档 owner + **本部门任一成员** + 本/上级部门管理员。
- **谁能删除/覆盖**：**仅 owner**（V-06）。

## 2. SpiceDB 模型（`auth-platform-core/.../schemas/knowledge.zed`）

```
definition department {
    relation parent: department              // 上级部门（根无 parent）
    relation member: user                    // 本部门成员（一人一部门）
    relation admin:  user | group#member     // 部门管理员
    permission doc_reader = member + parent->doc_reader   // 本部门 + 所有上级 → 自动可读
    permission doc_admin  = admin  + parent->doc_admin
}
definition document {
    relation home_dept: department; relation owner: user; relation viewer: user; relation public: user:*
    permission view  = owner + viewer + public + home_dept->doc_reader
    permission share = owner + home_dept->member + home_dept->doc_admin
    permission edit  = owner                 // 删除/覆盖：仅上传人
    // 兼容窗口保留 parent_space/parent_folder/editor/commenter/public_viewer（仅回滚/控制台，不进新 view）
}
```
> 方向与旧 `space/folder` 的“向下继承”**相反**（这里祖先能看后代的文档）。`space/folder` 定义并存不删。

## 3. 身份链路（Casdoor group → 判权用的 department）

```
Casdoor 嵌套 group + role
   └─(DepartmentSyncService, admin=department-sync-enabled)→ SpiceDB department(parent/member/admin)
用户登录 → token.groups ─(edge CasdoorTokenExchangeFilter.extractDepartment)→ 内部 JWT dept
   → 下游 InternalToken.verify → TenantContext.department
上传文档 → DocumentService → RealKnowledgeAuthz.onDocumentCreated 写 document#home_dept
检索/分享 → checkBulk(view) / @CheckAccess(share)
```
- **一人一部门**：edge 从 token `groups`（`edge.casdoor.groups-claim`，默认 `groups`）取与 owner 同 org 的**唯一**组作部门（0=缺失 / >1=歧义 → 不猜、不拒登录）。
- 部门 id = `<tenant>_<group>`（`KnowledgeResourceIds.department` 与 `CasdoorGroupIds.encode` 同构）。

## 4. 部门管理员约定（V-03 = Casdoor role）

Casdoor group 无原生 admin 字段。约定：**Casdoor role 名 `<group>-admin` 的 `users` 即部门 `<group>` 的管理员**。`DepartmentSyncService` 读该 role，用户名经用户表解析为 subject，写 `department#admin`。

## 5. 开关与灰度（默认全关，引入即安全）

| 开关 | 作用 |
|---|---|
| `app.rag.authz.mode` = `disabled`(默认)/`shadow`/`enforce` | disabled 与接入前逐字一致；shadow 只观测；enforce 真过滤 |
| `authz.casdoor.department-sync-enabled` = false(默认) | 开启才把 Casdoor 部门树同步进 SpiceDB `department`（与旧 group 同步并存） |
| `edge.casdoor.groups-claim` = `groups` | token 里哪个 claim 承载用户组（部门） |

- **enforce 守卫**：enforce 下无法确定上传人部门 → **拒绝新建**（403，不写无归属孤儿文档）；disabled/shadow 不拦。
- **失败姿态**：判权依赖故障 shadow fail-open / enforce fail-closed（既有 F3/core 严格校验不变）。

## 6. seed / 验证

`deploy/dept-authz-fixture.sh`（取代 D3 的 `rag-authz-fixture.sh`）：seed 一棵演示部门树 + 文档，`APPLY=1` 写入并**强一致自校验**模型（本部门+上级可读、平级不可读、edit 仅 owner、share 含祖先管理员）。
```
TENANT=demo APPLY=1 bash deploy/dept-authz-fixture.sh
```

## 7. 多项目（§B）

每个**项目**独立一个 SpiceDB 实例（Casdoor 共享 IAM）。项目内对象 id 仍带 `<tenant>_` 前缀。因此各项目独占自己的 `knowledge.zed`，`schema/write` 整体替换即正确（不会误删他项目定义）。

## 8. 待办 / 上线注意

- **contract 阶段**：稳定后停 `onDocumentCreated` 的 `parent_space` 兼容双写，并收窄 `document.viewer` 为 `user`、清理旧 relation。
- **auth-console 控制台**：`lexicon.ts`/`SpacesPage.tsx` 仍硬编码 `public_viewer`/`edit`，兼容窗口需同步。
- **多 org 同步**：已支持——`CasdoorClient` 按 `authz.casdoor.organizations` 列表（空回退单 `organization`）逐 org 拉取 group/role 合并，`DepartmentSyncService` 随之多 org 化（部门 id 带 `<org>_` 前缀无碰撞）。`delete-threshold` 仍为跨 org 累计的全局熔断，per-org 隔离熔断/分页为后续。
- **集成/E2E**：真实 Casdoor(建部门 group + `<dept>-admin` role)→edge→knowledge→SpiceDB 全链路，需活栈。
- **共享 dev SpiceDB 拆分**：当前 dev :8543 含他项目定义（EMR：dept/encounter/patient），按 §B 应给本项目独占实例。
