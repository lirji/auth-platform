# 方案 C：授权投影物化到 ES + SpiceDB 最终复核

## 1. 架构

把文档的可见主体/组或授权分区投影到 ES 文档字段；Casdoor/SpiceDB 关系变化通过投影任务更新 ES。查询以 tenant term + subject/group terms 预过滤，融合后仍使用 SpiceDB checkBulk 做权威复核。

示意（字段为计划态，不代表当前存在）：

```text
SpiceDB/Casdoor relationship change
  -> AuthzProjection worker
  -> ES chunk fields: aclUsers / aclGroups / authzVersion

query:
  tenant term
  AND (aclUsers contains sub OR aclGroups intersects current groups)
  -> fusion
  -> SpiceDB checkBulk final authority
```

## 2. 模块职责

- auth-platform 产生授权变更投影事件或定时快照。
- 新投影 worker 把文档/space/folder 继承展开成 ES 可检索 ACL。
- ES mapping 与索引器维护 ACL 字段和版本。
- knowledge 查询做低成本预过滤，最终 checkBulk 保证撤权安全。

## 3. 与 A/B 的本质区别

- A 不复制授权数据，运行时实时判定。
- B 每次查询实时求可见资源集合。
- C 把授权派生结果复制进搜索索引，以写放大换读性能。

## 4. 改动范围与实施成本

需要事件/outbox、投影 worker、ES mapping 版本、全量重建、死信/重放、权限版本与监控。还要处理 folder/space/group 继承的扇出更新。

实施成本约 15–25 个开发日，且运维复杂度最高。

## 5. 扩展性

- 高 QPS、大候选库下读性能最好，ES 能在召回阶段排除绝大多数无权文档。
- group/space 权限变更可能扇出到海量 chunk，写放大显著。
- ACL 数组可能触及 ES 文档大小、mapping 和 terms 限制。
- 适合权限变化低频、查询极高频的成熟阶段。

## 6. 风险评审

| 维度 | 风险/失败场景 | 缓解 |
|---|---|---|
| 兼容性 | ES mapping/索引重建，旧索引无 ACL | 新索引+alias 双写切换 |
| 事务 | SpiceDB 已撤权但 ES 投影滞后 | 最终 fully-consistent checkBulk，绝不只信 ES ACL |
| 并发 | 多事件乱序覆盖新版本 | 每资源 authzVersion/单调 revision；幂等 upsert |
| 幂等 | 重放重复事件 | 稳定 event ID 与版本比较 |
| 性能 | group 变化触发大扇出，写入风暴 | 延迟批处理、按 space 投影、限流 |
| 安全 | ACL 字段漏写/陈旧 | ES 只做预过滤，SpiceDB 最终复核；缺字段拒绝 |
| 数据迁移 | 全量 reindex 与关系快照不一致 | 固定 SpiceDB revision 构建，alias 原子切换 |
| 灰度 | 新旧索引结果差异 | shadow 双查、差异指标、按租户 alias |
| 回滚 | 新 mapping/worker 状态复杂 | 保留旧 alias 与方案 A 后置过滤路径 |

## 7. 已知弱点

1. 授权数据复制形成新的最终一致投影，排障链路明显变长。
2. folder/space/group 的继承展开可能产生巨大扇出。
3. 当前仓库没有可靠事件投影基础设施来承载该链路；为本任务引入成本过高。
4. 即使物化 ACL，也不能取消 mandatory checkBulk，否则撤权窗口会泄露正文。

## 8. 适用结论

这是面向规模化的二期/三期架构，不适合当前闭环。可吸收的优点是 ES alias 双写重建、authzVersion 和投影延迟监控思路，但不应在首期引入完整投影系统。

