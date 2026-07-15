# 方案 C：事务 Outbox + 事件驱动授权投影

## 1. 架构主张

auth-service 和 knowledge-service 在各自本地事务/协调状态中记录授权领域事件，经 Kafka/outbox relay 投递；AP 新增 projector 消费并幂等写 SpiceDB。Casdoor webhook 同样转换成标准 identity/group 事件。在线 check 仍走 SDK。

```text
auth DB mutation -> AUTHZ_OUTBOX --relay--> Kafka --\
knowledge mutation -> AUTHZ_OUTBOX --relay--> Kafka ----> AP projector -> SpiceDB
Casdoor webhook --------------------------------------/
```

LP 已有 `platform-eventbus` 和其他服务的 outbox 模式，可复用基础设施思路，但 auth/knowledge 当前没有 authz outbox。

## 2. 模块职责

- 生产者：只提交本地事实和事件，不同步依赖 AP。
- Kafka：按 `tenantId + aggregateType + aggregateId` 分区保序。
- AP projector：消费、去重、版本检查、关系写、DLQ、重放。
- reconciliation job：周期性 snapshot 对账，兜住消息丢失/人为改 tuple。

## 3. 事件建议（均为拟新增）

- `IdentitySubjectLinked`：legacy user → Casdoor sub。
- `GroupMembershipReplaced`、`RoleMembershipReplaced`。
- `SpaceCreated/PolicyChanged/Deleted`。
- `DocumentCreated/Moved/Deleted`。

统一 envelope：`eventId`、`eventType`、`schemaVersion`、`occurredAt`、`tenantId`、`aggregateType`、`aggregateId`、`aggregateVersion`、`actorSub`、`payload`、`traceId`。不得放密码、token 或正文。

## 4. 改动范围与成本

- 高：新增表/relay/projector/DLQ/replay 工具、schema version 管理、跨仓库契约和运维面。
- knowledge 当前核心 metadata 在 Redis，若要真正 transactional outbox，需新增关系型协调表或采用 Redis Stream + Lua；两者都不是小改。

## 5. 扩展性

- 四案最好：新服务发布标准事件即可接 IAM；高吞吐、松耦合、可审计回放。
- 事件 schema 演进与重放副作用是长期治理成本。

## 6. 风险评审（risk-reviewer）

| 维度 | 评审 |
|---|---|
| 兼容性 | 在线业务不依赖 AP；但引入 eventbus 对 auth/knowledge 是新运行依赖 |
| 事务 | auth JDBC 可用经典 outbox；knowledge 多存储仍只能保证 metadata/outbox 原子，向量等需补偿 |
| 并发 | 按 aggregate 分区 + version 可拒绝乱序；跨 aggregate（role 改动影响 N spaces）仍需 fan-out 编排 |
| 幂等 | eventId 去重 + TOUCH；DELETE 重放应无害 |
| 性能 | 适合大规模；消费 lag 会延迟授权，撤权延迟尤其敏感 |
| 安全 | 事件总线包含授权事实，ACL、加密、保留期、DLQ 权限都需治理 |
| 数据迁移 | 可将全量迁移转成 bootstrap events；重放能力强 |
| 灰度 | 可 shadow consumer、比较 projected vs desired；很好 |
| 回滚 | 关 consumer 容易，回滚已投影关系需按版本/快照，不能简单反向消费 |

### 失败场景

- 同一成员变更事件乱序导致撤权被旧 grant 覆盖；必须比较 aggregateVersion。
- consumer 写 SpiceDB 成功但 offset 未提交，重复消费需幂等。
- Kafka 长时间 lag 时本地已撤权、SpiceDB 仍放行；敏感撤权需同步 fast path 或强 SLO。
- schema v2 consumer 回放 v1 payload 不兼容。

## 7. 测试重点

- producer transaction rollback 不得有可见事件；relay 重试不丢不重语义。
- consumer crash 的四个窗口（写前、写后/offset 前、offset 后、DLQ）。
- partition key、version 乱序、重放、schema 兼容。
- Kafka lag 下授权/撤权 SLO与报警。

## 8. 结论

长期平台化价值最高，但对当前 pilot 过重，且不能自动解决 knowledge 多存储事务。可把事件 envelope 和 projector port 留作演进点，不建议作为首期主路径。
