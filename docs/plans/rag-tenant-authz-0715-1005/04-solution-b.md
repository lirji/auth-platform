# 方案 B：权限集合前置收窄 + 最终 checkBulk 复核

## 1. 架构

查询前先调用 `lookupResources(user, view, document)` 得到用户可见文档集合，按 tenant 前缀校验并转成裸 docId；ES 同时使用 tenant term 与 docId terms 收窄候选。融合后仍执行 mandatory checkBulk，避免集合过期或转换错误。

```text
TenantContext
  -> lookupResources(document, view, user)
  -> 仅保留 <tenant>_ 前缀，生成 allowed docId 集
  -> ES bool.filter: tenant term AND docId terms
  -> 其他源命中也按 allowed set 预过滤
  -> fusion
  -> checkBulk 最终复核
  -> rerank
```

## 2. 模块职责

- 新的“授权候选提供器”（计划新增）负责 lookup、租户前缀校验、集合缓存/分页。
- retrieval source 接受可见 docId 约束；ES gateway 增加 terms 过滤能力。
- `RealKnowledgeAuthz` 同时提供 lookup 与 checkBulk 复核。
- group/space/document tuple 仍由 auth-platform 管理。

## 3. 与方案 A 的本质区别

方案 A 先按相关性找候选、再判权；方案 B 先求权限集合，再在权限子集中检索。它能显著降低 underfill，但把授权集合大小和 lookup 延迟放入热路径。

## 4. 改动范围与实施成本

- `RetrievalRequest` 需要新增 allowed doc IDs 或授权谓词。
- vector/keyword/ES/graph 各源都要支持前置集合过滤，否则只能部分受益。
- ES 大 terms 查询需分页/chunk；向量后端对大 allowlist 的支持并不统一。
- 需要缓存失效与权限版本策略。

实施成本约 8–12 个开发日，明显高于方案 A。

## 5. 扩展性

- 当用户可见文档集合较小且总库很大时效果好。
- 当用户通过租户组可见几十万文档时，lookup/terms 集合会很大，网络和内存成本不可接受。
- 不同向量库对 metadata `IN`/大过滤集合的限制不同，破坏当前 provider 可插拔性。

## 6. 风险评审

| 维度 | 风险/失败场景 | 缓解 |
|---|---|---|
| 兼容性 | RetrievalSource/各向量 provider 接口都变化 | feature flag；仅 ES 前置，其余保留后置 |
| 事务/一致性 | lookup 后撤权、checkBulk 前存在窗口 | mandatory fully-consistent checkBulk 最终复核 |
| 并发 | 授权集合缓存与组变更并发 | 短 TTL/版本水位；撤权不依赖缓存放行 |
| 幂等 | lookup 无写问题 | 缓存 key 必须含 tenant+sub+schema/version |
| 性能 | 大用户集合导致巨大 terms、内存和序列化 | 上限；超过阈值退回方案 A |
| 安全 | 前缀解析错误可能把他租户 ID 带入 | 强制 `<tenant>_` 校验；最终 checkBulk |
| 数据迁移 | tuple 不完整会直接漏召回 | shadow 对比；关系 backfill 是硬前置 |
| 灰度 | A/B 查询结果差异复杂 | 双跑采样，不把前置结果直接生效 |
| 回滚 | 新接口侵入多源 | 保留 A 的无 allowed-set 分支 |

## 7. 测试难点

- 需要覆盖每种向量 provider 对 allowlist 的支持/降级。
- 需要模拟大规模 lookup、分页、缓存失效、撤权窗口。
- 结果相关性与授权完整性耦合，回归矩阵显著扩大。

## 8. 适用结论

它适合授权集合通常很小的超大文档库，不适合作为当前首期默认。可吸收的优点是：监控 underfill 后，对特定租户/ES 源启用有上限的预过滤，而不是全平台一次性采用。

