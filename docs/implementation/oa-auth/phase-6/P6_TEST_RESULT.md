# P6 实施与验证结果

范围：已选commerce-platform运营租户的本地隔离演练。实现与本地最终验证PASS。auth指定reactor单测233项及真实PG/SpiceDB综合IT1项通过；commerce完整reactor393项（388通过、5个可选性能测试跳过）；Python17项；跨进程31项。源码摘要见P6_CODE_EVIDENCE.json。最终两仓远程CI全部PASS，精确提交及运行链接见CI_RESULT.md。

| 验收 | 证据 |
|---|---|
| 只读来源、精确映射、未知隔离、保留到期/撤销 | Python17项；P6_SOURCE_UNCHANGED.json |
| 不含Token的持久执行引用、同Grant/目录版本/调用方绑定 | ExecutionAuthorizationIT真实PG+SpiceDB；SDK真实HTTP协议测试 |
| 导入幂等、增量版本、失败回滚、拒绝墓碑 | 同一PG/图IT以及MigrationImportCli真实跨进程重放 |
| 完整经营、HTTP拒绝、后台推进、事务切换锁 | CentralCatalogMySqlTest真实MySQL5项 |
| 服务边界依赖 | commerce architecture-tests |
| 实际影子、冻结、切换、重启、worker撤权、回退和故障 | P6_REHEARSAL_RESULT.json，31项PASS |
| 原运行商城未改 | 原8602容器running/healthy；选定来源四表前后逐字段相同 |

事务复核：执行引用在远程判权后短事务持久化，使用时再次实时检查；导入检查点/Grant/审计/投影意图在单条同一事务；route FOR SHARE在业务事务内阻止切换越过在途旧请求。不存在中央授权和商城业务提交的分布式原子承诺。

限制：无仓库统一formatter，静态分析未配置；只按既有风格和架构测试检查。性能profile的5项测试未开启，不能宣称生产容量或SLO。P6-07报告明确生产HOLD；P7未执行。

两仓Code Hygiene均IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，限制为缺统一formatter/静态分析未配置；事务检查结论见本报告。未通过删除测试、放宽断言或修改共享中间件配置来取得PASS。

最终精度复核：执行到期时间契约为UTC微秒，SDK对纳秒输入规范化，服务端拒绝超精度直接调用，避免PostgreSQL持久化后幂等响应变化。指定reactor及真实PG/图IT重新通过；SDK补充纳秒输入回归通过。
