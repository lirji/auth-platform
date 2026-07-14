# 04 · 候选方案 B：任务型工作台与页面模块化

## 1. 方案定位

围绕管理员真实任务重组壳层和核心页，同时将“状态编排”留在页面容器、将“表单/结果/列表呈现”拆成可测试面板。它不改变 API 或全局状态架构，但比方案 A 更强调核心流、复用和长期维护。

## 2. 架构与模块职责

```text
App shell
├─ grouped NAV + breadcrumb + PageHeader
├─ page container（持有 query/mutation/local state）
│  ├─ grants: GrantFormPanel + RelationshipListPanel
│  ├─ playground: QueryPanel + ResultPanel
│  └─ schema/audit: page-specific presentational components
└─ design primitives
   ├─ AsyncState
   ├─ SemanticTag
   ├─ RefBadge/TupleText
   └─ AllowDenyResult
```

- 页面容器负责现有 `useState/useQuery/useMutation` 和 handler，避免把 React Query 状态散落到深层。
- 子面板只接收显式 props 和事件，不直接调用 API；这样可以独立做组件测试和 Story-like fixture（不要求引入 Storybook）。
- CSS Modules 用于 page/layout 局部样式；`global.css` 只保留 reset、CSS 变量和全局响应式基础。是否使用 CSS Modules 无需新依赖，Vite 已支持。
- `NAV` 增加 group/description/access 元数据，由 `AppLayout` 转为分组 Menu；routes 保持不变。

## 3. 核心流程

### 授予工作台

- 左 7：表单四段 + sticky 元组确认区 + 主动作。
- 右 5：当前资源摘要 + 现存授予列表 + 独立状态/刷新表现。
- 页面容器继续使用 `submit` 与 `afterWrite`；`GrantFormPanel` 只发出字段变化和 submit。
- 快速撤销拟增加 `Popconfirm`，该行为变更需产品确认；未确认时仍只做 danger 样式。

### 调试工作台

- 左 5：模式/条件；右 7：结果。
- `ResultPanel` 接收当前 mode、四个 mutation 的必要状态和数据。
- check banner、输入摘要、expand path 分层；expand 错误不改变 check 的 allow/deny。
- 三种模式各有独立未执行、空结果、错误视图，但不引入新的业务状态库。

### 其他页

- Overview 变成任务入口：核心双卡优先，身份信息与统计缺口降权。
- Schema 拆出 `SchemaTypeCard`，relation/permission 使用统一 SemanticTag。
- Audit 用 page toolbar + Table 容器，避免外层重 Card；只展示现有响应字段。
- Sync/Spaces 使用统一状态原语，明确能力边界。

## 4. 改动范围

- 现有文件约 14–18 个。
- 新增 theme、layout/common/domain 原语以及 grants/playground 子组件，约 10–14 个文件。
- `package.json` 只因测试工具调整；无新 UI 运行时依赖。
- API、auth、store、配置与数据结构保持不变。

## 5. 扩展性

- 未来加 directory selector、批量授予、结果复制、详情 Drawer 时，可在面板层扩展而不扩大页面容器 JSX。
- 子组件 props 是内部 UI 契约，需避免把整份 mutation 对象直接下传；建议定义最小 props。
- 不引入跨页状态和 URL 参数，扩展到“授予后跳调试并自动带参”仍需后续设计。

## 6. 实施成本

- 预计 7–10 个开发日 + 3–4 个测试/视觉验收日（单人粗略估算，待校准）。
- 需要先明确组件边界与 props，但回报是核心页可测试性和维护性更好。

## 7. 优点

1. 任务流、信息层级和工程边界同时改善。
2. 保留页面容器现有业务逻辑，避免过度架构。
3. 核心面板可独立做组件与视觉测试。
4. 对未来核心页增强有足够承载力。

## 8. 弱点

1. 文件数和 props 设计成本高于方案 A。
2. 拆分时容易把现有 handler 语义误拆或造成重复 state。
3. 若只追求一次性换肤，投入可能偏高。
4. 不解决跨页上下文和刷新恢复问题。

## 9. 失败场景与控制

- 失败：子组件自行发请求导致缓存/错误处理分裂。控制：明确 page container 是唯一数据编排者。
- 失败：props drilling 过多。控制：核心页最多两层；不为避免 props 提前引入 Context/Zustand。
- 失败：拆分与视觉同时进行难以定位回归。控制：先做“无视觉变化的呈现拆分”（可选），再套新样式；或至少用测试 fixture 锁定事件 payload。
- 失败：导航分组导致路径/选中态回归。控制：Menu key 仍使用现有绝对 path，并对 7 个路径逐一回归。

## 10. 适用条件

如果核心页未来仍会持续增强，且团队愿意为可维护性投入一个完整迭代，B 是平衡最好的候选。
