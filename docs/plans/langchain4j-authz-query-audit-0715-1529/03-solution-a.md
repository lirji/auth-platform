# 03 — Solution A：保留融合后 checkBulk，做外科式正确性加固

## 定位

保留现有 `fuse → checkBulk → rerank` 架构，只修复已证实缺陷并把运营前置变成可执行门禁。这是推荐基线。

## 架构与职责

- auth-platform SDK：对 `check`/`checkBulk` 响应做完整类型校验；畸形协议一律抛异常。
- knowledge-service：现有 Real/Noop、full consistency、资源 id helper 和过滤落点不变。
- auth-platform provision：继续用显式 manifest/fixture 写 D3 binding，不让通用 group sync 猜测业务授权。
- 发布层：authz shadow/enforce 与 tenant-wide semantic cache 硬互斥；enforce 前验证 D3 tuple、membership、历史 document parent_space。

## 核心流程

1. 检索融合后，按去重 docId 分批 `checkBulk(view, full)`。
2. SDK 校验 `results`、数量、resource 唯一且属于请求、`allowed` 存在且为 boolean。
3. 任一异常：shadow 全集、enforce 空集；single 同理。
4. 发布 preflight 先证明 D3 和缓存互斥，再允许从 shadow 切 enforce。

## 改动范围

- `auth-platform-sdk/.../RemoteAuthzEngine.java`：`check` 与 `parseCheckBulk`。
- `auth-platform-sdk/.../RemoteAuthzEngineTest.java`：缺失/非 boolean allowed、空 check body。
- `auth-platform/deploy/rag-authz-fixture.sh`：APPLY 后读回/判权验证。
- `langchain4j-platform/deploy/smoke-rag-tenant-authz.sh`：D3 member/stranger、cache-off preflight、AP 故障断言。
- 运维文档：记录 semantic-cache 互斥和 enforce gate。

## 扩展性与成本

- 实施成本低，几乎不触碰业务调用链；回滚为 SDK/脚本回滚或 authz 切回 shadow。
- 不解决长期 auth-aware cache、GraphRAG 溯源、按用户授权版本；这些明确留在后续。
- 不提供 share shadow 观测；沿用已批准 API 规则。

## 风险评审

- 兼容性：合法响应无变化；畸形响应从普通 deny 变异常，是预期收紧。
- 事务/并发/幂等：不改业务事务；fixture TOUCH 幂等，验证只读。
- 性能：不增加 AP RPC；仅 JSON 节点类型判断。
- 安全：直接消除协议静默降级，并阻断已知缓存旁路组合。
- 灰度/回滚：disabled→shadow→enforce；任一门禁失败保持 shadow。

## 已知弱点

正确性依赖部署门禁被持续执行；如果某环境绕过脚本直接改配置，D3/缓存风险仍可出现。后续可把门禁提升为集中发布控制或配置策略。

