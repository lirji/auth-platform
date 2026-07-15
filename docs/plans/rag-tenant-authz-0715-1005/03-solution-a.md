# 方案 A：沿现有融合后 checkBulk 主链生产化

## 1. 架构

保留现有 `KnowledgeQueryService` 编排与 `KnowledgeAuthz` 端口，把当前已实现的后置过滤收敛为强约束：

```text
Casdoor(owner/sub) -> internal JWT -> TenantContext snapshot
  -> vector/keyword/ES(term tenantId)/graph
  -> HybridFusionService
  -> docId 去重
  -> RealKnowledgeAuthz.checkBulk(document:<t>_<d>, view, user:<sub>)
  -> 只保留 allowed
  -> rerank -> response
```

ES term 是第一道租户边界，SpiceDB checkBulk 是正文返回前的第二道文档边界。两者使用同一个 `TenantContext` 快照。

## 2. 模块职责

- edge：验证 Casdoor token；严格 profile 固定 `owner/sub`，换发内部 JWT。
- `KnowledgeQueryService`：限制 topK/候选池；候选倍数取 authz 与 reranker 所需倍数的最大值（不相乘）；融合、docId 去重、调用批量判权、授权后再 rerank。
- `ElasticsearchEsGateway`：始终生成 tenant term；拒绝空 tenant。
- `RealKnowledgeAuthz`：资源 ID 编码、checkBulk 分批、协议结果校验、fail-closed 与指标。
- auth-platform admin：遍历显式配置的 Casdoor organizations，把 group 同步为 tenant-scoped group，以 direct relationship 对账，并显式绑定 default space。
- SpiceDB：作为 view 关系推导权威。

## 3. 核心流程

1. edge 对 JWT 完整验签，从 `owner/sub` 构造内部身份。
2. `KnowledgeQueryService.query` 一次读取 tenant/user，形成不可变请求快照。
3. ES 以 tenant term 和可选 category term 拉取有界候选；其他源也只查当前租户。
4. 融合后收集非空 docId，稳定去重；strict 模式与 public enabled 配置冲突时拒绝启动。
5. 按配置批次调用 `checkBulk`；missing/deny/error 不进入 readable set。
6. 过滤候选后 rerank，最终最多返回请求 topK。
7. group 同步和 space binding 通过 schema 的 `parent_space->view` 影响判定，无需把 ACL 写入 ES。

## 4. 改动范围与实施成本

改动集中在现有类与测试，公开 API、ES mapping、内部 JWT、SpiceDB schema 均不变。需要补部署配置、group ID 租户化与 required E2E。

- 代码改动风险：低到中。
- 实施成本：约 3–5 个开发日（不含真实环境数据整理）。
- 运维依赖：AP server、SpiceDB、Casdoor admin 同步可用。

## 5. 扩展性

- 新检索源只要产出 docId，自动受统一后置过滤保护。
- folder/space 继承由 SpiceDB schema 扩展，不改变查询过滤器。
- checkBulk 可通过候选上限、分批和未来一致性策略调优。
- 不适合无限候选或极高 QPS；每次查询至少增加一次 AP/SpiceDB 网络往返。

## 6. 风险评审

| 维度 | 风险/失败场景 | 缓解 |
|---|---|---|
| 兼容性 | enforce 会把原“同租户全可见”收紧为 owner/显式 view | 先 shadow；明确 default space group binding |
| 事务 | 文档多存储写成功、关系写失败，文档会被安全地隐藏 | backfill/reconcile；缺关系 deny；告警 orphan |
| 并发 | 同名上传、组同步并发与多副本对账 | owner CREATE；group sync 分布式 lease/单副本；幂等 TOUCH；分页不完整禁止 DELETE |
| 幂等 | 重复 group/document relation 写 | TOUCH；owner 不伪造；backfill manifest 可重跑 |
| 性能 | fully-consistent checkBulk 增加 P95；未授权候选导致 underfill | 候选 multiplier、maxCandidates、bulkSize、指标与压测 |
| 安全 | 空 tenant、畸形 bulk 响应、AP 故障、service credential 泄露 | 启动/运行 fail-fast；enforce fail-closed；专属 Secret/网络 allowlist/轮换 |
| 数据迁移 | 历史文档没有 owner | 只补 parent_space；按明确 group binding/manifest 授权 |
| 灰度 | shadow 本身仍会返回 denied 文档 | 明确 shadow 只用于观测；安全验收只看 enforce |
| 回滚 | enforce 造成可见性骤降 | 配置退回 shadow/disabled；保留关系不影响旧行为 |

## 7. 已知弱点

1. 授权后置导致未经授权的高分候选仍占用召回额度，可能不足 topK。
2. 每次请求都依赖 AP/SpiceDB；故障时安全但可用性下降。
3. 当前文档双写没有事务状态机，关系缺失需对账。
4. 公共库 bypass 必须关闭或单独治理。

## 8. 适用结论

这是与硬性目标和现有代码最贴合的方案。它没有重写检索架构，容易证明“tenant term + 最终 checkBulk”两道边界，也最容易回滚。
