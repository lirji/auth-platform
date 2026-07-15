# 需求分析：部门层级知识隔离落地

## 1. 分析依据与约束

本文件是 `requirements-analyst` 视角。设计依据是：

- `docs/plans/casdoor-multitenant-identity-0715-1635/FINAL_PLAN.md:27-94` 的 §A；
- `docs/plans/casdoor-multitenant-identity-0715-1635/casdoor-capability-spike.md:1-46`；
- 2026-07-15 两仓当前工作树中的真实代码。

§A 已定稿。本轮不讨论替代 schema，不重开“一 org 一 app / 共享 app”的登录方案，也不改变“项目=租户=Casdoor org、部门=Casdoor 嵌套 group、一人一部门”。四个候选方案只比较实施、迁移、同步和切换路径，最终数据模型完全相同。

## 2. 目标

1. 将文档授权从 D3“default space 可向租户成员组开放”替换为部门树模型。
2. 文档上传时固定归属上传人的唯一部门；本部门成员和所有祖先部门成员自动可读。
3. 平级、下级、其他分支和其他租户不能读取；公共文档和单篇 `viewer` 是两个显式例外。
4. 文档 owner、本部门任一成员、本部门管理员和所有祖先部门管理员可直接授予/撤销单篇 `viewer`，不建设 workflow。
5. Casdoor group 的 direct member、父子边和管理员标记可按多个 org 完整、分页、幂等地同步到 SpiceDB `department`。
6. 保留 `app.rag.authz.mode=disabled|shadow|enforce` 的现有含义；默认 disabled 的运行结果必须与当前版本逐字一致。
7. 存量文档、存量关系和内部 JWT 可在不中断回滚能力的前提下迁移；所有删除都有 per-org 熔断，灰度期不物理删旧数据。

## 3. 已确认业务规则

| ID | 规则 | 可验证结果 |
|---|---|---|
| BR-01 | 项目=租户=Casdoor org | 资源 ID 仍以 `<tenantId>_` 前缀隔离；不改变登录层 org/app 决策 |
| BR-02 | 部门树=Casdoor 嵌套 group | 每个 Casdoor 部门 group 对应 `department:<org>_<group>` |
| BR-03 | 一人一部门 | 每 org 每个用户恰好一个 direct department；0 或 >1 均为身份异常 |
| BR-04 | 文档归属上传人部门 | 新建写 `document:<org>_<doc>#home_dept@department:<org>_<dept>` |
| BR-05 | 纵向自动可读 | 文档所属部门成员及该部门所有祖先部门成员可 `view` |
| BR-06 | 横向默认拒绝 | 平级、下级、旁支和其他租户无 `view` |
| BR-07 | 公共可读 | `document#public@user:*` 可读；现有共享公共库命中仍由 `Hit.shared()` 短路 |
| BR-08 | 单篇跨部门授权 | 只写/删该文档的 `viewer@user:<sub>`，不授 group、不改整部门 |
| BR-09 | 分享者 | owner + 本部门成员 + 本/祖先部门 admin 可 `share` |
| BR-10 | 分类不判权 | `category` 继续是检索/展示元数据，不从分类猜部门 |
| BR-11 | 检索机制不改 | 融合后按非空 docId 去重，`checkBulk(view)`；shared 放行、无 docId enforce 丢弃 |
| BR-12 | D3 被取代 | 不再 seed `space:<tenant>_default#viewer@group:<tenant>_members#member` |

锁定的目标 schema 为：

```zed
definition department {
    relation parent: department
    relation member: user
    relation admin: user | group#member
    permission doc_reader = member + parent->doc_reader
    permission doc_admin = admin + parent->doc_admin
}

definition document {
    relation home_dept: department
    relation owner: user
    relation viewer: user
    relation public: user:*
    permission view = owner + viewer + public + home_dept->doc_reader
    permission share = owner + home_dept->member + home_dept->doc_admin
}
```

## 4. 模式与失败姿态

| 场景 | disabled | shadow | enforce |
|---|---|---|---|
| token 无 `groups` | 当前行为不变，不要求部门 | 身份仍可使用；记录 `department=missing`；读只观测，缺部门的新建关系不伪造 | 读按 SpiceDB 现有关系 fail-closed；上传/新建因无法确定 `home_dept` 明确拒绝 |
| token 有多个候选部门 | 当前行为不变 | 不任选其一；记录 `department=ambiguous` | 不任选其一；上传/新建拒绝 |
| department 未同步 | 当前行为不变 | `view` 计算 would-deny 并告警 `department_unsynced` | 非 owner/viewer/public 默认 deny；上传切 enforce 前必须有同步水位门禁 |
| AP/SpiceDB 故障 | 当前 Noop 行为 | 读 fail-open 并记 error；写路径不得伪造成功 | 读 fail-closed；写失败向上传播 |

`DUAL/ONLY` 是 edge 的认证来源切换，不能被部门异常重新定义：有效 Casdoor token 即使部门缺失也不应让全平台登录失败；内部 JWT 可携带空部门，下游仅在需要 `home_dept` 的知识写路径按上述 mode 处理。这样不破坏非知识服务和 legacy/API-key 流量。

## 5. 兼容与迁移要求

1. schema 采用 expand → migrate → contract。兼容窗口保留旧 document relation/permission 以便回滚，但新 `view/share` 必须按锁定语义计算；兼容项不得成为长期替代设计。
2. 兼容窗口新文档临时双写 `home_dept` 与 `parent_space=<tenant>_default`；旧边只用于回滚，不进入新 `view`。
3. 旧 `public_viewer@user:*` 复制为 `public@user:*`；旧 direct `viewer@user` 原样保留。
4. 旧 `viewer@group#member` 与目标 `viewer:user` 不兼容。上线前必须盘点；若存在，需经显式 manifest 展开为个人 viewer 或由业务确认撤销，禁止静默保留/猜测。
5. 存量 `home_dept` 首选映射为“文档 owner 的唯一 direct Casdoor department”。`category` 不作为隐式映射。owner 缺失、owner 无部门或多部门进入异常清单，人工 manifest 后再写。
6. backfill 只 TOUCH/CREATE 新关系，不删除旧关系；重复运行第二次应为零差异，并逐文档读回恰好一个 `home_dept`。
7. dev 可跳过 backfill，但必须同时证明业务 registry 无文档且 SpiceDB 无 `document` 关系，记录检查结果；仅凭“应该没数据”不能跳过。

## 6. 边界条件和不变量

- department ID 与 document ID 都必须租户化。`CasdoorGroupIds.encode(org, group)` 与计划新增的 `KnowledgeResourceIds.department(project, deptId)` 必须产出相同 `<org>_<group>`。
- 父关系方向固定为 `child#parent@department:parent`；根部门无 parent；每个非根至多一个 parent；同步前检测环。
- 一次不完整分页、HTTP 非 2xx、响应解析错误、org owner 不匹配、重复 group ID、父节点不存在、环或一人多部门，均使该 org 本轮不写任何 DELETE。
- 删除阈值按 org 独立计算，不能让小 org 的异常被全局总量稀释，也不能因一个 org 失败阻断已验证的其他 org；每 org 的 diff 必须先完整计算再写。
- `home_dept` 在业务上是单值，但 SpiceDB relation 本身不强制基数；创建、backfill 和审计必须显式检查“恰好一个”。
- 同步为单写者；Java `synchronized` 只保护单 JVM，不等于集群单写。
- `FULLY_CONSISTENT` 读语义和 F3/core 严格响应校验不改。
- `space/folder` 继续作为独立授权对象存在；最终 document `view` 不再沿 `parent_space/parent_folder` 继承。两种方向相反，不得混入同一最终表达式。

## 7. 非目标

- 不改变目标 Zed schema 或重新设计 org/app/tenant。
- 不建设跨部门审批 workflow；“申请通过”即有 `share` 权限的用户调用现有单篇分享接口。
- 不把 category、角色名或 group 名约定成部门真相源。
- 不改变 `checkBulk` 协议、F3 严格校验或检索融合算法。
- 不承诺用分布式事务原子提交向量库、registry 和 SpiceDB；通过 fail-closed、幂等补偿和 reconcile 收敛。
- 本轮实施计划不物理删除 space/folder、旧 tuple、legacy 登录或 Casdoor 对象。

## 8. 歧义与实施阻断项

| ID | 待验证事项 | 验证方式 | 未通过时姿态 |
|---|---|---|---|
| V-01 | Casdoor group 父/子字段名、方向及完整路径形状 | 对本地 Casdoor 建 3 层树，保存 `/api/get-groups` 原始响应并对照 UI | 不实现 parent 映射，不猜字段 |
| V-02 | group/users API 的分页参数、总数/终止条件 | 用超过一页的测试 org 探活；验证末页和重复页 | 该 org 同步只读不删 |
| V-03 | department admin 的权威标记 | 检查 group/user/role 原始响应与管理操作后的 diff | 不写 admin；share 管理员路径不验收 |
| V-04 | token `groups` 是 direct group、是否含祖先、完整名还是短名 | PKCE 获取真实 token，保留脱敏 claim 样本 | 多值不选取，department 标 ambiguous |
| V-05 | 固定 SpiceDB 版本对递归深度和 schema 收缩的行为 | pin 当前镜像 digest，跑深度/性能/旧 tuple 收缩测试 | 不进 enforce/contract |
| V-06 | 锁定 schema 无 `edit`，但当前覆盖/删除仍判 `edit` | 见 `DocumentService.java:211-217,332-340` | 不把 `edit` 擅自改成 `share`；contract 阶段阻断，待业务确认 owner/edit 规则 |
| V-07 | 是否存在存量 document group viewer、editor/commenter、public_viewer | 全量 direct relationship inventory | 未迁移前不得收窄 document relation |

V-06 是真实代码与锁定模型的接口缺口，不是重新设计邀请。兼容窗口保留旧 `edit` 以维持现状；最终 contract 前必须由原设计负责人补充既有覆盖/删除操作的目标权限，否则保留兼容项且明确未达到纯目标 schema。

## 9. 验收标准

1. 三层树中，叶子文档对 owner、叶子成员、父成员、根成员为 allow；对平级、下级、旁支、其他租户为 deny。
2. `share` 对 owner、本部门普通成员、本部门 admin、祖先 admin 为 allow；对祖先普通成员、平级/下级成员及其他租户为 deny。
3. `viewer` grant/revoke 强一致立即生效；公共 relation 对任意 user 生效。
4. disabled 不调用 AP、不要求 department，上传、检索、列表、get/delete 的结果与当前基线一致。
5. shadow 不过滤读取，能区分 would-deny、department missing/ambiguous/unsynced 和依赖 error。
6. enforce 缺身份/关系时不泄漏正文；缺 department 的新建明确失败且不写伪造归属。
7. 多 org 分页同步重复两次零差异；单 org 拉取不完整或 DELETE 超阈值时该 org 零写入。
8. backfill 每篇目标文档恰好一个 `home_dept`，第二次 dry-run 零 diff；异常全部进入 manifest，不用 category 猜测。
9. 固定 SpiceDB 版本的递归深度和 `checkBulk` 压测不触及 5 秒 SDK 读超时，并满足上线前确定的 p95/p99 预算。
10. 灰度可从 enforce 退回 shadow/disabled；兼容窗口可恢复旧 schema/view 和旧应用，且不删除新旧 tuple。
