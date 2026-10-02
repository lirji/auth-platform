# CE08 十二车道职责与验收边界

本片不重新授予 SYSTEM 员工权限。调度拓扑来自 `commerce-app/.../runtime/scheduling/EventWorker.java`：十条原业务车道，加 replay、retention；默认独立固定延迟、有界线程池，原行锁/CAS负责多实例竞争。当前跨进程演练关闭自动 workers，通过真实 Owner SPI 明确调用资金及回放车道，不能把该演练写成十二个调度线程全部运行。拓扑/公平性、真实持久化恢复由现有测试验证。

| 车道 | 原职责与权威来源 | 员工引用/撤权边界 | 精确验证出处 |
| --- | --- | --- | --- |
| payments | 已存在 payment_attempt 与可信渠道账本的核对、终态及 outbox；不能按员工离职取消原资金责任 | 人工 reconcile 单独 payment.reconcile；后台 tick 没有员工 Actor | 本片 system-auth-stop-result，PaymentCheckLaneTest、PersistedCommerceTest |
| refunds | 已预留 refund/case/order 金额，可信渠道结果和原退款承诺 | 人工 refund.reconcile 独立；后台不继承其 Grant | 本片真实停用与 Auth 停服后 SUCCEEDED，Refund/崩溃/并发原实库回归 |
| orders | 原到期事实、关闭资金意图、释放原预占；后台按原订单承诺 | 新人工 expire 最多20项、实际 store；重试原事实不换目标；后台不需要该员工仍在职 | system-auth-stop-result CANCELLED，OrderExpiryLaneTest、CentralOrderOperationsMySqlTest |
| events | 原 outbox → 实际消费者 → 业务效果与 inbox 同事务；隔离保留原失败 | 人工 pump 每次新有界调用，retry 原事件未成功消费者；不复制 ADMIN 给消费者 | 原 EventFailureSemantics/EventSchedulingFairness/CrashRecovery 实库；当前 UI int1/unknown/SQL |
| segments | MANUAL 原有限 Source 或 SYSTEM 固定发布政策，真实版本及人群快照 | 每次继续重验原来源，重试不换原 Grant；扫描按实际发布政策 | Root segments-core/owner/ui-test-result，S2 真实终态引用；本片未改 Segment Owner |
| journeys | MANUAL 原有限 enrollment Source；SYSTEM 精确已发布 journey/content policy；原事件触发 | 原身份/路由/期限/Grant 每节点核对，政策源不成为员工 ADMIN | Journey e900616 Owner，J1 真实26项/J2 跨进程 Source22/原 Grant35秒到期；本片使用原 PreparedEnrollment |
| cycles | 原有效治理政策和会员周期计算/rollout 原事实 | 发布政策与人工读取/变更分离；系统评估不伪造新员工 Source | 原 cycle-core-integration-result 为 Auth 真实引用验证，业务 CycleSchedule/MemberCycle/CentralCycleMySqlTest；不把 Auth 测试单独当业务证明 |
| points | 积分原 lot 到期、原 hold/ledger，保留既有订单及退款补偿 | 人工 points 各能力独立；到期承担原账本责任，不需要员工新 Grant | 原 points-core-integration-result 为 Auth 引用验证；业务 MemberPointsLane/CentralPointsMySqlTest、PointsCheckout 实库 |
| deliveries | 原 ISSUE Source、定向旧钱包及首次 REVOKE CONTROL Source | 无新增 SYSTEM；新控制来源不能替换 ISSUE、首次 REVOKE 或旧 Grant；未知仍冻结原命令 | Root durable6 1316/39图、21真 recipient、双原源/5审计、停服/到期；terminal-review 与 visual-review 联合验证 |
| catalog-jobs | 实际 creator_json 与固定 targets/deadline/receipts；旧 CentralCatalog 原执行引用 | 每项按 creator 原执行引用实时 checkExecution；新 Grant/控制不能恢复被撤销原来源；不是 product.read alias | Root P6/D6 catalog 跨 JVM Source/撤权/停服，CatalogJob 原回归；本片未改此 Owner |
| replay | 原 MANUAL HUMAN/tenant/principal/member/generation/caller/context/route 精确持久来源；LEGACY 仅真实原 createdBy | 授权窗口上限86460，原 Grant交集；每步无许可不推进 cursor/attempts；控制不替换创建源；ReplayGate 拒绝资金/外部副作用 | 本片真实6秒 SDK Source→Auth reference/原 Grant/role→精确 command_identity SQL；到期、新 CREATE 不能复活旧回执；Auth 真停服原任务不动 |
| retention | 显式治理 policy，默认关闭；DELIVERED/SKIPPED与 Inbox原子删除，保护活动 replay | 没有员工引用；不编造保留期限授权，不开放任意清表；命令清理须独立长下限 | RetentionTest、MultiInstanceRecoveryTest 原真实 MySQL；默认 disabled 的运行诊断；本片不启用生产清理 |

所有来源都有明确 Owner。分批预算、deadline、CAS/唯一约束、Inbox 与原回执沿实际 Owner 实现；恢复不撤销已经提交的业务效果。Runtime recover 最多50个明示旧 ID，恢复审计保存原失败与前后状态；没有“恢复任意业务/清空失败记录/任意消费者重放”接口。

复用证据不表示当前改动免验：本片改变 orders/events/replay 及聚合，已运行独占真实 MySQL/HTTP与当前跨进程失败路径。未变车道按 Source SHA/Owner提交核对旧不可变验收，不重复运行已经完成的业务演练。所有实际路径、SHA、XML/log、归档与当前 UI 在 `TEST_RESULT.md` 的终态索引中记录。
