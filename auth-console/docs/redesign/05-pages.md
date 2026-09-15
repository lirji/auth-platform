# 05 · 页面清单

七个页面各自的布局骨架、栅格与状态处理。所有页面都以 `PageHeader` 起头,数据区套用
[03 · 状态与反馈](./03-state-and-feedback.md) 的四态原语,业务逻辑/请求时机/payload 未变。

| 路由 | 文件 | 分组 | 定位 |
|---|---|---|---|
| `/` | `WorkspaceHome.tsx` | 工作台 | 登录后按身份选择授权工作区 |
| `/w/:id` | `WorkspaceOverview.tsx` | 工作台 | 当前项目内的授权入口 |
| `/w/:id/grants` | `GrantsPage.tsx` | **核心操作** | 授予/撤销关系元组 |
| `/w/:id/playground` | `PlaygroundPage.tsx` | **核心操作** | 实时判定 + 反查 + 判定路径 |
| `/w/:id/schema` | `SchemaViewerPage.tsx` | 资源与模型 | `.zed` 授权模型可视化 |
| `/w/:id/spaces` | `SpacesPage.tsx` | 资源与模型 | 空间/知识库成员 |
| `/w/:id/sync` | `IdentitySyncPage.tsx` | 系统治理 | Casdoor → 默认 SpiceDB 组同步 |
| `/w/:id/audit` | `AuditPage.tsx` | 系统治理 | 当前工作区操作审计 |

---

## ① 工作区选择 / 项目概览

- `/` `WorkspaceHome`：可见工作区多于一个时展示卡片选择（键盘可达）；仅一个则直进该项目 `home`。
- `/w/:id` `WorkspaceOverview`：当前项目名称 + 该工作区开放的授予/调试入口卡。
- 工作区内侧栏按 `features` 过滤；顶部可切换工作区。

## ② 授予管理 Grants(核心)

- **左 14 : 右 10** `Row`(`Col xs=24 lg=14 / 10`)。
- 左卡:`Segmented`(授予/撤销)+ 竖排表单(资源 / 关系 / 主体);group/organization 主体时出现 `#member` Switch;
  底部 **实时元组预览卡**(`colors.bgSubtle` 底 + `TupleText`)展示「即将写入的关系元组」;提交按钮撤销态 `danger`。
- 右卡「现存授予」:`listRelationships` 结果 `List`,每条 `TupleText` + `Popconfirm` 快速撤销;
  **error → `ErrorState` + 重试;loading 仅首次不遮旧数据**;未填资源 id 时 `List.locale` 提示。

## ③ 权限调试器 Playground(核心)

- **左 10 : 右 14** `Row`。
- 左「输入」卡:`Segmented`(判定 Check / 反查资源 / 反查主体,`block`)+ 按模式显隐的主体/资源/权限字段;
  check 模式有「用我自己」快捷填充。
- 右「结果」卡(`minHeight: 360`):**三模式各自独立四态**(见 03 的 per-mode 范式):
  - Check:`AllowDenyResult` 大卡 + 「判定路径(展开)」子卡(`expand` 独立 loading `Spin` / error `Alert` / data `Tree`,`.scroll-x` 局部横滚);结果上方一行**输入摘要**(`subject · 权限 · resource`)。
  - 反查资源/主体:`List bordered` + `RefBadge`;反查主体附 caveat `Alert`。

## ④ 授权模型 SchemaViewer

- `PageHeader` extra 放 `Segmented`(类型卡片 / 原始 .zed)。
- `PageSkeleton` / `ErrorState` 打底;`cards` 视图用 `Row` 网格(`xs=24 md=12 xl=8`)铺 `SchemaTypeCard`。
- **解析为空**(`defs.length===0 && q.data`)→ `EmptyState`「未解析出类型定义」+ 「查看原始 .zed」按钮(切 `raw`)。

## ⑤ 空间/知识库 Spaces

- 占位页:`EmptyState`「规划中」。保留入口,后端补 space 列表端点后再落地。

## ⑥ 身份同步 IdentitySync

- 单栏聚焦卡(`maxWidth: 680`):「立即同步」按钮(`loading`)。
- `sync.isSuccess` 后出三 `Statistic`(处理组数 / 新增 `colors.success` / 移除 `colors.error`);
  `isError` → `Result warning`。**去掉了 onError toast**,避免与 Result 双重反馈。

## ⑦ 审计日志 Audit

- `PageHeader` extra 放「刷新」按钮(`loading={isFetching}`)。
- `Table`:`scroll={{x:720}}`(**窄屏横向滚动不撑破**)、`locale.emptyText`「暂无审计记录」、`pagination pageSize:20`;
  列:时间 / 操作人 / 动作(`Tag` 色分 grant/revoke/其他)/ 关系元组(等宽 `code`)。
- 描述如实写「内存环形最近 500 条,重启清空」(对应后端 `AuditStore` CAP=500)。
