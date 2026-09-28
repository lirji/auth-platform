# 企业 IAM 当前进度

## 任务目标

用户已新授权「做P3」。完成P3全部10节点并正常合并推送main后暂停，不进入P4或生产部署；原63节点DAG不变。

## 当前状态

P0/P1/P2已交付；P3执行中。P3-01 DONE，证据见phase-3/P3-01_TEST_RESULT.md。当前P3-03 IN_PROGRESS；它与P3-02都已满足原前置依赖，先完成版本栅栏以支撑ScopePlan。其余P3节点TODO。

## 已完成

- P2交付见phase-2/P2_DELIVERY_RESULT.md，基线auth3e49644、commerce d7c9e3c。
- P3-01：固定范围快照、受控管理入口、同Grant语义和store字段绑定；4个新规则单测及61个PG集成用例通过。

## 未完成与下一步

P3-03主库A/C新快照、策略/目录栅栏；随后P3-04a/b/c和05/06完成投影/读路径，P3-02完成ScopePlan与真实业务列表/导出，P3-07集中故障及性能验证。每片保持原DAG依赖，不把未验证结果写DONE。

两仓原目录任务分支已创建。OA用户修改未动，共享Casdoor升级HOLD保留。仅使用隔离测试资源，无生产部署；仓库无Java formatter/静态分析器的现有限制保留。
