# 测试方案与验收标准（test-designer）

## 1. 测试基线与原则

当前仓库没有测试依赖、脚本或测试文件。本计划建议实施时新增 Vitest + React Testing Library + user-event + MSW；Playwright 用于关键 E2E/视觉回归，是否接入 CI 待验证。测试重点是证明“视觉重排没有改变真实业务 payload、鉴权和状态语义”。

不以快照覆盖全部 DOM；优先语义查询、事件 payload、可见状态与少量稳定视觉截图。

## 2. 建议测试分层

| 层级 | 工具 | 目标 |
|---|---|---|
| 单元 | Vitest | theme/导航元数据、lexicon、parser/tree 现有纯函数 |
| 组件 | RTL + user-event | Select/Tag/状态原语/核心呈现面板 |
| 页面集成 | RTL + MSW + QueryClient | API 契约、状态切换、写后刷新、错误反馈 |
| 路由/壳层 | MemoryRouter/BrowserRouter 测试 | 导航、面包屑、折叠与移动 Drawer |
| E2E | Playwright | 7 路由、核心任务、响应式和键盘操作 |
| 视觉回归 | Playwright screenshot | 1440/1024/390 三档关键页面 |

### 2.1 三个候选方案的差异化测试投入

| 候选 | 在共同回归之外必须增加 | 退出标准 |
|---|---|---|
| A 渐进视觉整顿 | 全局 CSS 污染检查、每页 DOM/行为前后对照、固定宽度移除后的 4 viewport 截图 | 7 页无 Antd 样式串扰；现有 handler/API 请求计数不变 |
| B 任务型工作台 | Grant/Relationship/Query/Result 面板 props 契约、事件 payload、容器与呈现层职责测试 | 子面板不直接调用 API；页面容器完整保留 state/query/mutation；核心 payload 精确匹配 |
| C 上下文权限工作台 | URL parse/serialize、非法参数、前进后退、旧链接、ID 泄露评审、跨页映射与移动 Inspector | URL 契约有版本/容错策略；未确认映射不自动选择 permission；安全评审通过 |

若最终按本文推荐选择 B，应执行共同回归 + B 专项；A 的 CSS 污染与 viewport 测试仍作为实施策略被吸收。C 未入选，其 URL 专项不应转化为本期实现工作。

## 3. 单元测试

### 3.1 现有领域逻辑回归

- `relationsFor/permissionsFor`：9 种 ObjectType 的现有映射不变；relation 与 permission 互不串用。
- `parseZed`：正常 definition、注释、多个 definition、relation union、permission expr、空文本、未知/不闭合文本；页面对空结果降级。
- `expandToTree`：空响应、leaf、union/intersection/exclusion、缺字段；生成可渲染节点且不抛错。
- `groupsFromToken`：现有 shortName 行为若测试基础设施覆盖 auth；重设计不改变它。

### 3.2 新设计原语

- `appTheme`：关键 token 值固定，组件 token 不覆盖错误状态色。
- `RelationTag/PermissionTag`：同色场景仍可通过 `R/P` 文本或可访问标签区分。
- `PageHeader`：title、description、breadcrumb/extra 插槽语义正确。
- `AsyncState`：loading/empty/error/success 不同时出现；error retry 可触发。
- `AllowDenyResult`：allowed true/false 有文字与 icon/aria，不只依赖颜色。

## 4. 核心组件测试

### GrantFormPanel（拟新增）

1. 默认资源 space、主体 user 与空 relation 的显示与当前页面一致。
2. 切资源类型调用现有 `onResourceType`，relation 被页面容器清空。
3. user 主体不显示 userset 控件；group/organization 显示。
4. userset 开启时预览包含 `#member`，关闭时不含；是否允许关闭不在视觉测试中擅改。
5. 缺字段提交时显示现有警告，不发 API。
6. grant/revoke 模式按钮文案、danger 语义正确。
7. 超长 ID 在预览中换行但不撑破容器。

### RelationshipListPanel（拟新增）

- 未输入资源：提示输入，不请求或不显示“无数据”。
- 首次加载：skeleton。
- error：显示 `humanizeError` 结果与 retry。
- 空数组：显示“该资源暂无直接授予”。
- 有数据：元组包含 relation/type/id/subject relation。
- 点击撤销传出完整 `Relationship`，尤其 `subject.relation='member'` 不丢失。

### QueryPanel / ResultPanel（拟新增）

- 三模式只显示各自必要字段。
- 资源类型变化清空 permission。
- “用我自己”写入 `user` 与 authStore userId。
- 未选 permission、缺主体/资源 id 不发请求。
- check：ALLOW/DENY、输入摘要正确。
- check success + expand loading/error/success 分别正确；expand error 不改 ALLOW/DENY。
- lookup resources/subjects 空数组与未运行不同。
- subjects caveat 警示在有结果和空结果附近均按产品决定展示；至少有响应时必须显示。

## 5. 页面集成/API 场景

### 5.1 Grants

| 场景 | MSW 行为 | 断言 |
|---|---|---|
| grant 成功 | POST 返回 token；relationships 返回新行 | payload 精确；成功消息；query 被刷新 |
| grant 400/500 | 返回错误 | 不显示成功、不清空输入、错误人话 |
| revoke userset | 捕获 body | `subjectRelation: member` 保留 |
| 列表快速撤销 | revoke 成功 | 当前资源 query 刷新 |
| viewer 403 | POST 403 | 显示“无权限(需 authz-admin)”；不能伪装成功 |
| 快速切资源 | 不同延迟响应 | 最终面板只对应当前 query key |

### 5.2 Playground

| 场景 | MSW 行为 | 断言 |
|---|---|---|
| allow + expand success | check true，expand tree | ALLOW 与路径均显示 |
| deny + expand success | check false | DENY，不使用成功色作为唯一线索 |
| check success + expand 500 | 分离返回 | 主结果保留，路径显示独立错误/不可用 |
| check 500 + expand success | check 失败 | 不把 expand 当权威结果，显示 check 错误 |
| lookup empty | 空数组 | “查询完成，无匹配”而非“尚未运行” |
| lookup subjects | caveated 风险文案 | 风险提示可见 |
| 连续运行 | 先慢后快 | 最终呈现不混淆条件摘要与结果；若现有 mutation 不能保证，记录为待修缺陷 |

### 5.3 Schema/Audit/Sync

- Schema：loading、HTTP error、正常 cards、raw、解析结果为空→引导 raw；长表达式横向/折行符合规范。
- Audit：200 条显示、pageSize 20、手动刷新、empty、error；不出现服务端总数/筛选的虚假描述。
- Sync：pending 禁重复、success 3 个统计、409/500 error、再次点击可重试；页面刷新后不声称有服务端历史。

## 6. 鉴权与路由回归

1. `/callback` 仍公开，其余页面仍在 `ProtectedRoute` 下。
2. 未登录触发 signinRedirect，returnTo 保留 pathname。
3. 无 viewer/admin 显示 403。
4. 7 个 path 的 Menu selectedKey、breadcrumb 与 PageHeader 一致。
5. viewer 的授予/同步在产品决策前保持当前可见性与请求行为；无论后续选择显示/禁用，直接收到 403 都要正确反馈。
6. 账户菜单仍调用 `auth.signoutRedirect()`。

## 7. 响应式与视觉矩阵

| viewport | 页面 | 关键断言 |
|---:|---|---|
| 1440×900 | shell、Overview、Grants、Playground、Schema、Audit | 双栏比例、1440 最大宽、侧栏/顶栏、无截断 |
| 1024×768 | Grants、Playground、Audit | 可折叠侧栏、网格不拥挤、表格可滚动 |
| 768×1024 | shell、核心页 | Drawer 导航或单栏切换点正确 |
| 390×844 | 7 页 | 单栏、16px 边距、主按钮可达、无页面级横向溢出（表格/代码局部除外） |

视觉 fixture 至少覆盖：初始空态、loading、error、成功、长 128 字符 ID、10+ 关系、深层 expand tree、200 条 Audit。

## 8. 无障碍与交互检查

- Tab 顺序：导航 → 页面标题/动作 → 表单 → 结果；Drawer 打开后焦点受控并可 Escape 关闭。
- 所有 icon-only 按钮有可访问名称。
- Segmented、Select、Button 可键盘操作；focus 可见。
- 文本/背景对比建议达到 WCAG 2.1 AA；状态不只靠颜色。
- 200% zoom 下核心操作不丢失；长 ID 不覆盖按钮。
- reduced motion 下不增加非必要动效。

## 9. 性能验收

- 不新增重型 UI 运行时依赖；构建 chunk 不因重设计出现明显异常增长（阈值以实施前 `pnpm build` 产物为基线，当前规划阶段未运行构建，待记录）。
- 200 行 Audit、常见 Schema 卡片、深层 Tree 操作无明显主线程卡顿；建议用浏览器 Performance 人工复核。
- Layout 切换不导致页面 query 无谓重复请求；以 MSW 请求计数验证。
- 图片资源为零或极少；若后续加入品牌图片，需压缩与尺寸约束，本期不计划加入。

## 10. 回归清单

- grant/revoke/check/expand/lookup resources/lookup subjects/sync/schema/relationships/audit 的方法、路径、参数不变。
- Bearer 注入、401 单飞、登录跳转不变。
- `subjectRelation` 保留。
- `resourceType` 变化清空 relation/permission 的现有行为保留。
- Query key `['relationships', type, id]` 与成功失效行为保留。
- raw `.zed` 内容完整显示。
- Audit 列仍为 at/actor/action/detail。

## 11. 明确可验证的完成门槛

1. `pnpm build` 通过。
2. 新增的 unit/component/integration 测试全部通过，核心业务 payload 场景零失败。
3. 7 路由桌面与手机 smoke 全通过。
4. 1440/1024/390 关键截图经设计/产品评审，无阻塞级溢出、错位、层级歧义。
5. ALLOW/DENY、relation/permission 的非颜色区分通过检查。
6. 所有 query/mutation 至少有 loading/error/success；有“未运行”语义的页面另有 idle 状态。
7. 未引入 API、数据库、OIDC、消息结构变化。
8. 撤销确认、viewer 菜单、Spaces 导航三项待确认问题在上线前有书面决策。
