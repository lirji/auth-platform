# P3 故障验收矩阵

| 场景 | 已执行证据 | 结果 |
|---|---|---|
| 不同Grant的动作与范围不能交叉放大 | ScopeRulesTest、真实ReliableAuthorizationIT、商品门店AND HTTP | PASS |
| 旧marker晚到写 | ProjectionCasIT；真实SIGSTOP→撤权→新执行器→SIGCONT，远端旧CAS拒绝 | PASS |
| 图成功而SQL无receipt | 真实SIGKILL_AFTER_REMOTE_COMMIT，新进程补唯一receipt/operation | PASS |
| 租约/批次/marker异常 | ReliableProjectionIT，50批有界、旧READY拒绝、未知marker BLOCKED | PASS |
| bulk缺项/错关联/Conditional/不完整响应 | StrictGraph协议HTTP测试，整批拒绝，不保留部分ALLOW | PASS |
| 主库A/C及双水位 | FencePostgresIT、ReliableAuthorizationIT，独立快照再读及候选变化 | PASS |
| 双auth JVM | 18111/18113轮流真实判权；撤权提交未投影503，receipt后两节点DENY | PASS |
| 行/count/search/统计/详情 | Owner MySQL六项和48项真实HTTP，跨租户/同店不同资源负例 | PASS |
| 导出故障、隔离与重放 | SQL批次中途冲突回滚、双线程排他；真实进程kill50行后恢复55行 | PASS |
| 排队/执行/完成后的撤权 | MySQL用例、真实队列启动及完成下载503/403，旧范围绑定拒绝 | PASS |
| 组任职迁移/到期/离职再入职 | 真实PG+图，旧图尚可允许时SQL栅栏先阻断；代际不复活Grant | PASS |
| 紧急停用/回执/目录运维范围 | owner-only、全应用多分区epoch、审计幂等、精确来源时区权限 | PASS |
| 依赖不可用 | auth停机后商城503，无旧ACL回退；图错误协议与真实图恢复 | PASS |
| 本地延迟基线 | 60次双节点scope（并发2），30次商城范围列表（并发1） | PASS（小样本） |

证据：P3-01…06_TEST_RESULT、evidence/projection-process-faults.json、evidence/http-result.json、evidence/http-performance.json。范围限当前门店/商品只读接入，不宣称已验收未来退款写链路或生产容量。
