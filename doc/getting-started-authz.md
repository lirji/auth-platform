# 新手上手：如何建立用户 / 组织 / 权限关系

面向**第一次接触本平台**的开发者。目标：把"我该怎么建用户、建组织、赋权限"这件事讲清楚。
内容全部来自仓库现有代码与冒烟脚本，非规划态。

> 定位：纯授权平台。**Casdoor 管身份/登录，SpiceDB 管 Zanzibar/ReBAC 授权**。
> 若你在找 `User` 实体 / `Role` 表 / `user_role` 中间表——**它们在这里不存在**。先读下面第 1 节换脑子。

---

## 1. 先换脑子：这里没有"表"，只有"关系元组"

本平台是 **ReBAC**（Relationship-Based Access Control，Google Zanzibar 模型），不是经典 RBAC。
它把两件事拆开：

| 你想做的事 | 归谁管 | 在哪 |
|---|---|---|
| 建**用户**、登录、SSO | **Casdoor**（外部服务，:8000） | 本仓库**没有**建用户代码 |
| 建**组织/组/空间**、**赋权限/角色** | **SpiceDB**（ReBAC） | 全是"关系元组"，无 SQL 表、无 JPA 实体 |

由此推出三条反直觉但关键的事实：

- **没有** `POST /users`、`POST /organizations` 这类 CRUD 接口。
- **没有** `User/Role/Permission` 的 `@Entity`（全仓 grep `@Entity`/`CREATE TABLE` 为空）。
- 所有授权数据只有一种形态——**一条边（关系元组）**：

```
资源:id  # relation  @  主体:id
organization:acme # admin     @  user:alice        ← "alice 是 acme 的管理员"
```

**"建立关系" = 往 SpiceDB 写一条这样的边。** 组织、空间等对象**无需预先创建**：
你写第一条引用 `organization:acme` 的边时，SpiceDB 就把该对象"物化"出来了。
新手常见的卡点，正是在找"建表 / 建实体"的入口——但这里根本没有那一步。

---

## 2. 模型长什么样

对象类型及其可挂关系，全定义在一个文件：
`auth-platform-core/src/main/resources/schemas/knowledge.zed`

```
user          空定义,就是个主体
group         relation member          （组,可嵌套 → 承载"团队 / 角色"）
organization  relation admin, member   （= 租户 / 公司）
space         relation parent_org, owner, admin, editor, commenter, viewer
              permission manage = owner + admin + parent_org->administrate
              permission edit   = editor + manage        ← 权限是"算"出来的
folder        relation parent_space, parent_folder, editor, viewer
              permission edit = editor + parent_folder->edit + parent_space->edit  ← 沿边向下继承
department    relation parent, member, admin              （部门树,由 Casdoor 嵌套 group 同步）
              permission doc_reader = member + parent->doc_reader   ← 沿部门树向上传播
document      relation home_dept, owner, viewer, public    （部门层级模型,取代旧 D3）
              permission view = owner + viewer + public + home_dept->doc_reader
              permission edit = owner                      ← 删除/覆盖仅上传人
```

> **注意**：`document` 现走**部门层级隔离**（可读者 = 本部门 + 所有上级部门，`edit` 仅 owner），完整规则见
> [`../docs/authz-department-model.md`](../docs/authz-department-model.md)。下面第 3 节用 `space`/`folder` 演示
> ReBAC 的"组→角色→沿边继承"心法（folder 仍向下继承，机制最直观）；文档级共享则是往 `document#viewer` 接一条边。

区分两个词，是理解全模型的关键：

| 概念 | 谁负责 | 说明 |
|---|---|---|
| `relation` | **你手动写的边** | 如 `editor`、`member`、`parent_org`。写一条 grant = 接一条边 |
| `permission` | **引擎自动推导** | 如 `space.edit = editor + manage`。你不用写,引擎沿边算出来 |

例：`space.edit = editor + manage`，意为"只要你是 editor，或 owner/admin，或所属组织的管理员，就有 edit"。
这套推导由 SpiceDB 完成，你只负责把 `editor` 这条边接上。

> **RBAC 在这里是"特例"**：所谓"给某人研发编辑者角色"，就是把 `group:research` 绑到 `space` 的 `editor`
> relation。角色 = 一个 group + 一条边，没有独立的 `Role` 概念。

---

## 3. 手把手：建一套完整关系（含真实请求体）

赋权唯一入口是 `AdminController.grant`（`auth-platform-admin/.../AdminController.java:37`）：

**`POST http://localhost:8201/admin/grants`**，请求体 `GrantRequest`（`AdminDtos.java:12`）：

```
{ resourceType, resourceId, relation, subjectType, subjectId, subjectRelation }
```

> `subjectRelation` 只有当主体是"某个组的全体成员"时才填（填 `"member"`）；指向单个用户时留空/`null`。

下面用 `space`/`folder` 的向下继承演示"组→角色→资源"的关系链（这套继承在当前 schema 里仍然成立）。
目标：**让 bob 因为在研发组、研发组是知识库的编辑者，从而能编辑库里的文件夹——全程不给 bob 直接授权。**

**① 设 alice 为组织 acme 的管理员**　边：`organization:acme#admin@user:alice`
```json
{ "resourceType":"organization", "resourceId":"acme", "relation":"admin",
  "subjectType":"user", "subjectId":"alice", "subjectRelation":null }
```

**② 把 bob 加进研发组**　边：`group:research#member@user:bob`
```json
{ "resourceType":"group", "resourceId":"research", "relation":"member",
  "subjectType":"user", "subjectId":"bob", "subjectRelation":null }
```

**③ 知识库 space 归属组织 acme**　边：`space:kb_ml#parent_org@organization:acme`
```json
{ "resourceType":"space", "resourceId":"kb_ml", "relation":"parent_org",
  "subjectType":"organization", "subjectId":"acme", "subjectRelation":null }
```

**④ 把"研发组全体成员"绑成该空间的 editor**（= 赋"编辑者角色"，注意 `subjectRelation`）
　边：`space:kb_ml#editor@group:research#member`
```json
{ "resourceType":"space", "resourceId":"kb_ml", "relation":"editor",
  "subjectType":"group", "subjectId":"research", "subjectRelation":"member" }
```

**⑤ 文件夹挂到空间下**　边：`folder:f_42#parent_space@space:kb_ml`
```json
{ "resourceType":"folder", "resourceId":"f_42", "relation":"parent_space",
  "subjectType":"space", "subjectId":"kb_ml", "subjectRelation":null }
```

至此没给 bob 直接授过任何文件夹权限，但引擎会推导出这条链：

```
bob ∈ research#member  →  research 是 kb_ml 的 editor  →  kb_ml.edit
      →  folder:f_42 继承 parent_space->edit  →  bob 可编辑 f_42 ✅
```

这就是 ReBAC 的威力，也是你要建立的"关系链"。

---

## 4. 怎么验证建对了

**判权调试器**（`AdminController.java:83`）：
```json
POST /admin/check
{ "subjectType":"user", "subjectId":"bob", "permission":"edit",
  "resourceType":"folder", "resourceId":"f_42" }
→ { "allowed": true }
```

**反查 / 看现有边 / 解释判定**（均在 `AdminController`）：

| 接口 | 作用 |
|---|---|
| `GET /admin/resources/folder/f_42/subjects?permission=view` | 谁能看这个文件夹（反查主体） |
| `GET /admin/subjects/user/bob/resources?permission=view&resourceType=folder` | bob 能看哪些文件夹（反查资源） |
| `GET /admin/relationships?resourceType=space&resourceId=kb_ml` | kb_ml 现存哪些边 |
| `POST /admin/expand` | 展开判定树,肉眼看权限怎么一层层推出来 |

**跑现成的端到端例子**（最快建立直觉，强烈建议先跑一遍）：
```bash
cd deploy
docker compose up -d          # 起 postgres + spicedb + casdoor
bash spicedb-smoke.sh         # 灌 schema + 写上面这套边 + 断言判定
bash server-smoke.sh          # 经 REST(:8200) 复验同一条链路 ← 最贴近"用 API 赋权"
```
`spicedb-smoke.sh` 覆盖两类继承的断言（space/folder 向下继承 + document 部门模型向上传播）；
`server-smoke.sh` 是走 HTTP 的版本，看一遍即全懂。更完整的部门模型 seed/自校验：
`TENANT=demo APPLY=1 bash deploy/dept-authz-fixture.sh`。

---

## 5. 一定会踩的点

1. **"用户"从哪来？** 生产上不是手写 `alice`，而是在 **Casdoor** 建用户，登录后拿到的 `sub`(UUID)
   才是真实主体 id。Casdoor 里的组成员由 `GroupSyncService`（`casdoor/GroupSyncService.java:53`）批量同步成
   `group:<租户>_<组>#member@user:<sub>` 的边——**即上面第 ② 步的生产版**。手工 `POST /admin/grants`
   主要用于组织/空间的角色绑定与调试。

2. **对象 id 要带租户前缀**（`knowledge.zed:2`）：真实环境用 `acme_kb_ml`、`acme_d_42` 这种
   `<tenantId>_<id>`，防止不同租户同名对象在 SpiceDB 里串权。`CasdoorGroupIds` 会强制给 group 加前缀。

3. **写完立刻读可能读不到**（一致性水位）：admin 读接口默认 `FULLY_CONSISTENT` 已规避；自己写脚本连
   SpiceDB 时，写后读要带上返回的 ZedToken 或用 full 一致性。详见 [databases.md](./databases.md) 一致性一节。

4. **`/admin/*` 需要 `authz-admin` 权限**（`SecurityConfig`）：dev 环境先在 Casdoor 把自己加进
   `built-in/authz-admin` 组。

---

## 一句话总结

在本框架里，"建用户/组织/赋权"**不是** INSERT 一行记录，而是 `POST /admin/grants` 连一条边
`资源#关系@主体`；权限由引擎沿边自动推导，你只管把关系链接对。

> 延伸阅读：[databases.md](./databases.md)（数据模型与一致性）、[components.md](./components.md)（组件清单）、
> `../docs/统一登录平台接入手册.md`（SSO 接入 + `@CheckAccess` 声明式判权）。
