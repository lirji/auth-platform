# P3-07 故障与性能收尾

Status: DONE；Validation: PASS；远程CI与Git结果另见P3_DELIVERY_RESULT。

真实故障矩阵全部完成，见P3_FAULT_MATRIX.md。故障进程由测试自行启动、只终止持有的PID；未操作共享生产进程。投影执行器真实15秒数据库租约交接、远端CAS拒绝迟到写、SIGKILL后receipt补写均已复验；商城进程80049被强杀，进程80114恢复同一持久任务，55行无重复。双auth节点以共享PG/图和独立JVM执行新请求，非单JVM内存保证。

## 最终本地验证

- auth全仓mvn -Pgovernance-it verify：228单测、67真实PostgreSQL集成，零失败/错误/跳过。
- P3 governance-projection-it：CAS4、可靠worker6、真实进程2、授权12，共24项，零失败。
- 既有P2 governance-graph-it：7项通过；Boot4 SDK兼容通过。
- commerce完整mvn verify：383项，378通过、5既有条件跳过，零失败/错误；最终HTTP状态码语义常量化后重新编译与六项范围MySQL验证通过。
- 真实HTTP检查48项通过；私密原始配置/日志仅.local保存，仓库仅保存脱敏断言、进程ID和性能汇总。
- Hygiene：两仓COMPLETE_WITH_LIMITATIONS，无formatter/静态分析器的现有限制明确记录；没有未解决阻断项。

## 测量范围

| 路径 | 样本/并发 | p50 | p95 | p99 / max |
|---|---|---|---|---|
| 双auth ScopePlan | 60 / 2 | 49.096ms | 62.649ms | 77.182ms |
| 商城范围列表与统计 | 30 / 1 | 122.106ms | 152.322ms | 152.725ms |

55门店、3商品及2条其他租户数据；同一开发机隔离PG/MySQL/图/IdP，包含真实HTTP鉴权。两个新Grant观察到收敛分别299.889ms、101.506ms。分位数nearest-rank；样本很小，包含热运行效应，不是峰值吞吐或生产SLO承诺，也未证明大租户count/search成本。授权撤销以持久栅栏即时拒绝保护，新Grant在双水位都可见前允许保守DENY。

## 审查与边界

没有修改已执行迁移V9—V12/V47。新增资源Owner接口不跨模块写表；服务端构造资源事实，游标/任务绑定身份、代际、策略/目录版本及完整Grant集合；批次与命令回执同事务，下载重读当前版本。已收回跨应用影响的目录时区HTTP入口到受控CLI。

生产容量、保留清理策略和共享Casdoor升级仍未承诺；隔离验收实例保留供复验。P4审批工作流、P5门户、P6旧权迁移与P7生产治理均不在本次授权内。P3完成后暂停。

## 远程CI差异修正

首轮auth CI（36440680619）在4个组目录用例生成事件时失败：Linux Instant.now可含纳秒，而DirectoryEvents契约仅接受微秒。macOS时钟恰为微秒导致本地未复现。将测试事件时间显式截断至MICROS，保留生产协议严格校验；不放宽断言、不跳过用例。修复后auth main e55368a的CI36441586619完整SUCCESS，包括24项P3图测试及全部既有HTTP回归；详见P3_DELIVERY_RESULT。商城首轮main dceeb5a完整CI36440692582已成功，含真实MySQL、前端审计及浏览器验收。
