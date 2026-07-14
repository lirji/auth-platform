# 05 · 候选方案 C：上下文驱动的权限工作台

## 1. 方案定位

把授予、调试、模型理解从相互独立页面升级为“围绕当前资源/主体上下文”的联动工作台：URL query 保存上下文，页面之间可一键带参跳转，右侧统一 Inspector 展示元组、判定摘要或模型解释。该方案产品差异最大，也最接近专业安全运维工具。

## 2. 架构与模块职责

```text
URLSearchParams（可分享上下文）
  ↕ useAuthorizationContext（拟新增，校验/序列化）
Page containers
  ├─ Grants workspace
  ├─ Playground workspace
  └─ Schema workspace
Shared Inspector
  ├─ tuple summary
  ├─ relation/permission legend
  └─ deep links
```

- 拟新增 `useAuthorizationContext`，只管理 `resourceType/resourceId/subjectType/subjectId/relation/permission` 的 URL 序列化与白名单校验。
- Grants 成功后可生成“去调试器验证”的链接；Playground 可回到授予页查看同一资源。
- AppLayout 支持更宽的工作区和可选的上下文 Inspector；移动端 Inspector 变 Drawer。
- 页面内部仍调用现有 API/hooks，不建新的服务端聚合层。

## 3. 核心流程

### 授予 → 验证

1. 在 Grants 输入形成 URL 上下文（是否每次输入都写 URL 需节流）。
2. grant 成功后展示“在调试器验证”。
3. 跳 `/playground?...`，自动填入可对应字段；relation 不能自动等价为 permission，只有在存在**已确认映射**时才预选，否则让用户选择 permission。

### 调试 → 追溯

1. check/lookup 条件可复制链接。
2. 结果 Inspector 汇总资源、主体、permission 和 caveat。
3. 可跳 SchemaViewer 并定位 object type；具体 relation/permission DOM anchor 属新增 UI 能力。

## 4. 改动范围

- 现有文件约 18–22 个，包括 routes/pages/nav/layout/domain components。
- 新增 URL context hook、序列化纯函数、Inspector、deep-link actions、更多子面板与测试，约 15–20 个文件。
- 不需新 UI 库，但路由与页面状态初始化逻辑显著增加。
- 仍不改后端接口、数据库、OIDC 和 React Query 基础。

## 5. 扩展性

- 最适合未来加入可分享排障链接、跨团队工单、最近上下文、资源详情。
- URL 是稳定内部契约，一旦发布就要考虑兼容旧链接、非法参数与敏感信息暴露。
- 现有 ID 可能包含租户/用户标识；写入 URL 会进入浏览器历史、日志和截图，安全评审不可省略。

## 6. 实施成本

- 预计 12–18 个开发日 + 5–7 个测试/安全/视觉验收日（单人粗略估算，待校准）。
- 需要产品确认跨页映射、URL 敏感数据策略与 deep-link 生命周期。

## 7. 优点

1. 核心价值页形成闭环，管理员排障效率最高。
2. 查询上下文可恢复、可分享、可审计讨论。
3. 统一 Inspector 强化 relation/permission 教育和输入摘要。
4. 为后续专业化权限诊断能力打好交互架构。

## 8. 弱点

1. 明显超出“布局/视觉重设计”的最小范围，实际引入新的交互与 URL 契约。
2. URL 暴露 subject/resource ID，存在隐私与日志泄漏风险。
3. relation 到 permission 不是一一映射，跨页自动化容易误导。
4. 状态同步、浏览器前进后退、非法参数和旧链接测试成本最高。
5. 回滚后已分享链接会失效或退化。

## 9. 失败场景与控制

- 失败：把 relation 自动翻译成错误 permission。控制：除非 `lexicon` 有经业务确认的映射，否则只带资源/主体，不预选 permission。
- 失败：输入每个字符都更新 history。控制：只在提交或显式“复制链接”时写 URL，或使用 replace + debounce；具体策略待产品确认。
- 失败：敏感 ID 出现在 URL。控制：默认不带 subjectId，或只生成显式分享链接；需要安全负责人确认。
- 失败：旧 URL 参数导致 Select 非法值。控制：用 `ObjectType`/relationsFor/permissionsFor 白名单解析，非法值丢弃并提示。
- 失败：移动端三栏不可用。控制：Inspector 降级为 Drawer，主区单栏。

## 10. 适用条件

只有当“可分享排障上下文、授予后验证闭环”被确认为本期产品目标，并完成 URL 数据安全评审时，才建议选择 C；否则应只吸收其“输入摘要”和“显式跳转链接”概念，不落 URL 状态架构。
