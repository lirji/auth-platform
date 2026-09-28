# P3-04b TEST_RESULT

- 结论PASS；基线4a0995e。实现固定批次、15秒数据库租约、不可变payload/expected/hash、图后回执、末批desired条件READY和有界退避。
- `GOVERNANCE_TEST_CONFIG=<0600> GOVERNANCE_P3_GRAPH_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-projection-it verify` exit0，真实PG+图10项（CAS4、ReliableProjectionIT6）。日志worker-graph.log。
- 新6项：51Grant必须两批；当前租约阻止另一Runtime；图写期间撤销不能发布旧READY；模拟租约到期后新执行者完成撤销，迟到旧请求CAS失败且不能确认；真实图成功而SQL receipt约束失败后恢复且不重发payload；未知marker隔离且保留原图事实。
- 数据库直接修改payload或expected被不可变trigger拒绝；receipt内容hash复合FK与操作一致，操作分区/批号唯一且每分区只允许一个PENDING。V11已真实迁移，不修改历史。
- `./mvnw -q -pl auth-platform-admin -am package` exit0。实际admin Jar PropertiesLauncher运行ReliableProjectionCli，读取0600自有测试分区配置，exit0/READY；日志worker-cli.log。入口一次一批、30秒硬进程预算，不产生后台无限循环。
- 回执、Grant激活、旧意图结清和游标同事务；只有当前租约可确认，Grant version条件不恢复新REVOKED，desired CAS防并发旧READY。网络位于SQL事务外。
- Code Hygiene COMPLETE_WITH_LIMITATIONS；无Java formatter/静态分析器；枚举建模ADVISORY已用OperationState/Step/Kind明确封闭集合。事务已按实际失败测试复核。
- 本片多Runtime共享真实PG/图，但仍在同一测试JVM；P3-04c必须补真实子进程SIGSTOP/SIGCONT/SIGKILL及真实租约等待，不能拿本报告冒充进程故障通过。
- 管理重试/receipt展示、组目录关系与严格双水位读路径仍在P3-05/06；P3-02的真实商城范围接入未完成。
