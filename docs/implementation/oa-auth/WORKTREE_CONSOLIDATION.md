# auth worktree 收敛记录

用户要求 P1 做完后暂停，并将 auth worktree 合并，最终只保留 auth-platform 主目录。目录收敛已完成；P1-00—P1-07 当前均已通过验收，最终 Git 交付另行记录，不推进 P2。

## Git 与文件证据

收敛前主分支为 8c7945d，以下分支均无 main 之外的提交：

- auth-platform-erp-oidc，f02ff04。
- auth-platform-oa-unification，f18e05e。
- auth-platform-p1-identity，8c7945d。
- wms-platform/.local/auth-wms-oidc，dbf2ca4；这是 auth 仓库的已合并工作树，未改 wms 项目本身。

使用正常 `git worktree remove`，没有 force、reset、丢弃未提交产品代码或删除任务分支。最终 `git worktree list` 只有 `/Users/liruijun/personal/LLM/auth-platform`；同级 `auth-platform*` 目录也只有主目录。后续复用主目录中的 feat/oa-auth-p1-identity 分支，最终正常合并回 main。

14 组、224 个保留文件完成 SHA-256 和文件权限逐文件比对，包含私密配置、历史测试记录、报告及旧 JAR。校验清单位于忽略目录 `.local/worktree-consolidation/copy-verification.json`；原检查点另行归档。原 target 下编译产物可重新构建，测试报告已经保留。

P0 的 OA/commerce 只读基线通过各自仓库的 `git worktree move` 迁至主目录 `.local/p0-baselines/`，HEAD 分别仍为 f07c978/919081b；没有修改这两个项目的当前代码或用户工作树。它们不是额外的 auth 工作目录。

## 隔离环境迁移

专用 Casdoor 18090 的旧宿主 bind mount 指向即将移除的目录，因此迁到固定镜像、原生 UID 1000 的任务私密配置卷。保留同一专用数据库和认证资料；discovery/版本 API 验证 v4.11.0 PASS。旧容器日志以 0600 保存后才移除已停止的替代前容器。共享 Casdoor 和其他共享组件没有改变。

`.local/governance`、`.local/governance-ci-runtime`、`.local/p0-evidence` 和归档需保留，含凭据及后续 P1 验证资料，不提交远程。无清库、账号迁移或生产部署。本记录保存目录收敛及配置迁移事实；P1验收见 phase-1/P1-07_TEST_RESULT.md。
