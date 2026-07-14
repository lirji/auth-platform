# auth-console 页面布局与视觉重设计最终方案

> 状态：可交付执行。本文只规划前端重设计，不包含本轮代码修改。所有事实基于当前 `auth-console` 仓库；未从前端代码确认的事项标为“待验证/待确认”。

## 1. 背景

`auth-console` 已完成 M1–M6，技术栈为 React 18 + Vite + TypeScript + Ant Design v5 + React Query + Zustand + oidc-client-ts。现有 7 个业务页均可路由访问，授权 API、Casdoor SSO、领域 lexicon、授予/调试核心流已经落地。

当前体验问题集中在呈现层：`AppLayout` 只有深色 Sider、白色 Header 和 16px Content；`ConfigProvider` 没有 theme；`global.css` 只有根高度；页面大量使用固定宽度、行内 style、Card/Space 堆叠，缺少明确层级、响应式网格和统一状态反馈。授予与权限调试器是差异化核心能力，却没有获得足够的导航与结果区视觉权重。

## 2. 目标与非目标

### 2.1 目标

1. 建立一致的应用壳层：分组 Sider、全局 Header、面包屑、页面标题、最大内容宽与响应式边距。
2. 用 Antd `ConfigProvider theme` 建立克制的设计 token，不引入重型 UI 库。
3. 把 `/grants` 与 `/playground` 设计为任务型双栏工作台，保留其真实 state/API 调用链。
4. 为 Schema 类型网格、Audit 表格、Sync 单任务页、Spaces 占位建立明确骨架。
5. 统一 idle/loading/empty/error/success 和危险操作反馈。
6. 通过文字、前缀/图标和 Tag 样式区分 relation 与 permission，同时保留 `lexicon.ts` 的标签与配色来源。
7. 建立可执行的组件边界、测试策略、灰度与回滚步骤。

### 2.2 非目标

- 不修改业务规则、API 路径/请求响应、数据库、后端、OIDC、React Query/Zustand 选型。
- 不做用户/组织/身份/组 CRUD，不新增空间 CRUD。
- 不新增 Schema 编辑、关系图重型库、审计筛选/导出、服务端分页、全局搜索。
- 不改变现有 7 个业务 path，不引入 URL 查询上下文或 relation→permission 自动映射。
- 不实现暗色主题；只保证 token 结构可扩展。

## 3. 已确认的业务规则

1. relation 是可写入/撤销的关系，permission 是 check/lookup 的可判定权限，两者不能混用。
2. 元组预览格式为 `resourceType:resourceId#relation@subjectType:subjectId[#subjectRelation]`。
3. Grants 资源当前排除 `user`；主体当前只开放 `user/group/organization`。
4. group/organization 在 userset 开启时使用 `subjectRelation='member'`；列表撤销必须原样保留后端返回的 `subject.relation`。
5. `resourceType` 变化时清空 relation；Playground 的 `resourceType` 变化时清空 permission。
6. grant/revoke 成功后失效当前 `['relationships', resourceType, resourceId]`。
7. Playground 有 check、反查资源、反查主体三模式；check 与 expand 是两个并行请求，expand 是 best-effort 解释。
8. check 的 `allowed` 是 ALLOW/DENY 展示依据；expand 失败不能把 check 成功改成整体失败。
9. lookupSubjects 当前有 caveated 命中未过滤风险，提示必须保留。
10. Schema 从 `/admin/schema` 获取，`parseZed` 是轻量解析器；解析为空要允许查看 raw。
11. Audit 当前请求最多 200 条，前端 pageSize 20，展示 `at/actor/action/detail`。
12. `ProtectedRoute` 要求 `authz-viewer` 或 `authz-admin`；当前菜单和业务路由没有逐页 admin gate。

## 4. 当前代码与调用链分析

### 4.1 应用链路

```text
main.tsx
  → ConfigProvider(当前仅 zhCN)
  → Antd App
  → QueryClientProvider
  → AppAuthProvider(AuthProvider + AuthBridge)
  → RouterProvider
  → ProtectedRoute
  → AppLayout
  → Outlet → 7 pages
```

`AuthBridge` 把 OIDC profile 和 `groupsFromToken(access_token)` 同步到 `authStore`；`apiClient` 从同一 OIDC userStore 取 access token，401 时执行单飞 silent renew。重设计不碰这条安全链。

### 4.2 核心业务链路

```text
GrantsPage.submit
  → useGrant/useRevoke
  → api.grant/api.revoke
  → POST /admin/grants[/revoke]
  → message + GrantsPage.afterWrite
  → invalidate relationships query

PlaygroundPage.run
  ├─ check: useCheck + useExpand
  ├─ resources: useLookupResources
  └─ subjects: useLookupSubjects

SchemaViewerPage
  → GET /admin/schema → parseZed → cards/raw

AuditPage → GET /admin/audit?limit=200 → Table
IdentitySyncPage → POST /admin/casdoor/sync → groups/added/removed
```

### 4.3 可复用资产

- 完整保留：`src/api/authz.ts`、`src/api/client.ts`、`src/hooks/useAuthz.ts`、`src/auth/*`、`src/store/authStore.ts`。
- 领域单一来源：`OBJECT_TYPES/RELATIONS/PERMISSIONS`、`relationsFor/permissionsFor`。
- 转换逻辑：`parseZed`、`expandToTree`。
- 可演进原子组件：三个 Select、`RefBadge`、`TupleText`。

### 4.4 当前测试能力

仓库没有测试脚本、测试依赖或测试文件。`README.md` 的历史构建/API 验证记录不能替代可重复自动化测试。实施需先建立最小 Vitest/RTL/MSW 基线，Playwright 接 CI 能力待验证。

## 5. 候选方案对比与评分

评分 1–5，5 为最优；复杂度、测试难度、回滚成本的 5 分分别代表低复杂度、易测试、低回滚成本。权重：正确性 25%、改动风险 20%、复杂度 10%、可维护性 15%、扩展性 10%、测试难度 10%、回滚成本 10%。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 加权 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 渐进视觉整顿 | 4.2 | 4.7 | 4.8 | 3.0 | 2.8 | 4.0 | 4.8 | 4.08 |
| B 任务型工作台 | 4.6 | 4.0 | 3.7 | 4.6 | 4.4 | 3.8 | 4.0 | **4.23** |
| C 上下文权限工作台 | 3.6 | 2.7 | 2.3 | 4.3 | 4.9 | 2.2 | 2.5 | 3.28 |

- A 最稳、最快，但保留 Grants/Playground 大组件，后续会再次拆分。
- B 让页面容器保留真实请求和状态，呈现面板可测试，适合核心页继续增长。
- C 的联动能力最强，但引入 URL 契约、ID 暴露和 relation→permission 歧义，超出本期视觉重设计范围。

## 6. 最终方案及选择原因

采用 **B 为主体、A 为实施策略**：

- 用 B 的任务型信息架构与“页面容器编排 / 子面板呈现”边界。
- 用 A 的低风险分期：先 theme/shell/common primitives，再逐页替换；不改变请求时机、query key、handler 或 API。
- 仅吸收 C 的“本次输入摘要”概念；不做 URL 状态、共享 Inspector 或自动映射。

选择原因：它在核心页长期维护性和当前改动风险之间最均衡；无需引入新业务状态层，也不会把纯视觉任务演变为路由产品重构。

### 已知弱点

1. 新增文件与 props 契约较多，首轮开发成本高于简单换肤。
2. 页面容器与呈现面板拆分如果控制不好，会出现重复 state 或过度 props drilling。
3. 不解决刷新后表单恢复、授予后跨页自动带参。
4. 当前没有测试基础设施，建立基线本身占用交付时间。
5. viewer 的写页面呈现、撤销二次确认、Spaces 导航仍需产品决策。

## 7. 设计规范

### 7.1 壳层

- 桌面 Sider 224px，折叠 72px；Header 56px；内容最大宽 1440px，居中。
- Sider 从当前 dark 改为 light：`#FFFFFF` 背景、右侧 `#E6EAF0` 边框；选中项使用 `#EEF3FF` 浅蓝底、`#315EFB` 文本与左侧强调线。Header 使用白底与底边框，不叠加阴影。
- 内容背景 `#F5F7FA`，页面桌面 padding 24px、移动 16px。
- Header 左侧为折叠控制 + breadcrumb，右侧保留账户菜单；页面 title/description/extra 放在 `PageHeader`。
- 导航按“工作台 / 核心操作 / 资源与模型 / 系统治理”分组，核心两页优先；path 不变。
- `<992px` 侧栏改 Drawer；核心双栏在 `<992px` 变单栏。

### 7.2 token

`src/theme/theme.ts` 拟导出 `appTheme: ThemeConfig`：

| token | 值 |
|---|---|
| colorPrimary / colorInfo | `#315EFB` |
| colorSuccess | `#16A36A` |
| colorWarning | `#D97706` |
| colorError | `#D92D20` |
| colorBgLayout | `#F5F7FA` |
| colorBgContainer | `#FFFFFF` |
| colorText | `#172033` |
| colorTextSecondary | `#667085` |
| colorBorderSecondary | `#E6EAF0` |
| borderRadius / borderRadiusLG | `8 / 12` |
| fontSize / controlHeight / controlHeightLG | `14 / 36 / 40` |

间距使用 4px 基线：4、8、12、16、24、32、40、48。普通卡片使用细边框，浮层才使用阴影。具体对象/relation/permission 色彩继续读取 lexicon。

`appTheme.components` 至少约束：Layout 的 `headerBg/siderBg/bodyBg`，Menu 的 `itemSelectedBg/itemSelectedColor/itemBorderRadius`，Table 的 `headerBg/headerColor`，Card 的 `headerBg`。具体字段必须以当前安装的 Ant Design 5.21 类型定义为准；若某 component token 不存在，使用自有容器样式实现，不通过深层 `.ant-*` 覆盖硬凿。

### 7.3 relation / permission

- 拟新增 `RelationTag`：`R` 前缀或连接图标 + 描边样式 + lexicon 色。
- 拟新增 `PermissionTag`：`P` 前缀或盾牌图标 + 浅填充样式 + lexicon 色。
- 固定文案分别为“关系（可授予）”“权限（可判定）”。
- 禁止仅用绿色/蓝色区分；无障碍名称必须包含 relation/permission 类别。

### 7.4 统一反馈

- 首次加载：Skeleton；局部刷新：保留旧内容并显示局部 spinning。
- idle：提示下一步；empty：明确“查询完成但无匹配”，两者不能共用文案。
- error：Alert/Result + `humanizeError` + retry；写成功继续用 `message.success`。
- 撤销保持 danger；是否加 Popconfirm 待确认。
- check 与 expand 分区；expand error 只影响解释面板。

### 7.5 组件规范

| 组件 | 规范 | 禁止事项 |
|---|---|---|
| Button | 页面只设一个 primary 主动作；默认 36px，核心表单主动作 40px；撤销用 danger | 不用整块红底营造 revoke 模式，不用颜色替代文案 |
| Card/Surface | 12px 圆角、1px 次级边框、16–24px 内边距；普通内容无阴影 | 不层层嵌套 Card；不靠阴影制造所有层级 |
| Form field | label 在控件上方，label→control 8px，同组 16px、跨段 24px；必要说明紧随控件 | 不在同页混用多套 label 对齐；不把 placeholder 当唯一标签 |
| Select/Input | 默认 36px，核心表单 40px；类型 Select 与 ID Input 在桌面可 Compact，手机纵排 | 不截断已选 ID；错误态不能只改边框颜色 |
| Segmented | 只切换同一任务的少量模式；核心页 block，模式切换不自动提交 | 不承担一级导航 |
| Tag/Badge | 对象沿用 `OBJECT_TYPES`；relation/permission 用 SemanticTag；等宽内容可换行 | 不新建页面内颜色映射，不只靠色相区分类别 |
| Table/List | 表头 40px、行高约 44–48px；刷新保留旧数据；长 detail 局部处理 | 无后端支持时不展示虚假 total/filter/sort |
| Tree/Code | 等宽、长内容局部横向滚动，容器有边界；Tree 默认展开行为保留 | 不让内容触发页面级横向滚动 |
| Empty/Skeleton/Alert | idle 与 empty 文案分开；Skeleton 尽量匹配最终骨架；错误给 retry | 不以全屏 Spin 替代所有加载，不用 toast 承载持久风险 |

## 8. 页面骨架

### Overview

顶部突出 Grants/Playground 两个主任务卡；Schema 为次级入口。身份信息变成紧凑侧区，viewer Alert 保留；规模统计缺口降为弱提示，不构造假 KPI。

### Grants（7:5）

- 左：mode + 资源 / relation / 主体 / 元组确认四段。
- 右：当前资源标题、现存授予的 idle/loading/error/empty/data 五态。
- `GrantsPage` 继续持有 state/query/mutations 和 `submit/afterWrite/onResourceType`；子面板只收 props/emit event。
- 长 ID/tuple 允许换行；列表撤销原样传 `subject.relation`。

### Playground（5:7）

- 左：三模式 Segmented 与动态表单；保留“用我自己”。
- 右：最小高度 360px；idle、错误、ALLOW/DENY、反查列表各自明确。
- check 结果上方显示非纯颜色 banner；下方显示本次输入摘要，再显示独立 expand 路径区。
- caveated 风险提示紧邻 lookupSubjects 响应。

### SchemaViewer

保留 cards/raw；cards 使用 1/2/3 列响应式网格，`SchemaTypeCard` 内固定 relation/permission 两区；解析为空显示引导查看 raw。raw 使用可滚动 code panel。

### Audit

页面 toolbar + Table surface；保留 4 列、limit 200、pageSize 20 和手动刷新。无服务端总数、筛选、排序、导出。长 detail 局部换行/省略方案在视觉稿中确认。

### IdentitySync / Spaces

Sync 使用 640–720px 聚焦面板，分说明/动作/本次响应；不虚构历史。Spaces 用正式“规划中”空态；是否从导航隐藏待确认。

## 9. 精确到文件 / 函数 / 组件的修改清单

下列“拟新增”名称是执行契约；当前仓库尚不存在，实施 Agent 应按此创建或在同等职责下记录偏差。

### 9.1 现有文件

| 文件 | 现有函数/导出 | 精确修改 |
|---|---|---|
| `src/main.tsx` | 顶层 `render` | `ConfigProvider` 增加 `theme={appTheme}`；Provider 顺序不变 |
| `src/styles/global.css` | 全局样式 | 增加 reset、CSS variables、app shell/content/mono/overflow 基线；不深度覆盖 `.ant-*` |
| `src/components/layout/AppLayout.tsx` | `AppLayout` | 增加受控折叠、移动 Drawer、分组 Menu、breadcrumb、最大宽容器；保留 `useAuth` 登出、username、Outlet 与 path navigation |
| `src/nav.tsx` | `NavItem`, `NAV` | 在保留 7 个 path/label/icon 的前提下增加 group、description、可选 access/badge 元数据；不得成为前端安全边界 |
| `src/pages/OverviewPage.tsx` | `QuickCard`, `OverviewPage` | `QuickCard` 改为可复用任务卡呈现；重排任务/身份/提示，authStore 读取不变 |
| `src/pages/GrantsPage.tsx` | `GrantsPage`, `submit`, `afterWrite`, `onResourceType` | 保留 state 与三个方法语义；将表单和列表渲染委托给拟新增面板；不得改变 payload/query key/invalidate |
| `src/pages/PlaygroundPage.tsx` | `PlaygroundPage`, `run` | 保留三模式和 run 分支；委托 QueryPanel/ResultPanel；显式传 check/expand 独立状态；不改 API 并行关系 |
| `src/pages/SchemaViewerPage.tsx` | `SchemaViewerPage`, `typeColor` | 使用统一 PageHeader/状态原语/SchemaTypeCard；增加 parse 为空 fallback；query key/path 不变 |
| `src/pages/AuditPage.tsx` | `AuditPage`, `actionColor` | 重排 toolbar/Table，增加 error/empty；保持 `audit(200)`、4 列、pageSize 20 |
| `src/pages/IdentitySyncPage.tsx` | `IdentitySyncPage` | 重排单任务面板和四态；保持 `useCasdoorSync` 与响应字段 |
| `src/pages/SpacesPage.tsx` | `SpacesPage` | 用统一 EmptyState 表达规划中，不新增 CRUD |
| `src/components/domain/selects.tsx` | `ObjectTypeSelect`, `RelationSelect`, `PermissionSelect` | 增加兼容的 size/status/disabled/className 等呈现 props（按实际需要最小化）；options 仍来自 lexicon |
| `src/components/domain/RefBadge.tsx` | `RefBadge`, `TupleText` | 移除关键行内样式，增加溢出/可访问类；文本格式不变 |
| `src/auth/ProtectedRoute.tsx` | `ProtectedRoute` | 可选：只统一 loading/error 容器；鉴权判断、redirect、403 文案语义不变 |
| `src/pages/CallbackPage.tsx` | `CallbackPage` | 可选：只统一状态页面视觉；回调与 returnTo 逻辑不变 |

### 9.2 拟新增文件

| 文件 | 拟新增导出 | 责任边界 |
|---|---|---|
| `src/theme/theme.ts` | `appTheme` | 唯一 ThemeConfig |
| `src/components/layout/PageHeader.tsx` | `PageHeader` | title/description/extra，不取业务数据 |
| `src/components/common/AsyncState.tsx` | `PageSkeleton`, `ErrorState`, `EmptyState` | 通用状态呈现，不发请求 |
| `src/components/domain/SemanticTag.tsx` | `RelationTag`, `PermissionTag` | 读取 lexicon，类别非颜色区分 |
| `src/components/domain/AllowDenyResult.tsx` | `AllowDenyResult` | 只接 `allowed` 和摘要，不调用 check |
| `src/components/domain/SchemaTypeCard.tsx` | `SchemaTypeCard` | 只接 `ParsedDefinition`，渲染两类 semantic tags |
| `src/pages/grants/GrantFormPanel.tsx` | `GrantFormPanel` | 受控表单呈现；不拥有 mutation/query |
| `src/pages/grants/RelationshipListPanel.tsx` | `RelationshipListPanel` | 关系列表四态与撤销事件；不直接调用 API |
| `src/pages/playground/QueryPanel.tsx` | `QueryPanel` | 受控三模式输入；不调用 hooks |
| `src/pages/playground/ResultPanel.tsx` | `ResultPanel` | 按 mode/状态/data 渲染；check/expand 分区 |

CSS Modules 文件可与上述组件同目录新增；实际文件名按组件名 `.module.css`，不把样式集中回 JSX。

### 9.3 默认不改文件

`src/api/authz.ts`、`src/api/client.ts`、`src/hooks/useAuthz.ts`、`src/auth/AppAuthProvider.tsx`、`src/auth/AuthBridge.tsx`、`src/auth/oidcConfig.ts`、`src/config/index.ts`、`src/store/authStore.ts`、`src/router/routes.tsx`、`src/domain/zedParser.ts`、`src/domain/expandTree.ts`、`vite.config.ts`、`nginx.conf`、`Dockerfile`。若测试暴露现有 bug，应独立记录、评审后修改，不混为视觉重构。

## 10. 数据库、接口、配置、消息结构变更

| 类别 | 结论 |
|---|---|
| 数据库 | **无变更**；无迁移脚本 |
| 后端 API | **无变更**；方法、path、参数、响应保持 `src/api/authz.ts` 现状 |
| 前端 API 适配 | 默认无变更；继续复用 `apiClient`/hooks |
| OIDC/权限 | **无变更**；继续使用 access token、groups、ProtectedRoute |
| 环境配置 | **无新增变量**；Vite/Nginx/Docker 保持不变 |
| 消息结构 | **无变更**；GrantBody、CheckBody、Relationship、AuditRecord 等保持不变 |
| UI 内部契约 | 新增 ThemeConfig、Nav 展示元数据、呈现组件 props；不持久化、不跨网络 |
| 依赖 | 运行时不新增 UI 库；测试依赖会更新 `package.json/pnpm-lock.yaml` |

## 11. 分阶段实施步骤及依赖关系

阶段严格按“数据结构与领域模型 → 核心业务逻辑 → 接口与适配层 → 测试 → 文档与最终检查”组织。这里的“核心业务逻辑”是对现有页面容器逻辑的保护性拆分，不新增规则；“接口与适配层”包含 UI shell/theme 与页面接线。

以下五个阶段均分别列出依赖与完成标准；任一阶段未达标不得宣称该阶段完成。

### 阶段 1：数据结构与领域模型

1. 记录实施前 7 路由、关键 payload、桌面/手机截图与构建产物大小基线。
2. 新增 `appTheme` 和 NAV 展示元数据类型。
3. 新增 relation/permission 类别呈现契约，继续读取 `RELATIONS/PERMISSIONS`。
4. 明确核心面板最小 props 类型；不把 mutation 对象整体下传。
5. 先写纯函数/原语测试框架基线。

**依赖：** 无。

**完成标准：** TypeScript 通过；path、ObjectType、lexicon 映射、API 类型均未变化；主题 token 有单一来源；props 评审通过。

### 阶段 2：核心业务逻辑

1. 在 `GrantsPage` 保留现有 state、`submit/afterWrite/onResourceType`，抽出纯呈现面板。
2. 在 `PlaygroundPage` 保留 `run` 三分支和四个 mutation，抽出 Query/Result 面板。
3. 明确 check/expand 部分成功、idle/empty、caveat 状态。
4. 不改变请求触发时机、payload、query key、invalidate 或 OIDC/error 逻辑。

**依赖：** 阶段 1 的 props 与 SemanticTag/AsyncState 契约。

**完成标准：** grant/revoke userset payload、写后刷新、check/expand 并行、三类 lookup 与“用我自己”回归测试通过；无业务行为差异。

### 阶段 3：接口与适配层

1. `main.tsx` 接 theme；完成 AppLayout、分组 NAV、breadcrumb、内容容器和移动 Drawer。
2. 给 Grants/Playground 应用 7:5 与 5:7 响应式网格。
3. 迁移 Overview、Schema、Audit、Sync、Spaces。
4. 按需统一 ProtectedRoute/Callback 的视觉，不改判断与跳转。
5. 只通过现有 `api/authz.ts`/hooks 接线，不新增后端适配。

**依赖：** 阶段 2 锁定核心行为；theme/common primitives 可与阶段 2 的呈现工作并行，但合并顺序应先基础后页面。

**完成标准：** 7 路由布局统一；1440/1024/390 三档无阻塞溢出；所有状态齐全；API 请求计数未因布局变化异常增加。

### 阶段 4：测试

1. 完成 Vitest + RTL + user-event + MSW 单元/组件/页面集成测试。
2. 覆盖 grant/revoke/check/expand/lookup/sync/schema/audit 的成功、错误、空态和竞态。
3. 完成路由、鉴权、响应式、键盘、长文本、对比度测试。
4. 如 CI/浏览器环境可用，接入 Playwright 关键 E2E 与视觉截图；不可用则记录待验证并执行人工矩阵。
5. 运行 `pnpm build`，对比实施前 chunk/产物基线。

**依赖：** 阶段 3 页面稳定。

**完成标准：** build 与全部已接入测试通过；核心 payload 零回归；关键截图评审通过；无未解释 console error。

### 阶段 5：文档与最终检查

1. 更新 README 的页面结构、测试命令、状态规范；若行为无变，不改 API 文档。
2. 记录三个待确认决策：viewer 写页面、撤销确认、Spaces 导航。
3. 对照本计划执行文件范围审计，确认没有 API/auth/config 意外改动。
4. 进行可访问性、浏览器、真实 SSO/API smoke 和回滚演练。

**依赖：** 阶段 4 通过。

**完成标准：** 最终验收清单全勾选；文档与 UI 一致；发布/回滚负责人确认；未决项有负责人和截止时间。

## 12. 测试方案

### 12.1 必测业务不变量

- group/organization userset 开启时 grant/revoke body 带 `subjectRelation='member'`。
- 列表撤销原样传 relationship subject relation。
- 资源类型变化清空 relation/permission。
- grant/revoke 成功后失效当前 relationships key。
- check success + expand error 仍显示 ALLOW/DENY；expand 不作为权威结果。
- lookup 空结果与未运行不同；lookupSubjects caveat 可见。
- Audit 仍只使用现有四字段、limit 200、pageSize 20。
- Schema parse 空时可进入 raw，不显示无解释空白。
- 未登录/无 viewer/admin/401 续期/登出链路不回归。

### 12.2 状态与视觉矩阵

每个异步页面覆盖 idle（适用时）、initial loading、background refresh、empty、error、success。关键 viewport：1440×900、1024×768、768×1024、390×844；fixture 覆盖 128 字符 ID、深 Tree、200 条 Audit、多个 relation。

### 12.3 无障碍

状态不只靠颜色；focus 可见；icon button 有名称；Drawer 可 Escape；Tab 顺序合理；200% zoom 核心操作可达；建议 WCAG 2.1 AA（浏览器支持与正式等级待确认）。

### 12.4 性能

不新增运行时 UI 库；布局切换不重复触发 query；长内容只在局部滚动；以实施前 build 记录为 bundle 基线，任何显著增长需解释。

详尽用例见同目录 `test-plan.md`。

## 13. 风险、监控、灰度与回滚

### 13.1 主要风险

| 风险 | 等级 | 控制 |
|---|---:|---|
| 拆面板时改变 payload/handler 时机 | 高 | 页面容器保留 hooks/handler；MSW 精确断言 body |
| check/expand 状态误合并 | 高 | 两状态域、部分成功测试 |
| 全局 CSS 污染 Antd | 中 | CSS Modules + 自有根类；禁止深层 `.ant-*` |
| 移动端 Drawer/表格/Tree 溢出 | 中 | 4 viewport 视觉矩阵、局部 overflow |
| viewer 前端 gate 被误当安全边界 | 中 | 保留后端 403；UI 文案说明只读 |
| Audit 持久化文案不实 | 中 | 发布前后端确认 |
| 测试设施扩 scope | 中 | 先建立最小 Vitest/RTL/MSW，Playwright 视环境 |
| 设计 token 对比度不足 | 中 | 自动/人工对比度检查，非颜色信号 |

### 13.2 监控

当前仓库没有可确认的前端遥测/feature flag。上线后至少观察现有可用信号：静态资源加载错误、`/admin/*` 401/403/4xx/5xx、浏览器 console error、关键流程 smoke。Sentry、RUM、日志平台与告警阈值均为“待运维确认”，不得在实施说明中假定存在。

### 13.3 灰度

1. PR/提交按 theme+shell、common primitives、Grants、Playground、其余页、测试文档分层。
2. 测试环境先让平台/安全管理员验收核心两页。
3. 若部署平台支持前端版本流量灰度，可小范围发布；支持方式待验证。没有 flag 时使用镜像/tag 级整版灰度，不在代码里临时加未管理开关。
4. 核心 smoke：登录 → Grants 预览/授予/现存列表/撤销 → Playground allow/deny/expand/lookup → Schema → Audit → Sync 权限错误/成功。

### 13.4 回滚

- 保留上一前端构建镜像或静态产物；具体制品系统和命令待运维确认。
- 因无 API/DB/消息变更，回滚只需恢复上一前端版本，无数据回滚。
- 提交按页面隔离后，可单独 revert 某页或壳层；避免把 API/auth 改动混入同一提交。
- 回滚后重跑登录、7 路由和核心 API smoke。

## 14. 待确认项（实施前不阻断基础视觉，但阻断相关交互上线）

1. group/organization 的 `#member` 是否应从可关闭 Switch 改成强制；默认保持现状。
2. viewer 是否继续看见 Grants/Sync，以及写控件是禁用还是允许请求后显示 403；为避免未授权的行为变化，确认前保持现有可见性和请求行为，只补充角色说明。
3. 列表快速撤销和 revoke 模式是否增加 Popconfirm；默认建议增加，需产品确认。
4. Spaces 保留一级导航并标“规划中”，还是隐藏；默认保留标记。
5. Audit“内存环形最近 500 条、重启清空”文案是否与后端一致；必须后端确认。
6. 浏览器矩阵、正式无障碍等级、Playwright CI 环境；待工程负责人确认。

## 15. 最终验收清单

- [ ] 7 个现有业务 path 与 `/callback` 行为无变化。
- [ ] API、数据库、OIDC、消息结构、环境变量无变化。
- [ ] `ConfigProvider` theme 与 CSS token 单一来源落地。
- [ ] Sider/Header/breadcrumb/PageHeader/content grid 对齐统一。
- [ ] 1440 桌面核心页分别为 7:5、5:7；<992 变单栏/Drawer。
- [ ] Overview 首屏突出 Grants/Playground。
- [ ] Grants 四段表单、实时 tuple、五态列表清晰。
- [ ] userset 与列表撤销的 `subjectRelation` 无回归。
- [ ] Playground 三模式、ALLOW/DENY、输入摘要、expand 独立状态完整。
- [ ] lookupSubjects caveat 未弱化。
- [ ] Schema 1/2/3 列网格、relation/permission 两区、raw/解析空态完整。
- [ ] Audit 保留真实四列/200/20，不虚构筛选/总数/持久化能力。
- [ ] Sync 不虚构历史；Spaces 不伪装已实现。
- [ ] relation/permission 至少有两种非颜色差异。
- [ ] 所有异步页面具备适用的 idle/loading/empty/error/success。
- [ ] 390px 下关键操作可达，无页面级横向溢出（代码/表格/树仅局部滚动）。
- [ ] 键盘、focus、icon label、200% zoom、对比度检查通过。
- [ ] `pnpm build` 与已接入 unit/component/integration/E2E 测试通过。
- [ ] 真实 SSO 与核心 API smoke 通过。
- [ ] 待确认项均有书面决策，Audit 文案经后端确认。
- [ ] 发布制品、监控观察项、回滚步骤和责任人已确认。

## 16. 资深架构师复审记录

最终复审已完成，并据此做了四项收敛：

1. 撤回“默认禁用 viewer 写动作”的隐含行为变更；未决策前保持当前可见性和请求行为，后端 403 仍是边界。
2. 补齐 Button、Surface、Form、Table/List、Tree/Code、反馈组件的可执行规格，避免方案只停留在 token 层。
3. 为 A/B/C 分别补充差异化测试投入；最终 B 同时吸收 A 的 CSS 污染与多 viewport 回归。
4. 明确浅色 Sider、Header 边框和 Menu 选中态，修复壳层配色没有落到可实现参数的问题。

复审未发现需要修改数据库、API、鉴权或消息结构的理由；这些内容继续明确为零变更。剩余不确定项均保留在第 14 节，不以假设替代业务决策。
