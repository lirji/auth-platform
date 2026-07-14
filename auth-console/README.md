# auth-console

统一权限平台管控台(**授权侧**:关系/授予/权限调试)。React18 + Vite + TS + Ant Design + React Query + oidc-client-ts(登录走 Casdoor)。

> 身份/组织/用户/组的增删由 **Casdoor 自带控制台**(:8000)负责;auth-console 只做授权(SpiceDB 关系/授予/判定)。完整设计见 `PLAN.md`。

## 文档

布局/视觉重设计的**按功能拆分**说明(面向后续开发者)在 [`docs/redesign/`](./docs/redesign/README.md):

| 文档 | 覆盖 |
|---|---|
| [README](./docs/redesign/README.md) | 索引 + 改造总览 + **零变更约束** |
| [01 设计系统](./docs/redesign/01-design-system.md) | 调色板单一来源、`ThemeConfig` token、全局 CSS |
| [02 壳层与导航](./docs/redesign/02-layout-and-navigation.md) | `AppLayout`/`PageHeader`/`nav`、**响应式与移动端 Drawer** |
| [03 状态与反馈](./docs/redesign/03-state-and-feedback.md) | `AsyncState` 四态原语 + per-mode 状态机范式 |
| [04 领域组件](./docs/redesign/04-domain-components.md) | **relation vs permission 非颜色区分**、判定卡、schema 卡、引用徽章 |
| [05 页面清单](./docs/redesign/05-pages.md) | 七页布局骨架/栅格/状态 |
| [06 无障碍](./docs/redesign/06-accessibility.md) | 键盘可达、`aria-label`、表单 `<label htmlFor>`、焦点管理 |

> 规划与实施过程态另见 `docs/plans/auth-console-redesign-0714-1508/`(FINAL_PLAN + IMPLEMENTATION_PROGRESS)。

## 开发

```bash
pnpm install
pnpm dev        # http://localhost:5273  (/admin 同源反代到 auth-platform-admin :8201,免 CORS)
pnpm build      # tsc 类型检查 + vite 打包 → dist/
```

前置:`auth-platform-admin`(:8201)在跑;后续鉴权(M2)需 Casdoor(:8000)+ 在 Casdoor 建 `auth-console` 应用并把 `VITE_CASDOOR_CLIENT_ID` 填进 `.env.local`。

## 配置(`.env.local`,见 `.env.example`)

| 变量 | 说明 |
|---|---|
| `VITE_ADMIN_BASE_URL` | 空=相对路径(dev vite proxy / prod nginx 同源反代) |
| `VITE_ADMIN_TARGET` | dev proxy 目标(默认 :8201) |
| `VITE_CASDOOR_AUTHORITY` | Casdoor issuer(默认 :8000) |
| `VITE_CASDOOR_CLIENT_ID` | Casdoor auth-console 应用 client_id |

## 里程碑进度

- ✅ **M1 脚手架**:Vite+React+TS+antd 项目、路由/config/proxy、AppLayout 侧栏壳 + 7 页、`domain/lexicon`。
- ✅ **M2 鉴权流**:oidc-client-ts 接 Casdoor(授权码+PKCE,scope offline_access 走 refresh)、AuthBridge→authStore、`/callback`、ProtectedRoute(未登录跳 Casdoor / 非 authz-viewer 403)、axios 拦截器(附 Bearer + **401 单飞续期**)、头部登出。Casdoor 已建 `auth-console` 应用(client_id 在 `.env.local`)。
- ✅ **M3 数据层 + 原子组件**:`api/authz` + React Query hooks(useGrant/useCheck/useLookup*/useCasdoorSync + humanizeError)+ ObjectTypeSelect/RelationSelect/PermissionSelect/RefBadge/TupleText。
- ✅ **M4 核心页**:**授予管理**(资源+关系+主体,group 主体自动 #member,实时元组预览,grant/revoke)+ **权限调试器**(check 的 allow/deny + 反查资源/主体 + "用我自己")+ **身份同步**(一键 sync + added/removed)。
- ✅ **M5 概览 + schema 查看器**:概览(当前身份 + 授权组 + 快捷入口)+ schema 查看器(拉 `GET /admin/schema` → 客户端 `zedParser` 解析 → 类型卡片,含关系/权限;可切原始 .zed)。后端已加 `readSchema`(AuthzEngine 端口 + SpiceDB ReadSchema 透传)。
- ✅ **M6 交付**:vite `manualChunks` 分包(react/antd/oidc/query 各自 chunk)+ `Dockerfile`(node build → nginx:8202)+ `nginx.conf`(SPA 回退 + 同源反代 /admin)。

**验证**:构建通过(tsc+vite,分包生效);经 dev 代理 + 真实 Casdoor token 全 API 序列端到端(授予→判定→反查→撤销)通过;无 token 401 / authz-viewer 写 403;zedParser 对真实合并 schema 正确解析 9 个 definition。浏览器 SSO 登录待人工在 http://localhost:5273 实测(标准 oidc-client-ts 流程)。

## 结构

```
src/
  config/index.ts               唯一 env 出口
  api/
    client.ts                   axios 实例(附 Bearer + 401 单飞续期)
    authz.ts                    admin 端点封装 + DTO
  auth/                         OIDC 接 Casdoor:AppAuthProvider / AuthBridge→store / oidcConfig / ProtectedRoute
  store/authStore.ts            Zustand 鉴权态(userId/username/authorities + isAdmin)
  hooks/useAuthz.ts             React Query hooks(useGrant/useCheck/useLookup*/useCasdoorSync)+ humanizeError
  domain/
    lexicon.ts                  对象类型/权限/关系 → 中文标签+色 + relationsFor/permissionsFor
    zedParser.ts                .zed → ParsedDefinition   expandTree.ts  expand → antd Tree
  theme/
    colors.ts                   调色板单一来源     theme.ts  appTheme(ThemeConfig)
  styles/global.css             reset + .app-content/.brand/.mono/.scroll-x
  nav.tsx                       侧栏菜单 + 路由单一配置源(分组)
  router/routes.tsx             数据式路由表(/login /callback + 守卫)
  components/
    layout/                     AppLayout(壳层+响应式 Drawer)/ PageHeader
    common/AsyncState.tsx       PageSkeleton / ErrorState / EmptyState
    domain/                     SemanticTag(关系vs权限)/ AllowDenyResult / SchemaTypeCard / RefBadge / selects / SpaceMemberCard
  pages/*.tsx                   7 业务页 + CallbackPage
  main.tsx                      ConfigProvider(theme) + QueryClient + RouterProvider
```

> 呈现层设计说明按功能拆分见 [`docs/redesign/`](./docs/redesign/README.md)。
