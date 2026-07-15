# 05 — Solution C：授权优先的 allowed-set 检索与授权感知缓存

## 定位

先通过 `lookupResources(subject, view, document, consistency)` 得到可见 document id 集，再把允许集下推到向量/关键词/ES 检索；conversation cache 以 user + authorization version 或 source recheck 保障命中仍有权。

## 架构与职责

- auth planning：查询前 lookup 可见资源，校验/剥离 tenant 前缀。
- retrieval sources：接收 allowed docId 集并与 tenant/category filter 组合；public 分区走独立规则。
- cache：条目保存 source document ids 和授权版本；命中时重新校验或因版本变化失效。
- relationship write：产生可传播的授权版本/epoch，覆盖 grant/revoke/group/default-space 变化。

## 核心流程

1. 快照主体与租户，lookup 全部 view 资源。
2. 严格筛选 `<tenant>_` 前缀并转换为裸 docId。
3. 各检索源只召回允许集，融合和重排不再接触未授权正文。
4. cache 命中按 user/epoch/source ids 验证，撤权立即失效。

## 改动范围

涉及 `KnowledgeQueryService`、`RetrievalRequest`、全部 `RetrievalSource`、ES/vector filter 适配、auth-platform lookup 传输、conversation cache DTO/store/controller、关系写事件或版本存储，以及大量协议/配置/测试。

## 扩展性与成本

- 扩展性最高，可从根源处理缓存与重排前数据暴露，适合大规模 ACL 检索。
- 成本很高：lookup 集可能巨大，向量库/ES 大型 IN filter 有上限，分页/截断会造成误拒；授权 epoch 是新的分布式一致性系统。
- 不适合以 correctness audit 的最小修复直接落地。

## 风险评审

- 兼容性：内部请求模型和所有源变化；查询结果排序、召回可能显著变化。
- 事务：tuple 与 epoch 双写需要 outbox/幂等；任何漏事件会缓存越权。
- 性能：lookup 全集和大过滤集合可能比候选 checkBulk 更昂贵。
- 数据迁移：旧缓存条目/版本缺失必须整体失效。
- 灰度：需要双算比对，回滚要保留原 checkBulk 路径。

## 已知弱点

`lookupResources` 并不天然比 bounded candidates 的 `checkBulk` 更正确；在高基数租户中甚至更难保证完整性。只有在性能数据和缓存产品需求明确后才值得立项。

