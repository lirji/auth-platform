# 候选方案 C：长期 Casdoor 控制器 / continuous reconciler

## 1. 架构

扩展 `auth-platform-admin`，建立正式的 Casdoor desired-state 控制器：manifest/数据库是声明入口，控制器周期性读取 Casdoor current state，生成 plan，经审批后 apply；所有 user/org/role/permission 变化都有操作记录、幂等键、状态机和告警。运行流量切换仍复用现有 edge/frontend。

```text
desired state -> planner -> approval -> applier -> Casdoor
                     |                      |
                 audit/diff             reconcile/status
```

## 2. 模块职责

- `CasdoorClient`：从当前只读用户/组扩展到经验证的 org/user/role/permission API；
- planner：规范化 desired/current，产出不可变 plan 和 hash；
- applier：按 org 串行、对象级幂等、失败状态和重试；
- audit store：记录 actor、plan、前后状态、Casdoor response、token postcondition；
- scheduled reconciler：检测 drift；默认只告警，不自动做破坏性 prune；
- admin API/UI：只允许审批已生成 plan，不直接提交随意写操作。

## 3. 核心流程

1. desired state 提交并验证 schema；
2. planner 读取 Casdoor，输出 create/update/noop/conflict；
3. 人工审批 plan hash；
4. applier 执行 role assignment 后 permission refresh；
5. token probe 与状态持久化；
6. 定时 drift 检测，异常告警；
7. edge/frontend 按方案 A 的顺序灰度。

## 4. 改动范围

- 大：`auth-platform-admin` 新增 DTO、client、planner、状态表、API、鉴权、调度、测试和运维；
- 需要数据库迁移机制或至少明确状态表 DDL；现仓库 admin 侧暂无这套模型；
- 运行链不变，但新增一个高权限控制面，安全面显著扩大。

## 5. 并发、事务与幂等

- 可用数据库唯一约束/乐观锁保证同 org 单 active plan；
- Casdoor 远端写与本地审计无法组成原子事务，需要 outbox/saga 风格状态机；
- 能比 shell 更好地处理重试、漂移和规模，但实现正确性要求也更高。

## 6. 扩展性

- 最适合大量 org、频繁人员变更、合规审计和长期运营；
- 可进一步接 webhook、审批、SCIM/HR 源；
- 当前仅三条 demo user 时投入产出比低。

## 7. 主要风险与成本

- 高权限 client secret 常驻在线服务；若控制器被攻破可修改全平台身份；
- 自动 reconcile 若 desired state 错误会快速放大故障；
- 自建 Casdoor 控制器可能重新形成一个“第二权限真相源”；必须保证 desired 是声明入口、Casdoor 是生效真相并清晰定义冲突策略；
- 实施和测试成本最高，会拖慢本轮下线目标。

## 8. 适用结论

是规模化二期候选，不适合作为本轮前置。最终方案只吸收 plan/diff、状态日志、单写者和 drift 报告原则，不建设在线控制器。
