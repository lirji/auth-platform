# 03 · 候选方案 A：渐进式视觉整顿

## 1. 方案定位

在不拆页面组件、不改变状态归属与调用链的前提下，优先完成壳层、theme token、统一页面标题和页面内 CSS Grid。它是“最低结构扰动”的视觉重构，目标是快速把当前裸 Card/Space 堆叠提升到一致、可靠的管理台水准。

## 2. 架构与模块职责

```text
main.tsx → appTheme
AppLayout → global shell + breadcrumb + content container
每个 Page → PageHeader + 原页面状态/请求 + CSS Grid
common/domain 组件 → 轻量外观增强
```

- 新增 `src/theme/theme.ts`，集中 Antd token 和组件 token。
- 新增 `PageHeader`、`AsyncState`、`SemanticTag`、`AllowDenyResult` 四类小型通用组件。
- 页面不拆子目录；`GrantsPage`、`PlaygroundPage` 继续持有全部 state、query、mutation 和回调。
- 样式主要集中在 `global.css`，用明确的 `app-*` 类组织壳层和页面网格。

## 3. 核心页面流程

- 授予：保持 `submit/afterWrite/onResourceType` 原样，只把 JSX 改为四段表单和右侧列表，增加明确四态。
- 调试器：保持 `run` 原样；结果区新增“未执行”空态、ALLOW/DENY banner、expand 独立容器。
- Schema/Audit：保留当前 query 与列表/表格，只更换布局和状态反馈。

## 4. 改动范围

- 现有文件约 13–16 个：`main.tsx`、`global.css`、`AppLayout`、`nav.tsx`、7 个业务页、domain selects/badges，以及可选的 auth 状态页。
- 新增约 4–5 个通用文件。
- 不修改 API、hooks、store、auth、router；不引入新运行时依赖。

## 5. 扩展性

- Theme 与 PageHeader 可复用于后续页面。
- 页面仍为“大组件”，随着筛选、批量操作、更多结果解释增长，可维护性会下降。
- 全局 CSS 的类名约束靠团队纪律，跨页样式易互相影响。

## 6. 实施成本

- 预计 4–6 个开发日 + 2–3 个测试/视觉验收日（单人，环境齐备的粗略估算，待团队校准）。
- 学习与迁移成本最低；行为回归面最小。

## 7. 优点

1. 对现有业务逻辑的扰动最小，容易逐页提交与回滚。
2. 最快建立主题、间距和反馈一致性。
3. 不引入额外状态层或 URL 契约。
4. 适合当前页面数量小、逻辑已可用的状态。

## 8. 弱点

1. 授予和调试器仍是单文件大组件，视图与业务状态强耦合。
2. 视觉规范可复用，但核心工作台组件不易独立测试。
3. 后续增加批量授予、筛选、跨页验证时可能二次拆分。
4. 全局 CSS 容易形成隐式依赖。

## 9. 失败场景与控制

- 失败：全局 CSS 覆盖 Antd 内部类导致升级回归。控制：只使用自有根类和 `ConfigProvider components`，不写深层 `.ant-*` 选择器。
- 失败：页面 JSX 一次改动过大。控制：按壳层 → 公共原语 → 单页顺序落地，每页单独快照/视觉核对。
- 失败：新增统一空态掩盖旧数据。控制：局部刷新保留 data，仅首次无 data 时显示 skeleton/error。

## 10. 适用条件

若发布时间紧、短期不会扩展核心页业务能力，A 是最稳妥的基线方案。
