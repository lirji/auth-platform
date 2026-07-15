# 候选方案比较、风险复核与裁决

## 1. 统一评分规则

评分 1–5，越高越有利。对“改动风险、复杂度、测试难度、回滚成本”采用反向含义：5 表示风险/复杂度/难度/成本低。权重用于辅助判断，不替代硬性安全要求。

| 维度 | 权重 | 评分关注点 |
|---|---:|---|
| 正确性 | 25% | tenant 与 ReBAC 是否有可证明的 fail-closed 边界 |
| 改动风险 | 15% | 对现有检索、provider、身份链路的扰动 |
| 复杂度 | 10% | 组件、状态、数据复制与运行链路 |
| 可维护性 | 15% | 责任边界、排障、长期认知成本 |
| 扩展性 | 10% | 数据量/QPS/授权模型演进能力 |
| 测试难度 | 10% | 可确定验证、环境依赖、矩阵规模 |
| 回滚成本 | 15% | 是否可仅切配置、是否涉及索引/状态迁移 |

## 2. 评分表

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 加权总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 融合后 checkBulk 生产化 | 5 | 5 | 5 | 4 | 3 | 4 | 5 | **4.55** |
| B lookup 前置收窄 + checkBulk | 5 | 3 | 2 | 3 | 4 | 2 | 3 | **3.45** |
| C ES ACL 投影 + checkBulk | 4 | 2 | 1 | 2 | 5 | 1 | 2 | **2.65** |

## 3. 逐项比较

| 项目 | A | B | C |
|---|---|---|---|
| 是否满足 tenant term | 是 | 是 | 是 |
| 是否保留最终 checkBulk | 是 | 是 | 是 |
| 是否改公开 API | 否 | 否 | 否 |
| 是否改 ES mapping | 否 | 可不改 | 是 |
| 是否侵入所有 retrieval source | 最小 | 高 | 中到高 |
| 未授权候选导致 underfill | 有 | 少 | 少 |
| 撤权即时安全 | fully-consistent checkBulk | 同左 | 同左；不能只信投影 |
| 外部状态 | SpiceDB | SpiceDB + lookup/cache | SpiceDB + ES ACL 投影/事件 |
| 首期回滚 | mode 切回 shadow/disabled | 需关闭前置集合分支 | 需停 worker、回 alias、退回 A |

## 4. risk-reviewer 结论

### 4.1 共同硬风险

1. **身份混用**：最终 profile 若仍允许 legacy session/API key，就不能宣称所有 tenant 都来自 token.owner。
2. **历史关系缺失**：直接 enforce 会把未 backfill 的文档全部隐藏。
3. **组碰撞**：当前 `GroupSyncService` 使用短组名，必须在多租户上线前修正。
4. **公共库 bypass**：`shared=true` 当前跳过 ReBAC，与严格目标冲突。
5. **依赖故障**：AP/SpiceDB 故障必须安全拒绝；同时需要监控避免“安全但全空”长期无人察觉。
6. **候选资源滥用**：未限制 topK/bulk 会形成 ES/SpiceDB DoS 面。

### 4.2 方案特有失败场景

- A：高分无权文档把有权文档挤出候选；结果 underfill。
- B：大权限集合通过 lookup/terms 放大请求，provider 支持不一致；缓存撤权语义复杂。
- C：投影乱序/滞后、mapping 迁移、group 变更扇出导致写风暴；组件最多。

## 5. plan-judge 裁决

不机械照最高分原样采用 A，而是采用以下合并方案：

- 以 A 为安全主链，因为它已在当前代码中存在，最容易证明且回滚最简单。
- 吸收 B 的“减少 underfill”目标，但首期不用 lookupResources；改为有界候选放大、topK/候选/bulk 上限和 underfill 指标。只有指标证明必要时，再为特定租户/ES 源试点 lookup 前置。
- 吸收 C 的投影可观测性思想：记录关系 backfill/reconcile 状态、授权错误与延迟，但不把 ACL 复制到 ES。
- 补上三方案都不能回避的身份严格模式、tenant-scoped group、历史关系准备与 required E2E。

## 6. 所选方案的弱点

1. 每个查询增加一次或数次 AP/SpiceDB 网络调用，P95 必然上升。
2. 有界 overfetch 只能缓解、不能消除 underfill。
3. 缺失授权关系会安全隐藏数据；如果 backfill/同步运维不成熟，用户体验会表现为“搜不到”。
4. fully-consistent 成本需真实压测；为了正确性首期不提前优化。
5. category→folder 不进入核心发布，类目继承能力会晚一个阶段。

