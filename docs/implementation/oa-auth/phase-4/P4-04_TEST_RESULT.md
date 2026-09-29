# P4-04 TEST_RESULT

status=COMPLETED；gate=PASS。

- auth admin reactor verify exit0；RequestPostgresIT 13项通过。真实SQL验证重复事件只产一条OA_REQUEST Grant；审计失败回滚Inbox/申请/Grant/Scope/投影意图；审批人上限撤销后拒绝生效。
- RequestProjectionIT 1项真实PG+SpiceDB通过：审批先返回PENDING_APPLY，实际双栅栏和回执完成后ACTIVE；固定S001范围拒绝S002；扩大角色新版本不能扩张原Grant。
- Code hygiene通过（仅缺统一格式化工具）。事务人工核对：单分区锁与Inbox行锁；同库写入原子提交，无远程调用；图投影继续复用P3提交后可靠执行。V15只扩展既有来源约束并增加来源唯一性。
- 修正DAG遗留current_execution_ceiling/pause_after_phase为已授权P4，未改变63节点或进入P5。
- 下游P4-05至07未完成；本片没有宣称真实OA引擎G4完成。
