# P3-04c TEST_RESULT

结论PASS；基线4c9801c，生产算法与该commit相同，本片增加真实进程故障证明。

- `GOVERNANCE_TEST_CONFIG=<0600> GOVERNANCE_P3_GRAPH_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-projection-it verify` exit0；真实投影12项PASS（CAS4、可靠批次6、进程故障2）。新进程测试35.85秒，日志process-faults.log。
- 暂停：旧worker子JVM请求进入自有HTTP代理后SIGSTOP，等待数据库真实租约到期14988ms；另一子JVM应用新撤销并READY；SIGCONT后代理把真实旧请求送到18544图，HTTP400/gRPC9前置条件失败，图仍DENY，状态仍READY。没有改快SQL租约或Mock远端结果。
- 崩溃：代理已收到真实图写成功200，SQL仍无receipt且UPDATING时SIGKILL子JVM（exit137）。等待真实租约14812ms，另一子JVM读取marker并RECOVERED；operation与receipt各1，最终READY。不重新生成或重放payload。
- 私密配置/完整日志在.local/governance/p3/process-tests；脱敏PID/等待/结果证据入evidence/projection-process-faults.json。所有本测试子进程已终止，代理已关闭，PG/图保留复验。
- 代理只控制自有请求时序，所有关系/marker/CAS实际由锁定SpiceDB执行；结果未知和SQL失败恢复由生产ReliableProjection执行，无测试开关写产品状态。
- P3-04a/b/c必需项现已全部PASS，P3-04汇总DONE。P3-05严格批量读、P3-06组/receipt、P3-02业务范围和P3-07端到端/性能仍未完成。
- 本片没有生产源码变化，沿用4c9801c Code Hygiene限制（缺Java formatter/静态分析器）；测试源码与diff复核通过。未生产部署。
