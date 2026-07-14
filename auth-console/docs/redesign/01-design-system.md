# 01 · 设计系统(token / 配色 / 全局样式)

一套克制的设计 token,单一来源,经 Ant Design `ConfigProvider` 全局注入,避免 per-component 硬编码。

## 涉及文件

| 文件 | 职责 |
|---|---|
| `src/theme/colors.ts` | **调色板单一来源**——所有色值的唯一出处 |
| `src/theme/theme.ts` | Ant Design `ThemeConfig`(token + 组件级覆盖),色值全取自 `colors.ts` |
| `src/styles/global.css` | 全局 reset + 作用域工具类(`.app-content` / `.brand` / `.mono` / `.scroll-x`) |
| `src/main.tsx` | `<ConfigProvider locale={zhCN} theme={appTheme}>` 注入 |

## 调色板(`colors.ts`)

唯一来源。`theme.ts` 的 token 与「必须写行内色值」的组件(如 `AllowDenyResult`、`Overview` 任务卡、
`IdentitySync` 统计、`Grants` 预览卡)都从这里取,**不再散落 `#F7F9FC` 之类的字面量**。

```ts
export const colors = {
  primary: '#315EFB',      primarySoft: '#EEF3FF',
  success: '#16A36A',      warning: '#D97706',   error: '#D92D20',
  bgLayout: '#F5F7FA',     bgSubtle: '#F7F9FC',  border: '#E6EAF0',
  text: '#172033',         textSecondary: '#667085', textTertiary: '#98A2B3',
} as const
```

> **约定**:任何组件需要行内色值,一律 `import { colors } from '@/theme/colors'`(相对路径),
> 禁止再写十六进制字面量。新增色先加进 `colors.ts`。

## 主题 token(`theme.ts` → `appTheme`)

唯一 `ThemeConfig`,通过 `ConfigProvider` 生效:

- **token**:`colorPrimary/Info=primary`、`colorSuccess/Warning/Error`、`colorBgLayout=bgLayout`、
  `colorBgContainer=#FFF`、文字三级、`colorBorderSecondary=border`;
  圆角 `borderRadius: 8 / borderRadiusLG: 12`;字号 `14`;控件高 `controlHeight: 36 / LG: 40`;`wireframe: false`。
- **components 覆盖**:
  - `Layout`:Header/Sider 白底、`bodyBg=bgLayout`、`headerHeight: 56`、`headerPadding: '0 20px'`
  - `Menu`:选中态 `primarySoft` 底 + `primary` 字、圆角 8、`itemMarginInline: 8`
  - `Table`:表头 `bgSubtle` 底、hover `bgSubtle`
  - `Card`:表头透明、`headerFontSize: 15`
  - `Segmented`:选中项白底

## 全局样式(`global.css`)

不用 per-component CSS Module,只保留一份小体量全局样式 + 作用域类,**不深层覆盖 `.ant-*`**(污染面小):

- `--app-content-max: 1440px`;`body` 底色 `#f5f7fa`。
- **`.app-content`**:内容区容器——`max-width: 1440px` + `margin: 0 auto` 居中 + `padding: 24px`(≤768px 降为 16px)。由 `AppLayout` 包裹 `<Outlet/>`。
- **`.mono`**:等宽字体 + `word-break: break-all`(长 id/元组换行不撑破)。
- **`.scroll-x`**:`overflow-x: auto`——长内容(如 expand 判定树)**仅局部**横滚,不触发页面级横向溢出。
- **`.brand`**:侧栏/抽屉品牌区(图标 + 标题,flex 居中)。

## 注入(`main.tsx`)

```tsx
<ConfigProvider locale={zhCN} theme={appTheme}>
  <App> {/* antd App 提供 message/modal context */}
    …
  </App>
</ConfigProvider>
```

## 扩展指引

- 改主色/圆角/间距 → 只动 `colors.ts` / `theme.ts`,全局生效。
- 加组件级视觉规则 → 优先用 `theme.components.<Comp>` token,其次才考虑 `global.css` 作用域类;
  避免写 `.ant-xxx { … }` 深覆盖。
