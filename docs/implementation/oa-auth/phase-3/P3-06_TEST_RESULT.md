# P3-06 TEST_RESULT

- 结论PASS；基线d753abc。V12已真实执行，历史迁移不修改。目录组来自P1 DirectoryEvents消费事务；独立组受益方与固定范围、租户复合外键、持久墓碑批次、应用拥有者能力停用、受理/完成回执和审计重试均已实现。
- `GOVERNANCE_TEST_CONFIG=<0600> ./mvnw -q -pl auth-platform-governance,auth-platform-admin -am -Pgovernance-it verify` exit0，67个真实PG用例通过；日志group-pg.log。
- 真实图profile运行中CAS4、可靠worker6、实际子进程暂停/kill恢复2项通过。初次新增授权IT暴露SQL括号错误及双水位保守DENY后的测试假设，修复后定向ReliableAuthorizationIT 10项exit0；日志group-full-graph.log（保留初次失败）、group-final-authorization.log（最终通过）。不以失败的整轮命令宣称全轮exit0。
- 新增5个真实PG/图用例：OA目录任职迁移时图仍旧允许而栅栏阻断；迁移后旧边删除且独立Grant仍生效；离职再入职代际推进及REVOKED组来源不复活；未配置时区/排他截止/未来任职/DOTTED不授权；拥有者紧急停用原子推进两个环境分区且命令审计幂等；撤权先PROCESSING后真实COMPLETED、未知marker BLOCKED且审计retry不能覆盖。
- P3-05原有到期无清理、同Grant范围、角色扩展、双Runtime持久水位及C新快照5项继续通过。双水位允许图快照尚未追平时保守DENY，新授权验证有10秒上限等待；撤权拒绝断言不等待、不放宽。
- Code Hygiene COMPLETE_WITH_LIMITATIONS：未配置Java formatter及静态分析器；编译、单测、差异检查通过。管理HTTP真实双身份验收和商城消费继续归P3-02/07；未声明生产或完整P3已完成。

SKILL_HANDOFF: status=COMPLETE; gate=PASS; produced=P3-06_TEST_RESULT.md; unresolved=无本片阻塞; downstream_requirements=严格HTTP/ScopePlan/资源Owner与商城验证; recommended_next=P3-02。
