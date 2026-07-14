# 05 · 页面清单

七个页面各自的布局骨架、栅格与状态处理。所有页面都以 `PageHeader` 起头,数据区套用
[03 · 状态与反馈](./03-state-and-feedback.md) 的四态原语,业务逻辑/请求时机/payload 未变。

| 路由 | 文件 | 分组 | 定位 |
|---|---|---|---|
| `/` | `OverviewPage.tsx` | 工作台 | 身份 + 两大核心入口 |
| `/grants` | `GrantsPage.tsx` | **核心操作** | 授予/撤销关系元组 |
| `/playground` | `PlaygroundPage.tsx` | **核心操作** | 实时判定 + 反查 + 判定路径 |
| `/schema` | `SchemaViewerPage.tsx` | 资源与模型 | `.zed` 授权模型可视化 |
| `/spaces` | `SpacesPage.tsx` | 资源与模型 | 占位(规划中) |
| `/sync` | `IdentitySyncPage.tsx` | 系统治理 | Casdoor → SpiceDB 组同步 |
| `/audit` | `AuditPage.tsx` | 系统治理 | 操作审计表 |

---

## ① 概览 Overview

- 顶部两张 `TaskCard`(授予管理 / 权限调试器),`Row` 栅格 `xs=24 md=12`,`hoverable` + 键盘可达(见 [06](./06-accessibility.md))。
- 「当前身份」`Card` + `Descriptions`(`column={{xs:1, md:3}}`):用户 / 用户 id(sub,等宽)/ 授权组 `Tag`。
- viewer(非 admin)显示 `Alert info` 说明只读边界;底部 `Alert warning` 标注「规模统计暂无(SpiceDB 无 count-all,后端待补聚合端点)」——**如实标注,不臆造假数字**。

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
