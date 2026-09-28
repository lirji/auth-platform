# P3-03 TEST_RESULT

- 结论PASS；基线5ca8e3f。原DAG依赖P2-08已满足；在P3-02之前完成版本基础，不更改依赖。
- `GOVERNANCE_TEST_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-it verify` exit0；67个真实PG用例，其中FencePostgresIT新增6项全部通过。日志.local/governance/p3/fence-pg-fixed.log。
- 验证：未READY拒绝、旧P2读写拒绝严格分区、数据库禁止无确认READY、Grant/审计/epoch同事务回滚、外层旧可重复读事务不能遮蔽C新快照、重新确认后epoch变化仍拒绝、成员/主体/准入/清单变动推进栅栏、普通更新不解除BLOCKED。
- 初次失败来自测试夹具：检查约束覆盖已有审计行、尝试修改不可变能力风险。分别改为NOT VALID只注入新写失败、合法增量清单，重跑通过；产品不放宽任何规则。首次编译误传Catalog额外参数也已修正。
- 事务复核：ReadFence A/C各REQUIRES_NEW+REPEATABLE_READ、5秒，候选同事务；图调用不在本片执行。所有生产SQL在Mapper XML；数据库触发器覆盖既有写入口。V10为增量迁移，V1—V9未改。
- Code Hygiene COMPLETE_WITH_LIMITATIONS；仅FORMAT_TOOL_NOT_AVAILABLE，未配置静态分析器。
- 测试中的占位水位仅证明SQL读屏障，不是图确认或ALLOW证据。真实图水位/CAS/恢复仍属P3-04a/b/c/05，未宣称通过。
- 切换边界：受保护入口必须先路由至升级节点；旧二进制不能识别新栅栏。当前仅Runtime显式启用，未生产启用。
