# P3-05 TEST_RESULT

- 结论PASS；基线7a8d166。新增StrictGraphReader与ReliableAuthorization，固定同Grant、主库A/C和持久双水位。
- 先写bulk缺项/错关联/重复/Conditional回归；red因缺方法编译失败，实现后core协议7项PASS（新增2项覆盖多种畸形与乱序响应）。日志bulk-{red,unit}.log。
- `GOVERNANCE_TEST_CONFIG=<0600> GOVERNANCE_P3_GRAPH_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-projection-it -Dit.test=ReliableAuthorizationIT verify` exit0，新增真实PG/图授权5项PASS，日志strict-authorization.log。
- 真实验证read全店/refund单店不交叉、跨租户事实拒绝、两个Runtime读取持久水位、撤销而图仍旧ALLOW时栅栏拒绝、分别使用两个Opaque Token且并发撤销被C新快照发现、到期无清理仍DENY、角色扩展不扩大旧Grant。
- 同一SQL连接Grant/角色/范围，最多101探测后明确超限错误；任何缺项没有部分ALLOW。另一层契约桩缺Map项也整批AUTHZ_PROTOCOL_INVALID。
- Code Hygiene COMPLETE_WITH_LIMITATIONS；无Java formatter/静态分析器。原AuthzEngine九项和P2接口未改；严格分区旧读路径保持拒绝，P3-02接入新ScopePlan接口。
- P3-04c已有真实双子JVM持久marker恢复证据；本片两Runtime验证持久水位，完整auth HTTP双实例/SDK/资源Owner仍属于P3-07，不冒充已完成。
