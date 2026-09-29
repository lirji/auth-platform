# P4-03 TEST_RESULT

status=COMPLETED；gate=PASS。

- auth定向verify exit0，RequestPostgresIT 10项全部通过。新增真实PG Inbox、签名原文校验、传输nonce重放、同业务事件新签名重放、同ID改体冲突持久化、旧申请版本/错误审批人持久拒绝。接收不创建Grant。
- OA定向verify exit0，CentralApprovalPostgresIT 4项全部通过；新用例用真实SQL/HTTP接收端验证无引擎完成证据不产事件、有持久实际审批人日志且引擎端口确认完成才冻结事件，503后同一event/body可靠重投。引擎端口在本片测试中为受控测试端口，真实Flowable属于P4-07，不冒充G4。
- 两仓编译/相关单测、OA架构/API golden及Code hygiene通过（IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅无格式化器）。首次auth门禁报告协议魔法状态，已引入封闭Status/Outcome和事件常量。OA新增V26因测试未沿用项目outOfOrder首次失败，修正测试装配配置后通过；没有改已执行迁移或清库。
- 消息生产者、服务签名、实际审批人和申请受益人分开。OA捕获依赖既有真实实例/日志/流程查询，未知或代理办理不猜测资格；回调独立Outbox，租约/五次退避/耗尽可查。
- 下游：P4-04消费已验签Inbox，并与Grant/Scope/投影Outbox/审计同事务提交。P4-05/06/07未完成。
