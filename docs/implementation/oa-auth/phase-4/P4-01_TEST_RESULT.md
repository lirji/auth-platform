# P4-01 TEST_RESULT

status=COMPLETED；gate=PASS；slice=P4-01。

- 命令：`GOVERNANCE_TEST_CONFIG=<0600隔离配置> ./mvnw -q -pl auth-platform-admin -am -Pgovernance-it -Dit.test=RequestPostgresIT -Dfailsafe.failIfNoSpecifiedTests=false verify`，exit 0。
- 真实PG16 RequestPostgresIT：6测试，0失败/错误/跳过。角色v2不改申请，SQL拒改快照；并发三调用仅一申请/意图/审计；改体409、自批/他人/跨租户拒绝；当前上限和成员截止限制；审计失败回滚；跨分区复合外键拒绝。
- 相关模块编译与单测通过。无UI变更，本片真实HTTP联测随P4-07；不将本片声明为全阶段完成。
- Code Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅FORMAT_TOOL_NOT_AVAILABLE；仓库未配置静态分析。diff --check通过。事务人工复核：分区锁、命令锁、快照/审计/Outbox同本地事务，无远程调用。
- 首轮发现State导入重名编译失败；修复后测试发现断言误用command_receipt而实际表为command_record。均已修复并复验，失败日志保留在.local/governance/p4。

实现范围：protocol RequestDtos；governance RequestModels/AccessRequests/RequestMapper、V13、Runtime；admin GovernanceRequestController。后续P4-02至07尚未验证。
