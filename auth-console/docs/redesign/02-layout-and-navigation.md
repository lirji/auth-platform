# 02 · 壳层布局与导航

应用外壳(侧边栏 + 顶栏 + 内容区)、页面标题区、导航配置,以及**响应式与移动端 Drawer**。

## 涉及文件

| 文件 | 职责 |
|---|---|
| `src/components/layout/AppLayout.tsx` | 应用外壳:Sider / Header / 面包屑 / 内容容器 / **移动端 Drawer** |
| `src/components/layout/PageHeader.tsx` | 页面标题区(title / description / extra) |
| `src/nav.tsx` | 侧边菜单 + 路由的**单一配置源**(分组) |

## 导航配置(`nav.tsx`)

菜单项与分组的唯一来源,`AppLayout` 据此生成菜单、`router` 据此绑路由:

```ts
export interface NavItem { path: string; label: string; icon: ReactNode; group: string }

export const NAV: NavItem[] = [
  { path: '/',           label: '概览',       icon: <DashboardOutlined/>,        group: '工作台' },
  { path: '/grants',     label: '授予管理',   icon: <SafetyCertificateOutlined/>,group: '核心操作' },
  { path: '/playground', label: '权限调试器', icon: <BugOutlined/>,              group: '核心操作' },
  { path: '/schema',     label: '授权模型',   icon: <ApartmentOutlined/>,        group: '资源与模型' },
  { path: '/spaces',     label: '空间/知识库',icon: <DatabaseOutlined/>,         group: '资源与模型' },
  { path: '/sync',       label: '身份同步',   icon: <SyncOutlined/>,             group: '系统治理' },
  { path: '/audit',      label: '审计日志',   icon: <AuditOutlined/>,            group: '系统治理' },
]
export const NAV_GROUPS = ['工作台', '核心操作', '资源与模型', '系统治理']
```

> **信息架构**:把两个核心价值页(授予管理、权限调试器)提到「核心操作」分组、紧随「工作台」之后,
> 突出 SpiceDB 自身没有的 UI。

## 页面标题区(`PageHeader`)

每页顶部统一标题块,不取业务数据:

```tsx
<PageHeader title="授予管理" description="给用户/组授予 …" extra={<Segmented …/>} />
```

- `title` 用 `Typography.Title level={4}`;`description` 用次级段落;`extra` 右对齐(如刷新按钮、视图切换)。
- 容器 `flex + justify-between + flexWrap`,窄屏 extra 自动换行。

## 应用外壳(`AppLayout`)

`Layout` 结构:`[Sider] + [Header(汉堡 + 面包屑 + 账户下拉) + Content(.app-content 包 <Outlet/>)] + [Drawer]`。

### 响应式:桌面 Sider ↔ 移动端 Drawer

用 `Grid.useBreakpoint()` 判断断点,`isMobile = !screens.lg`(`lg` = 992px):

| 视口 | Sider | 汉堡按钮行为 | 菜单载体 |
|---|---|---|---|
| **≥ lg(桌面)** | 渲染,可折叠 **224 ↔ 72** | 切换 `collapsed`(折叠/展开图标) | Sider 内 `Menu` |
| **< lg(移动)** | 不渲染(内容占满宽) | 打开左侧 `Drawer` | Drawer 内 `Menu` |

菜单抽成 `menu(afterClick?)` helper,Sider 与 Drawer **复用同一份定义,零重复**;
移动端点任一菜单项后 `afterClick` 自动关闭 Drawer。

```tsx
const screens = Grid.useBreakpoint()
const isMobile = !screens.lg
const [collapsed, setCollapsed] = useState(false)   // 桌面折叠
const [drawerOpen, setDrawerOpen] = useState(false) // 移动抽屉

const menu = (afterClick?: () => void) => (
  <Menu mode="inline" selectedKeys={[location.pathname]} items={menuItems}
        onClick={(e) => { navigate(e.key); afterClick?.() }} />
)

// Header 汉堡:
onClick={() => (isMobile ? setDrawerOpen(true) : setCollapsed(!collapsed))}
aria-label={isMobile ? '打开菜单' : collapsed ? '展开菜单' : '收起菜单'}

// 移动端 Drawer(遮罩/Escape/焦点由 antd Drawer 内建):
<Drawer placement="left" width={224}
        open={isMobile && drawerOpen} onClose={() => setDrawerOpen(false)}>
  {menu(() => setDrawerOpen(false))}
</Drawer>
```

- **面包屑**:`[管控台, 当前页 label]`,当前页由 `NAV.find(path === location.pathname)` 得出。
- **账户区**:`Dropdown` 触发器是可聚焦 `Button`(键盘可达),下拉含「退出登录」→ `auth.signoutRedirect()`。
- **品牌区** `.brand`:桌面折叠(72)时只显示图标,展开显示「权限管控台」;Drawer 用标题栏显示。

### 手测要点

- 窗口拉到 `<992px`:Sider 消失、Header 出现汉堡 `☰`;≥992px 回到可折叠 Sider。
- Drawer:遮罩点击关 / `Esc` 关 / 点菜单项跳转并自动关 / `Tab` 焦点限制在 Drawer 内。
