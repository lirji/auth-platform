# 方案 B：IAM 中央拉取对账（auth-platform-admin 统一投影）

## 1. 架构主张

让 AP admin 成为投影协调者：从 Casdoor 拉身份/组，从 auth-service 的只读 RBAC snapshot 拉迁移期角色/组语义，从 knowledge 的 space snapshot 拉资源集合，计算完整期望态并与 SpiceDB 直接 tuple 做差量。业务在线判权仍由 knowledge 通过 SDK 完成。

```text
Casdoor -----------\
auth-service RBAC --- > auth-platform-admin DesiredStateReconciler -> SpiceDB
knowledge spaces ----/

Casdoor token -> edge -> internal JWT -> knowledge -> SDK check/write -> SpiceDB
```

## 2. 模块职责

- auth-service：迁移期只读导出 RBAC snapshot，之后冻结/退役身份写。
- knowledge-service：space/document 生命周期与关系写；提供内部只读 space snapshot 或由 AP 从明确的内部事件表读取（不直读业务 DB）。
- auth-platform-admin：规范化 tenant/group/role ID、版本化 desired state、差量 reconcile、审计与告警。
- Casdoor：用户、sub、组织与成员关系权威。

## 3. 核心流程

1. 一次性导出 LP `USERS/USER_ROLE/TENANT_ROLE/AUTH_GROUP/GROUP_ROLE/USER_GROUP`，完成 username/旧 userId→Casdoor sub crosswalk。
2. 用可重跑 manifest 把 tenant-scoped 原组、个人角色 assignment group 及 direct user membership 导入 Casdoor；迁移后 Casdoor 是 direct membership 权威，legacy snapshot 只做限时核对。
3. 生成 SpiceDB tenant-scoped 原组与角色组：
   - group membership 使用直接 tuple；
   - `role group#member@group#member` 表达组继承；
   - 个人角色从 Casdoor role-assignment group 投影；租户基础角色通过 tenant-all group 嵌套，不逐用户复制。
4. 依据显式 role→relation 配置，把 role group#member 绑定到每个 tenant space。
5. `GroupSyncService` 重构为“读取直接关系 + desired-current 差量”，webhook 触发，定时全量兜底。
6. knowledge 的新 document owner/parent_space 仍在业务写路径即时写；历史 document 无可信 owner 时只写 parent_space，reconciler 负责修复漂移。

## 4. 改动范围与成本

- 中高：AP admin 需要新增两个只读 client、规范化/差量模型、分布式互斥/leader、checkpoint 和 dry-run 报告。
- auth-service 在线 mutation 无 AP 依赖，耦合低于 A。
- 不引入 Kafka，部署比 C 简单。

## 5. 扩展性

- 新项目只要提供标准 snapshot 或 Casdoor 命名约定，即可接入同一 reconciler。
- 全量扫描在规模增长后需要分页、增量 cursor；中央 admin 可能成为治理瓶颈。

## 6. 风险评审（risk-reviewer）

| 维度 | 评审 |
|---|---|
| 兼容性 | 业务 mutation 不被远端可用性绑架；需稳定只读 snapshot 合同 |
| 事务 | 明确采用最终一致，不假装跨系统事务；每轮以 snapshot version/cursor 标识 |
| 并发 | 多 admin 副本必须分布式锁或单 leader；同一轮写 TOUCH/DELETE 幂等，旧 snapshot 不得覆盖新轮次 |
| 幂等 | 与现有 GroupSync 思路一致；必须改为读取直接 tuple，避免嵌套展开误删 |
| 性能 | 周期性全量会压 Casdoor/AP/SpiceDB；按 tenant 分页、hash 跳过未变分区可控 |
| 安全 | AP admin 需要读取三个源的 service credential；权限集中但可审计。server 写端仍需加固 |
| 数据迁移 | 最适合 dry-run、数量对账、反复执行和处理历史脏数据 |
| 灰度 | 可先只报 diff，再只 TOUCH，最后允许 DELETE；安全灰度能力强 |
| 回滚 | 停 reconciler 不影响在线业务；保留 tuple 快照可反向恢复 |

### 失败场景

- 某源分页中途变化导致混合快照：要求 snapshot version/ETag，或整轮重跑。
- Casdoor API 临时返回空数组若被当成权威空集会大规模撤权：需要“完整成功标记”，任何分页失败整轮禁止 DELETE。
- role→relation 配置错误会批量增权：配置变更必须 dry-run、双人审批、变更量阈值熔断。
- auth-service 与 Casdoor 双写期双方都变更成员：必须规定 cutover 时间和单一权威，不能靠 last-write-wins。

## 7. 测试重点

- 部分分页失败禁止 delete；空组织、成员清空、组删除、重命名。
- 多副本争抢、重复 webhook、定时任务与手动任务并发。
- 嵌套组直接 tuple 对账，不把 transitive user 当 direct edge。
- dry-run diff 与真实 apply 数量一致；超过阈值暂停。

## 8. 结论

这是平衡迁移可控性、解耦和实施成本的最佳过渡骨架；弱点是最终一致与中央扫描成本。适合与“Casdoor 最终权威”目标组合，而不是永久依赖 auth-service snapshot。
