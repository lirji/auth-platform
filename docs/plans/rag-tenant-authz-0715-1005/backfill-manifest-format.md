# 关系 backfill / binding manifest 格式（v1）

> Phase 1 领域模型产出：定义"把存量数据补成 ReBAC 关系"的**声明式 manifest 格式**，供 Phase 3 的
> `deploy/rag-authz-fixture.sh` / backfill 脚本消费。**不新增 SQL、不新增业务字段**；关系一律写 SpiceDB。
> 冻结决策：同租户默认可见性 = **选项①**（见 FINAL_PLAN §17）。

## 1. 为什么需要 manifest

`RealKnowledgeAuthz` 只在**新建**文档时写 `owner`/`parent_space`。切 `enforce` 前，存量文档没有关系会
被全部隐藏（"搜不到"）。manifest 用**可证明的**事实幂等补齐关系，并显式声明每租户的成员组绑定，
避免脚本内散落硬编码或猜测。原则：

- 只写**可证明**的关系：`parent_space` 可由 registry 的 `<tenant,docId>` 证明。
- **不伪造 owner**：无可信 owner manifest 时 owner 留空（unknown），绝不给全体补 blanket owner/viewer。
- group id 一律经 `CasdoorGroupIds.encode`（v1 `<org>_<group>`），拒绝歧义输入。

## 2. 顶层结构（YAML；JSON 等价）

```yaml
version: v1                      # manifest 格式版本
groupCodecVersion: v1            # 必须与 CasdoorGroupIds.VERSION 一致，否则脚本拒绝执行
tenants:
  - tenantId: acme               # = Casdoor organization = SpiceDB organization 对象 id
    # 该租户"全体成员组"短名。用于选项①同租户默认可见：绑到 default space 的 viewer。
    membersGroup: members        # 经 CasdoorGroupIds.encode(tenantId, membersGroup) → group:acme_members
    defaultSpace: default        # default space 短名 → space:acme_default（KnowledgeResourceIds.space）
    # —— 选项①：同租户默认可见（本 manifest 冻结启用）——
    defaultSpaceViewerGroups:    # 绑到 space:acme_default#viewer 的组（短名，逐租户显式声明）
      - members
    # 额外的 group→space 授权（可选，RBAC 特例）：viewer/editor/commenter/admin
    spaceBindings:
      - space: default
        relation: editor
        group: research          # → space:acme_default#editor@group:acme_research#member
    # 存量文档 owner 白名单（仅可信来源；缺省则 owner 留空 unknown，不伪造）
    documentOwners:              # 可选
      - docId: a66563a5a4528e01
        ownerUserId: u_alice     # = Casdoor sub
        source: registry-uploader   # 证据来源，审计用
```

## 3. 脚本据此写入的 SpiceDB 关系

对每个 tenant：

```text
# 租户本体（若缺）
organization:<tenantId>#... （按 admin 授权流程另配，非本 manifest 必写）

# 选项① 同租户默认可见：成员组绑 default space viewer
space:<tenantId>_<defaultSpace>#viewer@group:<enc(tenantId,membersGroup)>#member
  例：space:acme_default#viewer@group:acme_members#member

# 额外 spaceBindings（逐条）
space:<tenantId>_<space>#<relation>@group:<enc(tenantId,group)>#member
  例：space:acme_default#editor@group:acme_research#member

# 存量文档 parent_space（对能由 registry 证明 <tenant,docId> 的文档，幂等 TOUCH）
document:<tenantId>_<docId>#parent_space@space:<tenantId>_<defaultSpace>

# 存量文档 owner（仅 documentOwners 显式列出的可信项）
document:<tenantId>_<docId>#owner@user:<ownerUserId>
```

> group 成员元组 `group:<org>_<group>#member@user:<sub>` 由 `GroupSyncService`（Phase 2 迁移后）写，
> **不**在本 manifest 内重复维护，避免双写源。

## 4. 执行契约（Phase 3 脚本遵守）

- **幂等**：全部 TOUCH；可重复跑。
- **dry-run 默认**：先输出将写/将删清单与对账（现有 vs 期望），确认后再 apply。
- **fail-closed**：任一 group 短名经 codec 抛出 → 整条拒绝，不部分写。
- **不删**：backfill 只增不删；旧短名 group tuple 的清理是独立、带阈值/manifest 的 Phase 2/5 步骤。
- **owner 不可造**：`documentOwners` 未列出的文档 owner 保持 unknown，靠成员组 viewer 可见（选项①）。

## 5. 与代码的锚点

- group id 编码：`auth-platform-admin/.../casdoor/CasdoorGroupIds.encode`（v1）。
- 资源 id 编码：`knowledge-service/.../authz/KnowledgeResourceIds`（`document`/`space`/`organization`）。
- 消费脚本（计划新增，Phase 3）：`langchain4j-platform/deploy/rag-authz-fixture.sh` 或并入
  `deploy/smoke-rag-tenant-authz.sh`。
