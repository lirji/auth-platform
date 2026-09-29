# 企业 IAM 当前进度

## 当前状态

P4_COMPLETE_PAUSED。用户授权“继续做P4”，本轮完成P4并正常Git交付；P5前停止，不生产部署。原63节点DAG不变。

## 已完成

P0至P3历史交付见phase-3/P3_DELIVERY_RESULT。P4-01至07全部DONE：不可变申请、真实OA幂等启动、签名回调/持久Inbox/冲突隔离、Grant投影、取消/到期/离职来源回收、本人状态与可靠站内通知；真实跨进程E2E通过。

## 当前工作

两仓已正常合并推送main；auth与OA远程CI均通过，OA架构扫描误包含CI依赖仓库的问题已修复并通过全量回归。最终结果以phase-4/P4_DELIVERY_RESULT和CI_RESULT为准。P5-01仅为后续交接入口，未启动。

## 环境与交付

auth基线9140426，OA基线4ea8be9；两仓原目录任务分支feat/iam-p4-oa-access-lifecycle，无新增worktree。OA四项用户改动保留，未改其恢复文档。auth迁移V13—V16、OA V25/V26已执行，禁止改历史。

P4使用独立Kafka/Flowable/PG完成真实审批，未改共享来源信任；E2E应用进程已退出，任务专用基础设施停止，卷与私密复验配置保留。共享Casdoor升级、生产容量/保留期限限制继续保留。
