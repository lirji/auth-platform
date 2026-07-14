# 02 · 代码库分析（codebase-explorer）

## 1. 项目基线

- 运行栈：React 18.3、Vite 5.4、TypeScript 5.6、Ant Design 5.21、React Router 6.27、TanStack Query 5.59、Zustand 5、Axios 1.7、oidc-client-ts 3.5。
- 入口：`src/main.tsx` 依次装配 `ConfigProvider`、Ant Design `App`、`QueryClientProvider`、`AppAuthProvider`、`RouterProvider`。
- 当前 `ConfigProvider` 仅配置 `zhCN`，没有 `theme`；全局 CSS 只有根节点高度和 body margin。
- `package.json` 只有 `dev/build/preview`，没有 lint、unit、component、E2E 或 visual test 脚本。
- 本次仅分析前端仓库；后端数据库、事务与真实响应实现未读取。

## 2. 目录与职责

```text
src/
├── api/
│   ├── client.ts               Axios、Bearer、401 单飞续期
│   └── authz.ts                grant/revoke/check/lookup/sync/relationships/expand/audit
├── auth/                       OIDC Provider、会话镜像、受保护路由
├── components/
│   ├── common/Placeholder.tsx  占位卡片
│   ├── domain/selects.tsx      对象/relation/permission Select
│   ├── domain/RefBadge.tsx     对象 Tag、元组等宽文本
│   └── layout/AppLayout.tsx    Sider/Header/Content
├── domain/
│   ├── lexicon.ts              对象/relation/permission 标签、颜色、可选项
│   ├── expandTree.ts            expand 响应到 antd Tree
│   └── zedParser.ts             轻量 .zed 解析
├── hooks/useAuthz.ts           React Query mutations、错误人话
├── pages/                      7 个业务页 + callback
├── router/routes.tsx           路由表
├── store/authStore.ts          OIDC UI 镜像与角色判断
├── nav.tsx                     菜单项
├── main.tsx                    Provider 组合
└── styles/global.css           极少全局规则
```

## 3. 真实调用链

### 3.1 应用与鉴权

```text
main.tsx
  → AppAuthProvider(AuthProvider + AuthBridge)
  → RouterProvider
  → ProtectedRoute
      ├─ 未登录：auth.signinRedirect({ returnTo: pathname })
      ├─ 无 viewer/admin：403 Result
      └─ AppLayout → Outlet → page

AuthBridge
  → auth.user.profile + groupsFromToken(access_token)
  → useAuthStore.set({ userId, username, authorities })
```

`AppLayout` 只读取 `username`；Overview/Playground 读取更多会话字段。重设计壳层不能绕过 `react-oidc-context` 或改写 token 生命周期。

### 3.2 API 通道

```text
page
  → useAuthz mutation / page-local useQuery
  → api/authz.ts
  → apiClient
      ├─ request: userManager.getUser() → Authorization: Bearer access_token
      └─ response 401: signinSilent 单飞 → 重试一次 → signinRedirect
  → dev Vite proxy / prod nginx /admin 同源反代
```

视觉重设计可以统一错误容器，但不得在页面层再造一套 401 处理或直接访问 OIDC storage。

### 3.3 授予页

```text
GrantsPage local state
  resourceType/resourceId/relation/subjectType/subjectId/userset/mode
  → useMemo 生成 tuple 预览
  → useGrant 或 useRevoke
  → POST /admin/grants 或 /admin/grants/revoke
  → 成功 message + invalidate ['relationships', resourceType, resourceId]

resourceId 非空
  → useQuery(listRelationships)
  → GET /admin/relationships?resourceType&resourceId
  → List
  → 行内 revoke（原样转发 subject.relation）
```

注意：`relKey` 随输入即时变化，输入任意非空 ID 就触发请求；当前没有 debounce。重设计若只做视觉，不应顺手改变请求时机。可把 debounce 作为独立、可选的性能增强。

### 3.4 调试器

```text
PlaygroundPage local state + mode
  check:
    useCheck → POST /admin/check → { allowed }
    useExpand → POST /admin/expand → unknown → expandToTree
  resources:
    useLookupResources → GET /admin/subjects/{type}/{id}/resources
  subjects:
    useLookupSubjects → GET /admin/resources/{type}/{id}/subjects
```

check 与 expand 连续触发但彼此不等待。主按钮 loading 不包含 `expand.isPending`；expand 失败没有页面反馈，因为没有传 `onError`。最终布局应把“判定结果”和“解释路径”作为两个状态域。

### 3.5 Schema

```text
SchemaViewerPage useQuery ['schema']
  → apiClient.get('/admin/schema')
  → schema string
  → parseZed(schema)
  → ParsedDefinition[]
  → Row/Col 类型卡片 或 raw pre
```

`parseZed` 是正则加花括号配对，不是完整语法解析器；遇到未知语法，注释要求调用方降级原文，但当前页面在解析结果为空时只会显示空网格。这里是状态设计缺口。

### 3.6 审计与同步

- `AuditPage`：`useQuery(['audit'])` → `audit(200)` → `GET /admin/audit?limit=200` → Table，rowKey 使用数组下标。
- `IdentitySyncPage`：`useCasdoorSync` → `POST /admin/casdoor/sync` → 本地渲染 groups/added/removed；没有历史 query。

## 4. 当前页面与视觉问题

| 页面/组件 | 当前实现 | 可见问题 | 可复用部分 |
|---|---|---|---|
| `AppLayout` | 直接行内样式，dark Sider + white Header + margin 16 Content | 无 breadcrumb、无内容最大宽、移动端只依赖 Sider breakpoint、品牌/分组/页标题层级弱 | `NAV`、账户 Dropdown、Outlet |
| Overview | 身份 Card + 3 个固定宽 200 快捷卡 + Alert | 首屏重心落在身份而非核心任务；Row 无响应式 Col span；统计缺口警告过重 | `QuickCard` 行为、authStore 数据 |
| Grants | 固定 560 + 460 两 Card，Space wrap | 宽度与对齐不自适应；字段分组弱；错误/空态不完整；危险动作缺确认 | 所有 local state、tuple/useQuery/mutation 流程 |
| Playground | 固定 440 + 460，两 Card | 结果面积偏小；未执行时空白；check/expand 状态耦合不清；长树处理不足 | 三模式状态、`expandToTree`、RefBadge |
| SchemaViewer | Segmented + Row/Col cards + raw pre | loading 只有 Spin；解析空白无说明；relation/permission 视觉差异主要靠颜色/标题 | `parseZed`、lexicon、现有响应式 Col |
| Spaces | Placeholder | 功能占位但导航看似正式可用 | 路由和 Placeholder 语义 |
| IdentitySync | maxWidth 560 Card | 页面孤立、结果只有成功数字、无成功标题/时间 | mutation 与统计数据 |
| Audit | Card 内 Table | 表格嵌套感重；无 error/empty 专用状态；detail 长文本策略缺失 | Table 列与刷新 query |
| Callback/ProtectedRoute | 居中 Spin/Result | 与新品牌壳层视觉不一致，但不应大改鉴权行为 | 现有状态判断与动作 |

## 5. 数据模型与展示契约

### 5.1 现有类型

- `ObjectType`：9 个联合类型，来自 `domain/lexicon.ts`。
- `GrantBody`：资源、relation、主体和可选 `subjectRelation`。
- `CheckBody`：主体、permission、资源。
- `SubjectView`：`type/id`。
- `Relationship`：resource、relation、subject（subject relation 可空）。
- `AuditRecord`：`at/actor/action/detail`。
- `ParsedDefinition/ParsedRelation/ParsedPermission`：SchemaViewer 展示模型。
- `SpiceNode`：`expandTree.ts` 内部接口，字段均可选，API 层返回 `unknown`。

### 5.2 lexicon 单一来源

`OBJECT_TYPES`、`RELATIONS`、`PERMISSIONS` 与 `relationsFor/permissionsFor` 是现有 UI 术语与色彩来源。重设计应直接复用，不能在页面内复制 mapping。类别级 `relation/permission` 表现可在拟新增的展示组件中统一实现。

## 6. 状态与缓存

- 全局仅有 auth Zustand store；页面表单全部 local state。
- QueryClient 默认：window focus 不刷新、staleTime 30s、retry 1。
- Schema/Audit/Relationships 使用 query；写入和 check/lookup/expand/sync 使用 mutation。
- 没有 UI store，也没有侧栏折叠持久化。若只需当前会话折叠，可保留 `AppLayout` local state，不必扩展 Zustand。
- 没有 URL query 参数，跨页携参和刷新恢复都不存在。候选方案 C 会把这作为能力，但成本更高。

## 7. 配置、构建与样式约束

- `vite.config.ts` 已将 React、Antd、OIDC、Query 手工分包；不应新增重型 UI 依赖。
- Vite dev port 5273，`/admin` 代理目标默认 8201；生产 Nginx 8202 同源代理。
- `global.css` 可以承载 app shell、响应式、通用页面布局；若希望隔离，新增 CSS Modules 不需要额外依赖。
- 当前大量行内 style，无法统一响应式、hover/focus 和媒体查询。无论选哪个方案，都需要把布局样式移出 JSX。
- Ant Design v5 支持 `ConfigProvider theme.token/components`；这是主设计系统落点。

## 8. 测试现状

- 仓库未发现测试文件、测试脚本或 Vitest/Jest/Playwright/Testing Library 依赖。
- `README.md` 记录过构建和真实 API 序列验证，但这不是可重复的仓库自动化测试。
- 本次规划不能声称已有覆盖率或视觉基线。
- 实施若新增测试基础设施，会修改 `package.json`、lockfile、Vite/Vitest 配置及测试文件；是否允许在视觉迭代中引入 Playwright 需结合交付环境确认。

## 9. 可复用代码（明确保留）

1. `src/api/client.ts`：token 注入、401 单飞续期。
2. `src/api/authz.ts`：所有 API 路径与响应类型。
3. `src/hooks/useAuthz.ts`：mutation 封装与 `humanizeError`。
4. `src/auth/*`、`src/store/authStore.ts`：登录与角色镜像。
5. `src/domain/lexicon.ts`：业务词汇、中文标签、色彩。
6. `src/domain/zedParser.ts` 与 `expandTree.ts`：展示转换逻辑，补测试而非重写。
7. `ObjectTypeSelect`、`RelationSelect`、`PermissionSelect`：保留功能，扩充 props/呈现。
8. `RefBadge`、`TupleText`：保留语义，统一 style 与可复制/溢出能力。
9. `NAV`、routes、页面现有 state/mutation/query 调用链。

## 10. 受影响文件清单

以下是执行重设计时预计受影响的**现有文件**；最终方案会区分必须与可选。这里不表示本规划阶段会修改它们。

### 必须修改

| 路径 | 现有函数/组件 | 计划影响 |
|---|---|---|
| `src/main.tsx` | 顶层 render | 给 `ConfigProvider` 接入 theme |
| `src/styles/global.css` | 全局规则 | token 辅助变量、壳层、页面、响应式基线 |
| `src/components/layout/AppLayout.tsx` | `AppLayout` | 重建壳层、面包屑、移动 Drawer、内容容器 |
| `src/nav.tsx` | `NAV`, `NavItem` | 增加导航分组/描述/访问级别展示元数据（保留 path） |
| `src/pages/OverviewPage.tsx` | `QuickCard`, `OverviewPage` | 任务优先布局 |
| `src/pages/GrantsPage.tsx` | `GrantsPage`, `submit`, `afterWrite`, `onResourceType` | 仅重组呈现与状态面板，保留方法行为 |
| `src/pages/PlaygroundPage.tsx` | `PlaygroundPage`, `run` | 输入/结果工作区与独立 check/expand 状态 |
| `src/pages/SchemaViewerPage.tsx` | `SchemaViewerPage`, `typeColor` | 网格、类别视觉、解析空态、raw 面板 |
| `src/pages/SpacesPage.tsx` | `SpacesPage` | 正式规划中空态 |
| `src/pages/IdentitySyncPage.tsx` | `IdentitySyncPage` | 单任务页和统一反馈 |
| `src/pages/AuditPage.tsx` | `AuditPage`, `actionColor` | 表格工具条、四态、响应式 |
| `src/components/domain/selects.tsx` | 三个 Select 组件 | 大小、状态、option 类别提示等兼容 props |
| `src/components/domain/RefBadge.tsx` | `RefBadge`, `TupleText` | 统一外观、溢出、类别辅助 |

### 建议新增（名称是实施提案，不是现有事实）

| 拟新增路径 | 拟新增导出 | 职责 |
|---|---|---|
| `src/theme/theme.ts` | `appTheme` | Antd ThemeConfig 单一来源 |
| `src/components/layout/PageHeader.tsx` | `PageHeader` | 页标题、说明、extra |
| `src/components/common/AsyncState.tsx` | `PageSkeleton`, `ErrorState`, `EmptyState` | 状态反馈原语 |
| `src/components/domain/SemanticTag.tsx` | `RelationTag`, `PermissionTag` | relation/permission 非颜色区分 |
| `src/components/domain/AllowDenyResult.tsx` | `AllowDenyResult` | check 结果 banner |
| `src/components/domain/SchemaTypeCard.tsx` | `SchemaTypeCard` | Schema 卡片 |
| `src/pages/grants/GrantFormPanel.tsx` | `GrantFormPanel` | 授予表单呈现（可在第二阶段拆） |
| `src/pages/grants/RelationshipListPanel.tsx` | `RelationshipListPanel` | 现存授予列表呈现 |
| `src/pages/playground/QueryPanel.tsx` | `QueryPanel` | 调试器输入 |
| `src/pages/playground/ResultPanel.tsx` | `ResultPanel` | 调试器结果 |

### 条件修改/补测

| 路径 | 条件 |
|---|---|
| `src/domain/expandTree.ts` | 仅为稳定 key/展示元数据或测试性做小改；不能猜 API |
| `src/domain/zedParser.ts` | 默认只补测试；若修解析 bug，需独立评审，不混入纯视觉提交 |
| `src/hooks/useAuthz.ts` | 默认不改；仅在统一错误对象确有必要时修改 |
| `src/router/routes.tsx` | 默认不改路径；仅当 Page metadata 由 route handle 驱动时修改 |
| `src/auth/ProtectedRoute.tsx`, `src/pages/CallbackPage.tsx` | 可做视觉一致化，禁止改变鉴权条件/跳转 |
| `package.json`, `pnpm-lock.yaml` | 仅为测试依赖/脚本修改，不引入 UI 库 |

### 明确不改

- `src/api/authz.ts`、`src/api/client.ts`、`src/auth/AppAuthProvider.tsx`、`src/auth/AuthBridge.tsx`、`src/auth/oidcConfig.ts`、`src/config/index.ts`、`src/store/authStore.ts` 的业务与鉴权逻辑。
- `vite.config.ts`、`nginx.conf`、`Dockerfile` 的部署链路，除非后续测试配置确有独立需求。

## 11. 代码层关键风险

1. 行内样式迁移时容易同时改 JSX 结构与行为，建议先抽 token/壳层，再逐页迁移。
2. `GrantsPage` 同一个 `revoke` mutation 同时服务表单与列表；全局 loading 可能让两个入口互相影响，视觉方案应如实反映，不擅自改变 mutation 并发语义。
3. 调试器切 mode 后旧 mutation data 仍存在于 hook 实例中，但 JSX 按 mode 隔离；重布局不得误把不同模式旧结果混显。
4. `expandToTree` 使用模块级 counter，虽每次入口重置，但并发/服务端渲染不适用；当前为纯客户端。若要修，需单独测试。
5. Audit `rowKey` 为数组下标；刷新后行身份不稳定。API 没有 id，不能虚构稳定主键。
6. NAV 当前只以 `location.pathname` 精确匹配；现有路由均一级，足够。未来详情路由才需要前缀匹配，本期无需预先复杂化。
