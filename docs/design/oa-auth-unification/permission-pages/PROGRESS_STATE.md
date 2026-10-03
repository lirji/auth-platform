# 页面权限交付进度

目标：治理控制台5273完成菜单权限、权限目录、角色管理、成员授权和关联页面。

| 切片 | 状态 | 证据 |
|---|---|---|
| GP01 | DONE | 新菜单/目录页面、关联角色、19单测与真实浏览器 |
| GP02 | DONE | 独立角色/授权页面、完整详情、原命令与来源回收验收 |
| GP03 | VERIFYING | 关联页面和隔离验收已PASS，待精确CI、Git交付、本机更新与只读检查 |

GP01/GP02和GP03关联实现共享导航、URL与完整角色索引，作为一个可独立构建回滚的原子功能提交；交付记录单独提交。任务分支 `feat/governance-permission-pages`，原目录串行，无新增worktree或Agent。保护现有数据与私密配置。

详情见 [设计](FRONTEND_ARCHITECTURE.md)、[实施切片](IMPLEMENTATION_SLICES.md)、[验收](TEST_RESULT.md)。

本机local只展示该分区真实已发布目录：2菜单/3能力。完整Commerce 122能力/42菜单/34岗位是此前隔离test成果，当前任务不跨分区搬迁目录或自动授予权限。运行入口 `/governance/menus`、`/catalog`、`/roles`、`/grants`；旧 `/access` 标签页保留。
