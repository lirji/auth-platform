# 页面权限交付进度

目标：治理控制台5273完成菜单权限、权限目录、角色管理、成员授权和关联页面。

| 切片 | 状态 | 证据 |
|---|---|---|
| GP01 | DONE | 新菜单/目录页面、关联角色、19单测与真实浏览器 |
| GP02 | DONE | 独立角色/授权页面、完整详情、原命令与来源回收验收 |
| GP03 | DONE | 关联页面、隔离验收、精确CI、main推送、本机更新及39最终视觉检查PASS |

GP01/GP02和GP03关联实现共享导航、URL与完整角色索引，作为一个可独立构建回滚的原子功能提交；交付记录单独提交。任务分支 `feat/governance-permission-pages`，原目录串行，无新增worktree或Agent。保护现有数据与私密配置。

详情见 [设计](FRONTEND_ARCHITECTURE.md)、[实施切片](IMPLEMENTATION_SLICES.md)、[验收](TEST_RESULT.md)。

本机local只展示该分区真实已发布目录：2菜单/3能力。完整Commerce 122能力/42菜单/34岗位是此前隔离test成果，当前任务不跨分区搬迁目录或自动授予权限。运行入口 `/governance/menus`、`/catalog`、`/roles`、`/grants`；旧 `/access` 标签页保留。

2026-10-02 交付完成：产品 `c101b4538160f7e005b25b2ac1a302d0213719f0` 正常合并推送 main；[精确CI](https://github.com/lirji/auth-platform/actions/runs/37095434945) completed/SUCCESS，28步骤通过。5273运行不可变镜像 `auth-platform/governance-console:rev-c101b4538160`，三应用 healthy，原后端镜像保留。

本机14项真实密码/PKCE只读浏览器检查、39最终1440/390/320截图实际查看 PASS；0管理写入、0页面脚本错误、无body横向溢出，桌面/窄屏菜单详情、角色详情与三个表单完整显示。原目录、角色、成员授权、本人应用/组织读取前后一致；匿名401；8静态文件HTTP与镜像逐字节一致；IdP/Graph读取就绪、其余32容器ID未变。

全部最终及早期记录保留 `.local/permission-pages/` 与各隔离 runtime。无本任务新worktree，无清理授权，既有工作树、测试数据库、旧镜像和配置继续保留。未跟踪或忽略的私密证据、构建dist和根CODEX_PROGRESS不加入Git。结果见 [交付记录](DELIVERY_RESULT.json)。无未完成的本任务产品或验收步骤。
