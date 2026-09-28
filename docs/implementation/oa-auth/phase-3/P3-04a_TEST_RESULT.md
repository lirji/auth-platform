# P3-04a TEST_RESULT

结论PASS（远端CAS协议片）；基线588b0a1。不等于多执行器/kill恢复已完成。

- 真实SpiceDB v1.56.2+dev_infra PostgreSQL16独立图18544，固定digest沿P2；新数据库/受限账号/.local/governance/p3/graph 0600配置。共享8543和P2 18543未改变。
- `GOVERNANCE_P3_GRAPH_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-projection-it verify` exit0，ProjectionCasIT 4项PASS：迟到旧marker写不能复活撤权；不保存返回token后从真实readAt恢复；初始化不得覆盖已有marker；多marker保持隔离不自动删除；关系失败不推进marker。
- `./mvnw -q -pl auth-platform-core -am test` exit0，新增协议测试5项PASS：前置条件、缺token/尾随JSON/错误流/重复字段拒绝、响应头后body卡住总超时、512KiB响应上限、远端明文HTTP拒绝。日志cas-unit-final.log。
- red先缺少适配器编译失败；首次实现括号编译错误修正后真实4项通过，日志cas-{red,graph,graph-fixed}.log保留。
- 独立图脚本新增--phase p3，默认P2不变；首次启动和再次执行都PASS，不覆盖已有schema。CI增加专属图和profile，目前远程CI待阶段交付。
- Code Hygiene COMPLETE_WITH_LIMITATIONS：仓库无Java formatter，未配置静态分析器；512KiB为已命名响应上限常量（工具ADVISORY）。failsafe是既有插件，本片仅新增隔离profile，没有新依赖。
- 远端前置条件不代替管理权限；不允许任意业务更新marker资源类型。旧payload不能自行更换expected的持久化约束由P3-04b负责，本片只有端口契约和实际远端拒绝证据。
- P3-04c仍需真实两个进程暂停/恢复和图成功后kill、SQL回执补偿；不能使用本片丢弃返回值用例冒充kill验收。
