# 04 — Solution B：统一 mode-aware 授权门面，替换消费方分散模式判断

## 定位

把 single、bulk、AOP 敏感操作统一收敛到 knowledge 自己的 mode-aware authorization facade。分享不再直接依赖 SDK 的通用 `@CheckAccess` 语义，而由门面统一处理 disabled/shadow/enforce、错误指标和身份快照。

## 架构与职责

- 扩展现有 `KnowledgeAuthz`（或计划新增、命名需实施前确认的门面方法）表达 `authorizeDocumentOperation`，统一返回 allow/deny/error/would-deny。
- controller 只传裸 docId；门面从单次 `TenantContext.Tenant` 快照构造 subject 和完整资源 id，消除 fullId/bareId 双参数不变量。
- share/unshare 是否在 shadow 暴露由产品决定；若暴露，shadow 真 check、记录 would-deny、仍执行 viewer 写删。
- 继续使用 checkBulk 做检索，不改变数据面。

## 核心流程

1. 请求入口快照 tenant/user。
2. 门面派生 `document:<tenant>_<docId>` 并执行 full check。
3. disabled 直通；shadow 记录真实决策后放行；enforce 按真实决策。
4. 依赖异常统一映射安全结果和指标；不让 AOP 自行产生无指标 5xx。

## 改动范围

- `KnowledgeAuthz.java`、`RealKnowledgeAuthz.java`、`NoopKnowledgeAuthz.java`。
- `KnowledgeAccessApplicationService.java`、`DocumentShareController.java`、`KnowledgeAccessConfig.java`、`AuthzExceptionHandler.java`。
- `DocumentService.java` 可顺带改为入口身份快照。
- SDK 严格响应修复仍需采用方案 A 的部分。

## 扩展性与成本

- 中等成本；模式、指标、错误契约统一，可扩展未来 share/manage 操作。
- 会降低通用 `@CheckAccess` 示例价值，并改变现有 bean 条件与可能的 endpoint 可见性。
- 需要完整 Spring context 三模式测试和 controller 契约回归。

## 风险评审

- 兼容性：若 shadow 开放 share API，会从 404 变 2xx，是外部行为变化；必须先决策。
- 事务：shadow 下授权写会真实改变 SpiceDB，依赖故障时是否继续业务写必须明确。
- 并发：单次身份快照更稳；关系 TOUCH/DELETE 仍幂等。
- 安全：统一错误处理有利，但自研门面若漏掉代理/模式分支会引入新风险。
- 回滚：可切回 enforce-only AOP，但 bean 条件改动较多。

## 已知弱点

它解决的是一致性与可观测性设计债务，不是当前已证明的越权漏洞；在 shadow 分享规则未确认前实施容易把审计变成功能改造。

