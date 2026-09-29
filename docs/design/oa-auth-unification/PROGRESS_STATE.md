# 企业 IAM 当前进度

## 当前状态

P4_VALIDATED_DELIVERY_PENDING。最新用户授权“继续做P4”，完成P4后正常Git交付，P5前停止，不生产部署。原63节点DAG不变。

## 已完成

P0至P3历史交付见phase-3/P3_DELIVERY_RESULT。P4-01至07 DONE：不可变申请、真实OA幂等启动、可信回调/持久Inbox/冲突隔离，证据分别见phase-4对应TEST_RESULT。

## 当前工作

P4 G4真实链路已通过；当前正常Git交付和远程CI待完成，P5未授权。

## 环境与交付

auth基线9140426，OA基线4ea8be9；两仓原目录分支feat/iam-p4-oa-access-lifecycle。仅本任务分批本地提交，阶段最终合并/推送尚未执行。OA四项用户改动保留，不改其恢复文档。专用auth PG至V14；专用OA PG包含V25/V26（沿用模块迁移序列outOfOrder）。

P4使用独立Kafka/Flowable/PG完成真实审批，未改共享来源信任；所有本轮应用进程已由脚本退出。隔离卷与私密复验配置保留。共享Casdoor升级、生产容量/保留期限限制继续保留。
