# P4 交付记录

status=COMPLETED；gate=PASS（本地G4及两仓远程CI通过）。范围为原63节点DAG的P4-01至P4-07；P5未授权，未生产部署。

## 功能与验证

固定申请快照→真实OA流程与指定办理人→签名回调/Inbox→唯一OA_REQUEST Grant→真实图回执，审批状态与执行状态分离。支持本人查询与可靠站内通知，取消、到期和离职按授权来源回收，其他来源不受影响。

本地[G4验收](P4-07_TEST_RESULT.md)通过：60次真实HTTP检查，包含外部受邀PARTNER、真实REMOTE Flowable、丢回调ACK重投、图失败恢复、停止OA后的授权/到期、来源隔离。auth全reactor和25项P3真实图/CAS恢复通过；OA全reactor与5项中央审批PG测试通过。既有条件跳过与formatter缺失按原报告保留，没有生产容量承诺。

## Git交付

两个仓库均复用原项目目录的 `feat/iam-p4-oa-access-lifecycle`，正常合并并推送各自origin/main；不强推、不改历史，无新增worktree。

| 仓库 | 起始main | 已推送产品/测试main | 主要任务提交 |
|---|---|---|---|
| auth-platform | 9140426 | b97e1f1348f412cc4dd6ab731ebcbc2331258295 | 6e0c606、59d7bdf、499d976、b581007、52a2eda、b59a95d、5b985ab、40dbcf2、10d7f40 |
| oa-platform | 4ea8be9bfa3643a46f77f41be198142e0867c6d6 | 10c1348de76a8c695e5e2aaf64423fd7682dac49 | 7482e17、251463a、1745937、27dd248、814cd22 |

先发布auth，再由OA CI固定消费auth `40dbcf2ce86d21b83be91b3c9af6111f514ef4df`。auth后续10d7f40仅延长测试夹具到期窗口，未改OA消费产品源码。最终文档提交只更新设计/证据文档，产品、测试、构建及部署脚本树与上述通过CI的auth ref一致；不会把旧run冒充为新文档commit的run。精确CI见[CI_RESULT](CI_RESULT.md)。

首次OA CI失败为架构扫描把`.ci-deps/auth-platform`包含在OA JDBC基线中；814cd22限定到直属Maven模块生产源码并加入临时目录回归，不增加JDBC豁免。

## 保留与清理边界

- OA用户既有`CODEX_PROGRESS.md`、`docs/design/identity-authz-governance/PROGRESS_STATE.md`、未跟踪的同目录`DEPLOYMENT_RESULT.md`和`tmp/`未提交、未改写。任务`.local/`日志与私密配置也未提交。
- auth的忽略项包含`.local/`、本地恢复文档、开发配置、日志、IDE文件和构建产物；没有为“干净”删除用户内容。
- P4专用Compose三容器已停止；卷、专用DB、私密配置和完整失败/通过日志保留，供恢复与复验。共享dev_infra、Casdoor/P3图及其他用户进程未停止或重配。
- `target/`、前端`dist/`、`deploy/__pycache__/`及E2E目录中的复制JAR可重新生成，属于后续可清理候选；本轮不删除。`.local`中的配置/证据与持久卷需保留。
- OA历史worktree `auth-platform/.local/p0-baselines/oa`（detached f07c978）保持原状，作为P0基线保留；本轮未创建或删除worktree。

## 后续

P5-01仅作为下一阶段入口，不在本轮执行。生产TLS/ACL、容量、保留期限、共享Casdoor升级、真实生产发布/回滚及灾备演练继续按后续阶段处理；站内通知已实现，未承诺短信/邮件。
