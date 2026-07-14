# auth-console 布局/视觉重设计 · 文档索引

本目录记录 2026-07-14 对 **auth-console(统一权限平台管控台)** 的一次**布局与视觉重设计**。
这是**呈现层重设计,不是功能重写**:业务逻辑、API 层(`api/authz.ts`)、鉴权(`auth/*`)、
数据 hooks、Zustand store、路由、`domain/lexicon.ts` 全部保留不变(见「零变更约束」)。

> 规划与评审全过程另见 `docs/plans/auth-console-redesign-0714-1508/`
> (`FINAL_PLAN.md` = codex-plan 产出方案,`IMPLEMENTATION_PROGRESS.md` = 分阶段实施记录 + codex-review 修复 + 后置项收尾)。
> 本目录是面向后续开发者的**结果态说明**,按功能模块拆分。

## 按功能拆分的文档

| # | 文档 | 覆盖内容 |
|---|---|---|
| 01 | [设计系统](./01-design-system.md) | 调色板单一来源、Ant Design `ThemeConfig` token、全局 CSS、`ConfigProvider` 接入 |
| 02 | [壳层布局与导航](./02-layout-and-navigation.md) | `AppLayout`(Sider/Header/面包屑)、`PageHeader`、`nav` 分组、**响应式与移动端 Drawer** |
| 03 | [状态与反馈](./03-state-and-feedback.md) | 加载/错误/空/成功的统一原语(`AsyncState`),per-mode 状态机范式 |
| 04 | [领域组件](./04-domain-components.md) | `SemanticTag`(**relation vs permission 非颜色区分**)、`AllowDenyResult`、`SchemaTypeCard`、`RefBadge`、`selects` |
| 05 | [页面清单](./05-pages.md) | 七页各自的布局骨架、栅格、状态处理 |
| 06 | [无障碍](./06-accessibility.md) | 键盘可达、`aria-label`、表单 `<label htmlFor>`、Drawer 焦点管理 |

## 技术栈(未变)

React 18 · Vite 5 · TypeScript 5 · **Ant Design 5** · @tanstack/react-query 5 · Zustand 5 ·
oidc-client-ts 3 / react-oidc-context 3(登录走 Casdoor SSO)。包管理 pnpm。dev `:5273` / prod `:8202`。

```bash
pnpm dev      # http://localhost:5273
pnpm build    # tsc + vite build → dist/
```

## 零变更约束(重设计的边界)

以下在本次重设计中**逐字未改**,回归风险面被刻意压到最小:

- **API 层** `src/api/*`(端点、请求体、query key)
- **数据 hooks** `src/hooks/useAuthz.ts`(mutation/query、`humanizeError`)
- **鉴权** `src/auth/*`、`src/store/authStore.ts`(OIDC、groups→authorities、`isAdmin`)
- **领域词典** `src/domain/lexicon.ts`(对象/权限/关系的中文标签与配色)、`zedParser`、`expandTree`
- **路由/配置** `src/router`、`config`
- **后端 / Casdoor / OIDC** 一律未动

唯一的**向后兼容形参新增**:`selects.tsx` 三个 Select 组件加了可选 `id?`(不传即 `undefined`,老调用点不受影响),仅为无障碍 `<label htmlFor>` 关联。

## 设计目标回顾

1. 统一克制的设计 token(主色/中性色阶/圆角/间距),替代散落硬编码。
2. 清晰的壳层信息架构:分组侧边栏 + 面包屑 + 居中定宽内容区。
3. **突出两个核心价值页**(SpiceDB 自身没有的 UI):授予管理、权限调试器。
4. 加载/错误/空/成功四态**统一反馈**。
5. **relation(可授予)vs permission(可判定)的视觉区分——不只靠颜色**。
6. 响应式与信息密度打磨,移动端可用。
