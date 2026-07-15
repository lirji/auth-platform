# 候选方案 C：Blue/Green 授权平面 + 流量影子比较

## 1. 架构

保留当前 SpiceDB/Auth Platform 为 blue，新建固定版本的 green SpiceDB/Auth Platform。green 从 Casdoor 和业务 inventory 构造锁定的 department 模型；knowledge shadow 同时向 blue/green 判权并比较，最终将 `AUTHZ_SERVER_URL` 切到 green。

```text
knowledge ──现有判权──> AP-blue ─> SpiceDB-blue
         └─shadow compare──> AP-green ─> SpiceDB-green(target)
Casdoor/registry ──reconcile/backfill───────────────┘
```

## 2. 模块职责

- green 完整承载目标 schema/tuple，不需要在目标 datastore 保留旧 relation。
- 一个迁移器把 Casdoor department、document owner/viewer/public/home_dept 写入 green；不复制旧 parent_space 作为授权边。
- knowledge 的比较器记录同一 subject/resource 上 blue vs green 的 allow/deny，但 shadow 不改变响应。
- 配置层通过 AP endpoint 切换完成上线/回滚。

## 3. 核心流程

1. pin 并部署 green；加载纯目标 schema。
2. 完整同步部门和 backfill 文档；持续追赶 Casdoor/文档新增。
3. 影子比较 owner、本部门、祖先、平级、viewer、public 及真实请求；差异按预期/异常分类。
4. 停止短时写入或用双写水位确保 green 追平；切 endpoint。
5. blue 保留整个观察窗，回滚只切 endpoint。

## 4. 并发、事务和幂等

迁移器对 green 使用 TOUCH/diff；双写时每条变更含稳定 resource ID。切换门需要一个明确水位：所有小于等于水位的 document/Casdoor 变更都已在 green 读回。当前仓库没有事件日志或统一 revision，因此该水位机制是新增工程，不能假设存在。

## 5. 改动范围和成本

需要第二套 SpiceDB/AP、部署配置、shadow comparator、双写/追赶水位和运维监控。基础设施与测试成本最高，但 rollback 最快、目标 datastore 最干净。

## 6. 扩展性

适合大规模生产、严格 RTO/RPO 或未来频繁 schema 演进；可复用 green 验证环境做性能测试。对当前单体量级可能过度建设。

## 7. 风险与弱点

- 没有可靠事件水位时，green 看似全量但可能漏掉切换前最后一批写。
- 双授权调用放大 AP/SpiceDB 负载；必须采样或限流，不能影响用户请求。
- 两套 datastore 的 user/document ID 规则一旦漂移，会产生难解释差异。
- 费用、密钥、备份、监控和 on-call 面翻倍。
- 仍不能解决锁定 schema 未定义 edit 的业务缺口。

## 8. 结论

回滚和隔离最强，但当前仓库缺少事件水位基础。可将它作为高合规生产的升级路线，不作为本轮默认实施。
