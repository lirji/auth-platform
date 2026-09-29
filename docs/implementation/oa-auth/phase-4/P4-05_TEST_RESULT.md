# P4-05 TEST_RESULT

status=COMPLETED；gate=PASS。

- auth admin reactor verify exit0；RequestPostgresIT 16项 + RequestProjectionIT 1项真实PG/图验证通过。
- 4轮并发取消/批准均收敛：取消先提交无OA Grant，批准先提交只撤销本申请来源；旧视图和同命令重试安全，迟到批准不能恢复；独立DIRECT来源保持不变。
- 取消未启动申请不再请求OA；他人取消拒绝。到期和离职扫描重复运行无重复撤销；重新加入的新generation不能读取或恢复历史申请。
- 真实SpiceDB验证回收前REVOKING、栅栏拒绝，实际删除回执后REVOKED。实时有效期校验沿用P3，不依赖清理任务存活。
- 回收采用每分区最多100条短SQL事务，分区锁+状态版本防双执行器并发；无远程调用。APPROVED保存历史审批事实，执行视图展示回收事实。
- 首轮门禁发现来源魔法字符串，集中为RequestModels.SOURCE_TYPE后重跑必要验证与门禁通过；格式化工具仍未配置。
