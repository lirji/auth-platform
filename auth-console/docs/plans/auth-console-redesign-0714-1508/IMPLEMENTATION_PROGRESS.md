# auth-console 重设计 · 实施进度

## 已定决策(用户批准 2026-07-14)
- **测试范围**:砍掉 FINAL_PLAN 阶段4 全套 Vitest/RTL/MSW/Playwright 基线;本次聚焦视觉落地,仅 `pnpm build` + 关键交互手测。
- 待确认项按建议默认:`#member` 保持可关 Switch;快速撤销加 Popconfirm;Spaces 保留"规划中"入口;viewer 保持现有可见性(后端 403 为边界)。
- 落地策略:B(任务型工作台)+ A(低风险分期)。**零变更** API/鉴权/业务逻辑/query key。

## 阶段(测试阶段已砍,合并为 3 阶段)
- **P1 theme + 通用原语**:appTheme、global.css、main 接 theme、AsyncState、PageHeader、SemanticTag、AllowDenyResult、SchemaTypeCard。
- **P2 核心两页**:Grants/Playground 用新原语重排(容器持逻辑不变,呈现委托原语/子面板)。
- **P3 壳层 + 其余页**:AppLayout(浅色分组 Sider+面包屑+响应式)、nav 分组、Overview/Schema/Audit/Sync/Spaces 应用 PageHeader+AsyncState+视觉。

---

## 实施结果(全部完成,构建通过 + dev server 启动正常)

**P1 theme + 原语**:`src/theme/theme.ts`(appTheme:token+Layout/Menu/Table/Card/Segmented components)、`main.tsx` 接 theme、`styles/global.css`(reset + .app-content 最大宽1440居中响应式 + .brand/.mono/.scroll-x)、`components/common/AsyncState.tsx`(PageSkeleton/ErrorState/EmptyState)、`components/layout/PageHeader.tsx`、`components/domain/SemanticTag.tsx`(RelationTag 虚线描边"关系·" / PermissionTag 实心"权限·",非颜色区分)、`AllowDenyResult.tsx`、`SchemaTypeCard.tsx`。

**P2 核心两页**:GrantsPage(7:5 Row/Col + PageHeader + 列表撤销加 Popconfirm)、PlaygroundPage(5:7 + PageHeader + AllowDenyResult + idle EmptyState + caveat Alert)。**逻辑/请求时机/payload/query key 全未变**。

**P3 壳层 + 其余页**:AppLayout(浅色 Sider 224/0 + breakpoint lg 折叠 + brand + 分组 Menu + Header 面包屑/折叠按钮/登出 + .app-content 容器)、nav.tsx(加 group/description)、Overview(突出授予/调试器两任务卡 + 身份 Descriptions)、SchemaViewer(PageHeader + SchemaTypeCard 网格 + PageSkeleton/ErrorState)、Audit(PageHeader + 刷新 + ErrorState)、IdentitySync(PageHeader + 聚焦面板)、Spaces(EmptyState 规划中)。

## 与原计划的差异(有意)
1. **测试**:按用户决策砍掉 FINAL_PLAN 阶段4 全套 Vitest/RTL/MSW/Playwright。仅 `pnpm build`(tsc+vite 通过)+ dev server 启动 + 全 API 序列此前已 curl 验证。
2. **核心页面板拆分**:GrantsPage/PlaygroundPage 采用"就地重排 + 复用共享原语(PageHeader/AllowDenyResult/SemanticTag/AsyncState)+ Row/Col 响应式栅格",**未**拆成独立 GrantFormPanel/RelationshipListPanel/QueryPanel/ResultPanel 文件。视觉结果与计划一致;容器逻辑不变。独立面板文件作为可选结构重构后置(不影响功能与视觉)。
3. **样式隔离**:用小体量 `global.css` + 作用域类(.app-content/.brand/.mono/.scroll-x)+ 主题 token,替代 per-component `.module.css`;未深层覆盖 `.ant-*`,污染面很小。
4. 待确认默认已落地:列表撤销 Popconfirm ✅;#member 保持可关 Switch ✅;Spaces 保留"规划中"入口 ✅;viewer 可见性不变(后端 403 为边界)✅。

## 零变更确认
API(api/authz.ts)、鉴权(auth/*)、hooks、store、router、config、zedParser/expandTree、后端、OIDC、环境变量、消息结构均未改。运行时未新增 UI 库。

## codex-review 后修复(独立审查采纳,构建通过)
- **状态正确性**:Playground 三模式各自独立 idle/loading/error/data(修全局 idle 空白 bug);expand 的 loading/error 可见;`running` 含 `expand.isPending`;check 结果加"输入摘要";错误改持久 ErrorState(去掉与 toast 双重反馈)。Grants 现存列表加 ErrorState+retry,loading 只在首次不遮旧数据。
- **a11y**:Overview TaskCard 加 role/tabIndex/onKeyDown(键盘可达);AppLayout 折叠按钮加 aria-label,账户触发器换成可聚焦 Button。
- **色值收敛**:新增 `src/theme/colors.ts` 单一来源,theme.ts + AppLayout/Overview/Grants/AllowDenyResult/IdentitySync 全部改引用,消除散落硬编码。
- **其余**:Audit Table 加 `scroll={{x:720}}`+空态文案;SchemaViewer 解析为空时 EmptyState+"看原始 .zed";RefBadge 长 ID 换行不撑破;删无用 `Space` 导入、`NAV.description`、`--app-gap`。
- **未同意/已澄清**:Audit "内存环形 500 条"文案属实(后端 AuditStore CAP=500 我亲写);列表快撤 Popconfirm 是已批准决策非违规。

## 后置项收尾(已全部完成,构建通过)
- **移动端真 Drawer**:AppLayout 用 `Grid.useBreakpoint()` 判 `<lg`;桌面渲染可折叠 Sider(224↔72,Header 汉堡切折叠),移动端隐藏 Sider、Header 汉堡打开 antd `Drawer`(遮罩/Escape/焦点由 Drawer 内建;点菜单项自动收起)。菜单抽成 `menu(afterClick?)` 供 Sider/Drawer 复用,零重复。
- **表单 label 语义**:selects.tsx 三个 Select(ObjectType/Relation/Permission)加 `id` 透传到内层 antd Select;Grants/Playground 把 `Typography.Text strong` 伪标签换成真 `<label htmlFor>` 关联主控件,附属 Input 加 `aria-label`,`#member` Switch 加 `aria-label`。屏幕阅读器现可读出字段名。
