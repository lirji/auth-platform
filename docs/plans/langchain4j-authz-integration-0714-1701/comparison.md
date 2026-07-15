# 候选方案比较与裁决（plan-judge）

## 1. 评分口径

每项 1–5 分，**分数越高越有利**：正确性高、改动风险低、复杂度低、可维护/扩展性高、测试容易、回滚成本低。为避免凭总分机械选择，给正确性 25%、改动风险 20%、可维护性 15%，其余各 10%。评分依据是当前代码，不是抽象最佳实践。

## 2. 统一评分表

| 方案 | 正确性 25% | 改动风险 20% | 复杂度 10% | 可维护性 15% | 扩展性 10% | 测试难度 10% | 回滚成本 10% | 加权分 /5 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 最小同步桥接 | 3 | 4 | 4 | 2 | 3 | 4 | 5 | **3.45** |
| B IAM 中央拉取对账 | 4 | 4 | 3 | 4 | 4 | 3 | 4 | **3.80** |
| C Outbox + 事件投影 | 5 | 2 | 1 | 4 | 5 | 1 | 3 | **3.20** |
| D 一次性权威切换 | 5 | 2 | 2 | 5 | 5 | 2 | 1 | **3.45** |

## 3. 分数解释与反确认偏差检查

### A

- 优点被如实计入：最贴近现有未提交 `KnowledgeAuthz` 草稿、上线快、回滚容易。
- 没有因“改动小”就给高正确性：auth DB 与 SpiceDB 双写无法原子，且 Casdoor/auth-service 双权威是结构性问题。
- 适用：短期实验，不适合作为最终治理边界。

### B

- 得分最高不是因为它最先进，而是它与现有 `GroupSyncService` 的差量/reconcile 结构最匹配，且可以 dry-run。
- 弱点：最终一致、中央扫描、三个源 credential、snapshot 合同和多副本锁都是真实成本；没有给复杂度/测试高分。
- 适用：迁移与过渡主骨架。

### C

- 正确性/扩展性最高，但当前 knowledge metadata 主要在 Redis 且写多个 sink；引入 Kafka 不能神奇地获得端到端事务。
- 测试和运维成本最低分，避免“有事件总线就应事件化”的技术偏好。
- 适用：多服务大规模接 IAM 的后续平台化阶段。

### D

- 长期维护边界最好，身份与授权权威最清晰。
- 最大弱点是迁移不可逆部分：本地 BCrypt 密码、Casdoor sub、全部机器调用方和前端同时切换；回滚成本最低分。
- 适用：目标态，不适合作为第一跳。

## 4. 关键场景对比

| 场景 | A | B | C | D |
|---|---|---|---|---|
| AP 短暂不可用 | 管理写易漂移/超时 | 在线管理不受影响，稍后收敛 | 生产不受影响，消费 lag | 身份仍可用；资源写需 PENDING |
| 紧急撤权 | 同步可快，但失败危险 | 取决于 webhook/reconcile SLO | 取决于 event lag，可加 fast path | Casdoor webhook + AP，边界清晰 |
| 多副本 | after-commit 乱序需版本 | 需 leader/锁 | 分区+版本天然适配 | 同步服务仍需锁/对账 |
| 历史脏数据 | 处理弱 | dry-run/报告最好 | 可 bootstrap events，但建链重 | 一次迁移压力最大 |
| 回滚到旧登录 | 容易 | 较容易 | 中等 | 困难 |
| 后续接其他服务 | 投影代码扩散 | 标准 snapshot 可复用 | 标准事件最优 | IAM 边界最优 |

## 5. 最终裁决

不机械选择单一方案，采用“**B 的迁移/对账骨架 + D 的单一权威目标 + A 的同步资源写 fast path**”：

1. 身份目标态直接采用 D：Casdoor access token 是唯一人类入口，userId=sub，auth-service 本地登录退役。
2. RBAC 迁移采用 B：AP admin 做 dry-run、分租户 reconcile，先读取 auth-service snapshot，cutover 后只读 Casdoor，最终移除 legacy source。
3. knowledge 的创建/删除采用 A 的 SDK 同步写，以便新资源即时获得 owner/parent；但增加持久化 projection state + reconcile，不宣称跨系统事务。
4. 暂不引入 C 的 Kafka；保留标准 projection port、version/event envelope 设计，数据量或接入服务数达到阈值后再演进。

## 6. 最终方案已知弱点

- 仍有短时间最终一致窗口：Casdoor 组变更到 SpiceDB 由 webhook/reconcile 收敛。
- 首期 fully-consistent check 会增加 SpiceDB 延迟和负载。
- 需要同时改 AP、edge、knowledge、前端、部署与机器客户端，发布编排复杂。
- auth-service 只读过渡期仍是第二份历史 RBAC 数据，必须设置删除日期，否则会重新变成双权威。
- Redis 中的独立授权投影记录只能协调业务 metadata 与关系投影，不能让向量/ES/图谱写获得 ACID；仍依赖补偿。
- Casdoor tenant/scopes claim 尚未由本仓库代码证明，是 M0 硬阻塞项，不能在实现时凭经验填字段名。
