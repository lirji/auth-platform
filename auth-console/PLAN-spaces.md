# SpacesPage 实施计划 —「空间/知识库管理」页转正(v2)

> 由 frontend-plan 工作流产出:勘察现有前端 + 并行五方向只读子代理(需求/UIUX/架构/复用资产/测试风险)+ 综合 + 独立评审。
> 本计划是 [PLAN.md](./PLAN.md) 里「③空间管理(v1 合并进调试器,留 v2)」的 v2 转正,**遵循 PLAN.md 既定决策**(D8 全一致读、lexicon 单一来源、relation≠permission、复用优先、后端是权威边界)。
> **计划获批前不改任何代码。**

## 用户已拍板(本计划的前提)
1. **公开链接开关**:纳入本期;开启直接生效,**收回(关闭)走二次确认**(Popconfirm+danger)。
2. **space id 来源**:顶部手动输入(带租户前缀提示)+「我可管理的 {type}」快捷列表(复用 `lookupResources(我, manage/edit, type)`)。
3. **对象范围**:space + folder + document,同页切换资源类型(仍需手输对象 id,因无子对象枚举)。

---

## 1. Goals / Non-Goals

**Goals**
- 把 `src/pages/SpacesPage.tsx` 从占位卡转正为**以单个对象(space/folder/document)为中心的成员治理工作台**:选/输入对象 → 按角色(relation)分组展示直接成员 → 每个角色内增删成员(user / group#member)→ 公开链接开关(space/document)→ 同页判权自检(check + expand)。
- 与既有页面**差异化**:GrantsPage 是「原语视角」(选任意 type×relation 拼元组),SpacesPage 是「对象视角」(对象固定、按角色卡编排、语义化增删、公开开关、内嵌自检)。
- **最大化复用**:数据/hooks/组件/词典/交互约定全部沿用现有,净新增 1 个展示组件 + 重写 1 个页面。

**Non-Goals**
- 不做对象枚举/全局搜索(SpiceDB 无枚举 API;仅输入 + `lookupResources` 反查我可管理的)。
- 不做子对象下钻(在 space 页里管 folder/document 的树)——同样受枚举缺失限制,本期靠切换 type + 手输 id。
- 不做结构关系(`parent_org/parent_space/parent_folder`)的增删——层级由业务侧建对象时双写,沿用 PLAN.md 评审修正 #5。
- 不做用户/组 CRUD(归 Casdoor)。
- 不引入新依赖、不引入乐观更新、不改后端。

**关键约束(不可破坏)**
- 成员**权威源 = 直接关系元组** `listRelationships`(可精确 revoke),**不用** `lookupSubjects`(有 caveated/通配失真,仅作自检辅助)。这是对 PLAN.md 旧描述(§路由表 space 页主端点写的是 lookupSubjects)的**刷新**——因 `/admin/relationships` 现已落地(旧计划时它还在 P0-C 待补)。
- 读全部走 `fullyConsistent`(后端已锁死),写后 `invalidateQueries` 刷新,**不引 ZedToken 水位**(D8)。
- relation(可授予,写 grant)与 permission(可判定,check)词汇严格分开(lexicon 顶部红线)。

---

## 2. 路由与页面流

**路由/导航:已就绪,不改。** `/spaces` → `SpacesPage`(`src/router/routes.tsx:28`,`ProtectedRoute`+`AppLayout` 下),导航项在 `src/nav.tsx:26`(分组「资源与模型」,`DatabaseOutlined`)。权限 `viewer`(只读可进,写控件按 `isAdmin` 收敛)。

**选中态用 `useSearchParams`**(react-router 已在用):`/spaces?type=space&id=acme_kb`,可深链/分享/刷新不丢。

**页面流**
1. 进入 `/spaces` → 若无 query 参数:资源类型默认 `space`,成员区/自检区显示 `EmptyState`(提示输入对象 id)。
2. 选资源类型(space/folder/document)→ 输入对象 id + 点「加载」(或点「我可管理的 {type}」快捷列表里的某项自动回填 id 并加载)。
3. 加载 = `listRelationships(type, id)`(`useQuery`,key `['relationships', type, id]`,`enabled: !!id`)。
4. 结果按 relation 分组渲染角色卡;`public_viewer@user:*` 抽出驱动公开开关;结构关系 `parent_*` 过滤不显示。
5. 在某角色卡内增/删成员 → `useGrant`/`useRevoke` → 成功后 `invalidateQueries(key)` 刷新。
6. 切公开开关 → grant/revoke `type:id#public_viewer@user:*`(关闭走 Popconfirm)。
7. 右栏自检:填 user id + 选权限(`permissionsFor(type)`)→ `useCheck` + `useExpand` → `AllowDenyResult` + 判定路径树。

---

## 3. 组件树与文件级改动

```
SpacesPage (重写 src/pages/SpacesPage.tsx)
├─ PageHeader                              (复用 layout/PageHeader)
├─ 顶部控制卡 Card
│   ├─ ObjectTypeSelect(exclude 非资源类型,仅留 space/folder/document)   (复用 selects)
│   ├─ Input 对象 id(placeholder 提示租户前缀,如 acme_kb)                 (antd)
│   ├─ Button「加载成员」
│   ├─ Button「我可管理的 {type}」→ useLookupResources → 结果 RefBadge 可点回填  (复用 hook+RefBadge)
│   └─ 公开链接区(仅当 relationsFor(type) 含 public_viewer:space/document)
│        └─ Switch + <Tag gold>公开</Tag> + TupleText 预览 + secondary 说明   (antd + RefBadge)
├─ Row gutter=16
│   ├─ Col lg=15  成员区:memberRelations(type).map(rel =>
│   │       <SpaceMemberCard resourceType id relation members canWrite myId/>)  ← 新增组件
│   └─ Col lg=9   自检区 Card
│         ├─ Input user id +「用我自己」(authStore.userId)                  (复用 store)
│         ├─ PermissionSelect(resourceType)                                (复用 selects)
│         ├─ Button 运行 → useCheck + useExpand
│         ├─ AllowDenyResult                                               (复用 domain)
│         └─ 判定路径 Tree(expandToTree) .scroll-x                          (复用 expandTree)
└─ (加载态 Card loading / 失败 ErrorState / 空 EmptyState)                   (复用 AsyncState)

SpaceMemberCard (新增 src/components/domain/SpaceMemberCard.tsx)  —— 自包含的单角色卡
  props: { resourceType: ObjectType; resourceId: string; relation: string;
           members: Relationship[]; canWrite: boolean; myId?: string }
  ├─ 卡头:RelationTag(relation) + 计数                                     (复用 SemanticTag)
  ├─ List 成员:RefBadge(subject) + Popconfirm 移除
  │     · 移除 body 从元组逐字段派生(type/id/relation/subject/subjectRelation)—— R6
  │     · self-lockout:subject 为 myId 且 relation∈{owner,admin} 时 Popconfirm 文案加警告
  ├─ 添加行(canWrite 时):
  │     · owner 卡:固定 user,不渲染类型下拉(schema owner: user)
  │     · 其余卡:ObjectTypeSelect 限 user|group —— 注意 exclude 是黑名单,须反转白名单(见 §4 B2)
  │     · Input 主体 id
  │     · group 主体恒 subjectRelation='member'(不暴露开关;裸 group 被 SpiceDB schema 拒),旁附只读说明「以组成员身份」
  │     · Button 添加 → useGrant.mutate({...})
  └─ useQueryClient:写成功后 invalidateQueries(['relationships', resourceType, resourceId])
```

### 文件级改动清单
| 文件 | 改动 | 说明 |
|---|---|---|
| `src/pages/SpacesPage.tsx` | **重写** | 占位卡 → 实页编排(见组件树) |
| `src/components/domain/SpaceMemberCard.tsx` | **新增** | 自包含单角色卡(增删 + invalidate) |
| — 其余全部**不改** | — | routes/nav/main/config/client/authz.ts/useAuthz.ts/selects/RefBadge/AllowDenyResult/AsyncState/PageHeader/lexicon/expandTree/authStore 均直接复用 |

> **不新增依赖**(C1)、注释中文(C2)、相对路径 import 无 alias(C8)、`export default function SpacesPage`(C7)、`App.useApp()` 取 message(红线1)、危险操作 Popconfirm(红线2)、`humanizeError`(红线3)、写后 invalidate(红线4)、TupleText 等宽(红线5)。

---

## 4. 领域派生逻辑(纯前端,置于组件内或页内小工具)

```
STRUCTURAL = { parent_org, parent_space, parent_folder }         // 结构关系,排除出成员管理
memberRelations(type) = relationsFor(type).filter(r => !STRUCTURAL.has(r) && r !== 'public_viewer')
  · space    → [owner, admin, editor, commenter, viewer]
  · folder   → [editor, viewer]
  · document → [owner, editor, commenter, viewer]
hasPublicLink(type) = relationsFor(type).includes('public_viewer')  // space/document=true, folder=false
allowedSubjectTypes(relation) = relation === 'owner' ? ['user'] : ['user','group']   // 白名单,schema R7
// B2:ObjectTypeSelect 只吃黑名单 exclude,必须把白名单反转:
subjectExclude(relation) = OBJECT_TYPES.map(o=>o.value).filter(v => !allowedSubjectTypes(relation).includes(v))
// owner 卡直接固定 user、不渲染下拉(避免 exclude 剩单选项的别扭 UX)
isPublic(rels) = rels.some(r => r.relation==='public_viewer' && r.subject.type==='user' && r.subject.id==='*')
groupByRelation(rels) = rels 按 r.relation 分桶(useMemo;逻辑短,首版不抽 domain 文件)
LANDING_PERM = { space:'manage', folder:'edit', document:'edit' }   // 反查用权限;folder/document 无 manage
```

公开开关写入体:`{ resourceType:type, resourceId:id, relation:'public_viewer', subjectType:'user', subjectId:'*' }`(subjectRelation 省略)。开/关期间 `Switch loading + disabled=isPending`,避免派生态短暂显旧值。

> **group 恒 #member(B1)**:schema `admin/editor/commenter/viewer: user | group#member`(`knowledge.zed:22-24`),裸 `group:y` 不在允许主体内、WriteRelationships 会报错。故 SpacesPage **不复用** GrantsPage 的 userset 开关,group 主体一律 `#member`。
> **hook 调用形态(B3)**:`useGrant/useRevoke/useCheck/useExpand/useLookupResources` 均为**零参 hook**,统一 `const m = useXxx(); m.mutate({...对象参})`(照 `PlaygroundPage.tsx:28,42`),§7 里的位置参写法仅为示意。

---

## 5. 状态与边界(输入 → 期望)

| 状态/输入 | 期望行为 | 依据 |
|---|---|---|
| 空对象 id | 不发请求(`enabled:!!id`),EmptyState 提示 | GrantsPage:30 |
| 对象不存在 | `listRelationships` 返回 `[]` → 空态「无成员」,不报错(无法区分"不存在"与"零成员",枚举缺失固有二义) | 测试R;PLAN:139 |
| 加载中 | 成员区 Card `loading`;首屏可 PageSkeleton | GrantsPage:117 |
| 加载失败 | ErrorState + 重试(`humanizeError(err)` + `refetch`) | AsyncState:11 |
| 写入中 | 对应按钮/Switch `loading=isPending` | GrantsPage:24,109 |
| 写成功 | `message.success` + `invalidateQueries(key)` | GrantsPage:42,49 |
| 添加参数缺失 | 提交前 `message.warning` | GrantsPage:45 |
| group 主体 | **恒 `subjectRelation='member'`,不暴露开关**;裸 group 被 SpiceDB schema 拒(B1) | knowledge.zed:22-24 |
| 移除元组 | body 从列表项逐字段派生,精确 DELETE | R6;GrantsPage:130-142 |
| owner 授 group / public_viewer 授具体 user | UI 前置禁止(owner 卡固定 user;public 走开关) | R7;knowledge.zed:21,25 |
| public 开关 | 状态由 `isPublic(rels)` 派生(非本地 state);开=grant、关=Popconfirm 确认后 revoke `@user:*`;pending 期 Switch disabled | R4 |
| self-lockout(撤自己 owner/admin) | Popconfirm 文案追加警告;判定须带 `subject.type==='user' && subject.id===myId`(后端无自锁保护) | 测试;AdminController:47-54 |
| 只读用户(viewer,非 admin) | `canWrite=isAdmin(authorities)`:写控件禁用 + 提示,读/自检照常(后端仍 403 兜底) | 测试;authStore:20 |
| 重复授予 | TOUCH 幂等,列表无重复 | AdminController:37 |
| 401 token 过期 | axios 单飞静默续期,页面无感 | client:22-48 |
| 网络错误/超时 | `humanizeError`→「网络错误」,列表走 error 态 | useAuthz:36 |
| 大成员列表 | 当前单对象成员量小,不分页;若异常大仅展示不截断(标注为已知局限) | R2 |

**假设(用户未单独指定,采下述默认,可批时调整)**:
- A1 viewer 写控件默认「禁用 + tooltip」而非隐藏(让其看到功能存在)。
- A2 self-lockout 仅二次确认警告,不硬禁止。
- A3 对象 id 直接输原始带租户前缀 id(对齐 PLAN.md 待澄清 #2 的默认)。
- A4 「我可管理的 {type}」为按钮触发的 `lookupResources`,非进页自动拉(避免意外请求)。

---

## 6. API 契约(全部已就绪,零后端改动)

| 用途 | 前端 API(`src/api/authz.ts`) | 后端 |
|---|---|---|
| 列成员(权威源) | `listRelationships(type, id) → Relationship[]` | `GET /admin/relationships?resourceType&resourceId` |
| 增成员 / 开公开 | `grant(GrantBody) → {token}` | `POST /admin/grants`(TOUCH) |
| 删成员 / 关公开 | `revoke(GrantBody) → {token}` | `POST /admin/grants/revoke`(DELETE 单条) |
| 自检判定 | `check(CheckBody) → {allowed}` | `POST /admin/check`(full) |
| 判定路径 | `expand(type,id,permission)` | `POST /admin/expand` |
| 我可管理的 | `lookupResources(user, myId, perm, type) → {resourceIds}` | `GET /admin/subjects/user/{id}/resources` |

类型全复用:`Relationship`/`GrantBody`/`CheckBody`(`authz.ts`)、`ObjectType`(`lexicon.ts`)。**不需新增类型/hook/API**。

---

## 7. 实施步骤(按依赖排序)

1. **SpaceMemberCard 组件**(`src/components/domain/SpaceMemberCard.tsx`):卡头 RelationTag + 成员 List + Popconfirm 移除(元组派生 body + self-lockout 文案)+ 添加行(subjectType 约束 + group #member + useGrant)+ 自包含 invalidate。先做它,页面才有卡可放。
2. **SpacesPage 骨架**:PageHeader + 顶部控制卡(ObjectTypeSelect 限三类 + id Input + 加载按钮)+ `useSearchParams` 选中态 + `useQuery` 列成员 + 四态(loading/empty/error/success)。
3. **成员区**:`memberRelations(type).map` 渲染 SpaceMemberCard,按 relation 分桶传 members。
4. **公开开关**:仅 `hasPublicLink(type)` 时渲染;`isPublic` 派生态;开=grant、关=Popconfirm→revoke;写后 invalidate。
5. **快捷列表**:按钮触发 `useLookupResources().mutate({subjectType:'user', subjectId:myId, permission:LANDING_PERM[type], resourceType:type})`,结果 RefBadge 可点回填 id。标签按 type 措辞:space=「我可管理的空间」、folder/document=「我可编辑的{type}」(folder/document 无 manage,用 edit)。**语义提示**:此列表按 ReBAC 权限反查(某人在图里是 owner/admin/editor),与写操作的全局 `authz-admin` 门控是两条轴——平台管理员若在 ReBAC 图里不是任何对象的成员,列表可能为空,属正常;快捷列表定位为"便利入口"而非唯一入口,手输始终可用。
6. **自检区**:PermissionSelect(type) + user id(「用我自己」authStore.userId)+ useCheck/useExpand + AllowDenyResult + Tree(expandToTree,.scroll-x)。
7. **viewer gating**:`canWrite=isAdmin(authorities)` 贯穿写控件禁用 + 顶部只读提示 Alert(非 admin)。
8. **自测**:见 §8。

---

## 8. 测试策略(务实,零前端测试基建现状下)

- **P0 手动冒烟(必做)**:起 `deploy` 全栈 + admin:8201 + 前端 dev:5273;用 `spicedb-smoke.sh` 风格的独立 id(如 `smk_space`)走:输入 space id → 分组列成员 → 加 viewer(user)→ 出现 → 移除 → 消失;加 editor(group#member)→ 出现 `group:x#member`;公开开关 on/off → `public_viewer@user:*` 元组随之出现/消失;切 document 复验;自检 check/expand 与列表对照。核对页面结果与 `curl GET /admin/relationships` 一致。
- **P1 纯函数单测(可选,若愿装 Vitest)**:`memberRelations(type)`、`groupByRelation`、`isPublic`、`allowedSubjectTypes`、元组→revoke body 派生。这些无 IO,性价比最高。**本期不强制搭 Vitest**(仓库当前零前端测试基建,不为一页搭全套)。
- **不做**:MSW 组件测试 / Playwright E2E(维护预期不足以支撑,PLAN.md 已列为 gated)。
- **构建校验**:`pnpm build`(= `tsc && vite build`)必须通过(TS 严格模式)。

---

## 9. 验收标准

- [ ] `/spaces?type=space&id=<id>` 深链直达并加载该对象成员;刷新不丢选中。
- [ ] 成员按角色(owner/admin/editor/commenter/viewer)分组展示;`parent_*` 不出现;结果与 `/admin/relationships` 一致。
- [ ] 切 folder → 仅 editor/viewer 两卡、无公开开关;切 document → owner/editor/commenter/viewer + 公开开关。
- [ ] 加 user 成员、加 group(自动 #member)成员、移除成员均即时反映;元组预览正确。
- [ ] 公开开关:开启写入 `#public_viewer@user:*`;关闭弹二次确认后 revoke;开关态由列表派生正确。
- [ ] 自检:allow/deny 大卡正确;有 expand 时展示判定路径树。
- [ ] 「我可管理的 {type}」列出并可点回填。
- [ ] 非 admin(viewer)进入:写控件禁用 + 只读提示;写端点后端 403 有友好提示。
- [ ] self-lockout(撤自己 owner/admin)有二次确认警告。
- [ ] `pnpm build` 通过。

---

## 10. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 移除元组字段不精确 → 静默无效/误删(R6) | revoke body 一律从列表项派生,不重拼;group 带回 `subject.relation` |
| public_viewer 通配删不掉(R4) | 用列表读回的 `subject.id='*'` 原样回传 revoke |
| group 忘带 #member(R5) | 添加时按 subjectType 自动置 member |
| lookupSubjects 失真被误当权威(R9) | 成员权威源用 readRelationships;lookupSubjects 不用于成员列表 |
| 对象 id 缺租户前缀 → 写到错对象 | placeholder 提示;本期不做业务名解析(A3) |
| 公开链接影响面大 | 收回二次确认(用户决策);仅 admin 可操作(canWrite) |
| self-lockout | 二次确认警告(A2) |

**回滚(强)**:纯前端,净改动 = 1 重写 + 1 新增文件,后端零改动。回滚 = `git checkout` 这两个文件回占位符,导航/路由入口保留即可(页面自身降级回 EmptyState)。已上线的 server:8200 / sdk / core / GrantsPage 完全不受影响。

---

## 11. 独立评审结论(已完成)

评审逐条对照源码,判定:**可据以实施,无架构级返工**。全部技术断言(关系集、主体约束、API 签名、hook 类型、authStore 字段、queryKey 共享、路由/导航已注册、后端契约)核实为真。已回填的三处阻断修正:
- **B1**:group 恒 `#member`、去掉 userset 开关(裸 group 被 schema 拒)。→ 已并入 §3/§4/§5。
- **B2**:`allowedSubjectTypes` 白名单 → `ObjectTypeSelect.exclude` 黑名单须反转;owner 卡固定 user。→ 已并入 §4。
- **B3**:零参 hook 用 `.mutate({对象})` 形态。→ 已并入 §4/§7。

已采纳的非阻断改进:快捷列表按 type 措辞 + 两条轴语义提示(§7);self-lockout 判定加 `subject.type==='user'`(§5);公开 Switch pending 期 disabled(§4/§5)。评审确认:SpaceMemberCard 抽离与 `useSearchParams` 均非过度设计;**不需**改 `selects.tsx`(每卡固定 relation 的添加行规避了 RelationSelect override)。
