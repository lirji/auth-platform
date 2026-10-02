# MODAL-UX-01 权限控制台统一弹层

## 范围与实现

用户于2026-10-01确认改造全部业务及移动导航抽屉，优化弹层布局，并更新本地5273；Git任务分支、正常合并及main推送沿用持续授权。

10个业务抽屉及治理移动导航统一为 `GovernanceModal`；旧工作区移动导航改为居中 AntD Modal。原邀请撤销弹层也采用同一外观。源码不再使用 Drawer、DrawerProps、GovernanceDrawer 或抽屉样式；依赖版本保持锁文件原值。

弹层标题、关闭按钮及底部动作固定，正文独立滚动；角色640px、表单/策略720px、授权来源760px、申请详情800px、清单960px、导航480px。桌面左右至少24px，560px以下左右12px，视口高度限制使用dvh和vh回退。清单预览/发布及邀请撤销动作移到底部，移动按钮可换行。

关闭调用原业务处理器，保留脏表单确认及一次性邀请证明保存提醒；提交中/结果未知时同时禁用关闭按钮和Esc，原命令重试逻辑保持。身份、组织/应用分区、API、权限与URL上下文未变。

## 本地验证

- `pnpm --dir auth-console install --frozen-lockfile --offline`：168包复用本地缓存，依赖/锁文件无变动。
- `pnpm --dir auth-console build`：TypeScript与Vite PASS。
- `node --test auth-console/tests/governance-context.test.mjs`：6 PASS，0失败。
- `git diff --check`：PASS；`rg -n -i 'drawer|抽屉' auth-console/src`：无匹配。
- code-hygiene gate：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；无既有formatter，未新增依赖，类型检查和差异卫生通过。
- 专用控制台Docker镜像构建 PASS；运行HTML及全部引用的JS/CSS字节一致、5273/healthz返回ok。

验证日志/源码摘要/运行更新记录：任务工作树 `.local/console-modal-experience/`。证据与备份均忽略，不提交生成制品或运行配置。

## 真实Chrome交互与截图

| 页面/入口 | 实际检查 | 结果 |
|---|---|---|
| 角色差异 | 真实store_reader v1、引用1、新增能力与版本说明；居中显示；Esc关闭并把焦点返回“查看差异” | PASS |
| 创建角色 | 空提交显示编码/能力必填；输入后Esc弹放弃确认；继续编辑保留输入；放弃后返回列表，未创建角色 | PASS |
| 授予成员 | 真实成员查询、角色/范围/期限字段；长正文滚动到底，标题与提交区保持可见；关闭返回列表 | PASS |
| 申请策略创建 | 角色、范围、审批人和期限字段及底部操作；关闭返回空策略列表 | PASS |
| 外部邀请创建 | 发行方、精确身份、类型/期限/原因与底部按钮；未提交实际邀请 | PASS |
| 清单预览/发布 | JSON正文与关闭、预览、发布按钮；空输入禁用两项动作 | PASS |
| 申请权限 | 当前无可申请策略，说明完整，提交禁用，关闭可用 | PASS |
| 来源详情深链 | 不存在的来源显示当前页未找到提示；关闭移除grant参数并保留组织/应用/环境 | PASS |
| 申请详情错误路径 | 不可访问的申请显示权限错误；刷新详情、重试查询、关闭入口可用 | PASS |
| 治理移动导航 | 390×844打开居中菜单，全部导航/关闭可见；同页“授权管理”点击后菜单关闭，焦点回到打开导航按钮 | PASS |
| 手机表单 | 390×844、320×844角色表单：左右边距、字段、关闭/提交均可见 | PASS |

桌面截图1440×900保存在 `desktop-role-detail.jpg`，390手机截图为 `mobile-role-editor.jpg`、`mobile-navigation.jpg`；均来自当前5273真实产品并实际查看。初轮原生Chrome完成交互；扩展连接恢复后绑定控制台标签，保存截图并复核同页导航。测试结束已重置临时视口。

当前数据没有真实策略、申请详情、本人业务Grant或待撤销邀请记录，因此这些完整数据/提交结果路径未做业务写入验收。旧工作区移动导航仅类型构建/源码审查，未做旧工作区登录实操；完整辅助技术、故障注入及真实提交中/unknown路径未执行。本次确认关闭保护接线全部保留，不以源码审查替代这些运行测试。

## 本地运行与恢复

仅构建前端镜像 `auth-platform/governance-console:modal-experience`，并把其静态assets/HTML复制到现有 `auth-governance-console`；默认 `:local` 标签指向新版镜像，后续重建继续使用新制品。未重建共享回环网络容器，后端、projector、数据库与授权记录保持。

旧HTML保留在 `previous-html/`，旧镜像保留为 `auth-platform/governance-console:pre-modal-3e30c26`。必要时可先恢复旧assets再恢复旧HTML；后续重建前先把默认镜像标签恢复到旧版本。未删除旧镜像/证据或用户文件。

## 交付

基线3e30c26；任务分支 `feat/console-modal-experience`，唯一工作树 `~/.local/share/git-worktrees/auth-platform/console-modal-experience`。原目录PORTAL-UX-01改动由该工作树隔离；无子Agent。产品与设计/README在完整逻辑提交0be6cec中，整合已发布门户main的提交f6c3f54678d11601a521e2a65060074af80faf9d已正常推送任务分支及main。

精确[Auth Platform CI36949615700](https://github.com/lirji/auth-platform/actions/runs/36949615700) SUCCESS，headSha与f6c3f54一致，含既有后端集成/HTTP边界/故障恢复/SDK兼容及本次控制台构建。原项目目录main已干净快进对齐。最终收尾为纯交付文档，产品源码和锁文件13项摘要仍与最终本地验证一致，不以新文档提交冒充新的产品CI运行。

最终Git事实记录在根 `CODEX_PROGRESS.md` 的MODAL-UX-01节，远程CI结果保留 `.local/console-modal-experience/ci-result.json`。本任务工作树、依赖、制品和恢复备份保留，无清理授权；其他既有工作树未触碰，无未提交或未跟踪的任务文件。
