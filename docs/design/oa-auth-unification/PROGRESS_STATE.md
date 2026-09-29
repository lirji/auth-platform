# 企业 IAM 当前进度

## 当前状态

P4_IN_PROGRESS。最新用户授权“继续做P4”，完成P4后正常Git交付，P5前停止，不生产部署。原63节点DAG不变。

## 已完成

P0至P3历史交付见phase-3/P3_DELIVERY_RESULT。P4-01/02/03/04/05 DONE：不可变申请、真实OA幂等启动、可信回调/持久Inbox/冲突隔离，证据分别见phase-4对应TEST_RESULT。

## 当前工作

P4-06 IN_PROGRESS：状态/站内通知；随后P4-07真实引擎与跨进程故障。

## 环境与交付

auth基线9140426，OA基线4ea8be9；两仓原目录分支feat/iam-p4-oa-access-lifecycle。仅本任务分批本地提交，阶段最终合并/推送尚未执行。OA四项用户改动保留，不改其恢复文档。专用auth PG至V14；专用OA PG包含V25/V26（沿用模块迁移序列outOfOrder）。

共享workflow已启用Kafka来源信任，缺OA来源登记；P4-07须使用受控隔离引擎/总线，不改共享信任。P4-02真实HTTP测试已停止自有OA进程，未发共享Kafka审批命令。共享Casdoor升级、生产容量/保留期限限制继续保留。
