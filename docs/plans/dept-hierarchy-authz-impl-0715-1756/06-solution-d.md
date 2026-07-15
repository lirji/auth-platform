# 候选方案 D：Webhook/Event-first 增量同步 + Durable Lease

## 1. 架构

把 Casdoor 变更事件作为主路径：webhook 只写入持久事件/待处理队列，持 lease 的单 writer 按 org 串行拉取权威快照并 reconcile；定时全量扫描仅用于修复丢事件。knowledge 仍走兼容 schema 和双写。

## 2. 模块职责

- `CasdoorSyncController`：鉴权、去重 event id（若 payload 有可靠 id，待验证）、入队，绝不在 HTTP 线程直接全量 sync。
- durable queue/outbox：保存 org、event revision、状态、重试次数；具体存储当前仓库不存在，需新增数据库/消息设施。
- lease manager：保证同一 org 只有一个 writer；lease 过期可接管。
- `GroupSyncService`：仍以 Casdoor 完整 snapshot 为真相，不直接相信 webhook payload 的 parent/admin 字段。
- periodic auditor：低频全 org drift reconcile。

## 3. 核心流程

1. webhook 入队并立即 2xx；相同事件幂等去重。
2. writer 获取 `<org>` lease，合并短时间内重复事件。
3. 分页拉完整 snapshot、校验、diff、per-org fuse、写 SpiceDB、记录 checkpoint。
4. 释放 lease；失败指数退避，超过阈值进入 dead-letter/告警。
5. 定时 auditor 发现 webhook 丢失或 drift 后补齐。

## 4. 并发、事务和幂等

- durable lease 比方案 A 的配置式单 writer 更强；不同 org 可并行，同 org 串行。
- event 入队和 ack 具持久性；实际 tuple 写仍以 snapshot diff 幂等。
- 不使用 webhook 局部 payload直接 DELETE，避免乱序事件回滚新状态。
- DB/outbox schema、事务和清理策略是净新增，当前 `auth-platform-admin/pom.xml:14-35` 没有 JDBC/消息依赖。

## 5. 改动范围和成本

除了方案 A 的两仓改动，还要增加持久存储、lease/outbox repository、迁移脚本、重试/DLQ、监控与运维。成本高于 A，低延迟和 HA 更好。

## 6. 扩展性

多 org 变化频繁时，不必每个 interval 全量扫所有 org；org 级并行和去抖可控制 Casdoor 压力。未来可承载更多 IAM→ReBAC 投影。

## 7. 风险与弱点

- 当前 Casdoor webhook payload/event id/revision 未验证；无法证明可靠去重和顺序。
- 为一个 snapshot reconcile 引入数据库/队列，攻击面和故障面明显扩大。
- webhook 成功不代表 SpiceDB 已同步，必须暴露 lag/last-success，而调用方不能误解 2xx。
- lease 时钟、续租和脑裂需要专项测试。
- 仍需 periodic full reconcile，所以不能删除方案 A 的完整分页逻辑。

## 8. 结论

适合 org 数量和变更频率上升后的演进。最终方案只吸收“webhook 触发但以快照为准、org 串行、预留 lease 抽象”，本轮不先引入 durable queue。
