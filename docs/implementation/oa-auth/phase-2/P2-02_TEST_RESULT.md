# P2-02 固定角色与直接授权验收

PASS。实际命令 `GOVERNANCE_TEST_CONFIG=<私密配置> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-it verify` 退出0。

新增AccessPostgresIT 7项真实PG测试通过，既有目录/身份/邀请/清单共51项回归通过；unit72项通过。覆盖固定版本数据库不可变、来源独立撤销、无管理权限/自授予/范围/期限/跨租户/跨环境/代际越权拒绝、管理能力上限、并发同键唯一、改体冲突、审计失败完整回滚、停用管理者拒绝。

实现：V7 tenant_application/access_delegation/role_version/access_grant/grant_projection；表与列中文注释；Mapper XML；当前成员校验；事务命令/审计；授权初态PENDING；撤销立即REVOKED，可靠意图待P2-04处理。V1–V6未修改。

限制：本片不声明图已写入、HTTP或UI完成；接口片P2-03继续。无统一formatter/独立静态分析器；最终Code Hygiene单列。私密日志.local/governance/p2/p2-02-pg.log。
