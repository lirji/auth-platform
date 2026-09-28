# P3-01 TEST_RESULT

- Slice：P3-01；结论PASS（实现规范COMPLETE_WITH_LIMITATIONS）。
- 基线：auth 3e49644；本任务分支feat/oa-auth-p3-consistency。
- 新ScopeRulesTest 4项PASS：read全范围与refund单店不交叉；路径内AND/路径间OR；租户隔离；未绑定类型/版本/重复与无界集合拒绝；防御复制及缺事实拒绝。
- 真实PostgreSQL16：`GOVERNANCE_TEST_CONFIG=<0600配置> ./mvnw -q -pl auth-platform-governance,auth-platform-admin -am -Pgovernance-it verify` exit0，61项（Identity20、Invitation9、Directory17、Catalog5、Access10）。新增固定范围重放/不可变、半条路径与跨分区FK拒绝、审计失败回滚3项。
- 首次red因ScopeRules尚未实现编译失败；随后测试夹具101项数组含null，在构造List.of时提前失败，已改为101个不同ID后通过；没有放宽业务断言。
- 最终命名常量调整后 `./mvnw -q -pl auth-platform-governance -am test` exit0；未改变已验证SQL/事务。日志保留.local/governance/p3/scope-{red,unit,pg,final-unit}.log。
- Code Hygiene通过；仓库无Java formatter（FORMAT_TOOL_NOT_AVAILABLE），未配置静态分析器。SQL参数绑定、全部新表/字段中文注释、公开方法中文注释已复核。
- 当前实际绑定store_record的租户内store_id；SELF/部门/供应商未有真实Owner字段绑定时SCOPE_UNSUPPORTED。细范围不会被旧TENANT_ALL路径识别。ScopeRule尚不等于可复用ScopePlan或完整业务接入，后续P3-02/03/05继续。
- N/A：本片无可见UI变化。未宣称图CAS、双实例或业务导出通过。
