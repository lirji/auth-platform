# 候选方案对比与风险评审（risk-reviewer + plan-judge）

## 1. 方案摘要

| 方案 | 核心 | 主要优点 | 主要缺点 |
|---|---|---|---|
| A | manifest + 幂等脚本 + 单栈逐开关 strangler | 最复用现有代码、阶段独立、成本适中 | 脚本不适合长期大规模治理，多对象写非事务 |
| B | 蓝绿 edge/frontend 入口，共享下游 | 流量隔离和回滚最好 | 基础设施/CORS/OIDC callback/双配置复杂 |
| C | 扩展 auth-platform-admin 为长期控制器 | 审计、漂移、并发、规模化最好 | 范围和高权限攻击面最大，拖慢本轮 |
| D | Casdoor UI 手工配置并一次硬切 | 短期代码最少 | 不可证明幂等、易漏顺序、回滚脆弱 |

## 2. 统一风险评审

### 2.1 兼容性

- A：内部 JWT、下游调用和机器 API Key 均不变；最兼容。风险在 build-time 前端模式，需并存两个构建才能按比例灰度。
- B：下游兼容，但蓝绿 issuer/audience/redirect/internal-JWT-key 可能漂移；OIDC callback 必须保持同色。
- C：运行链兼容；控制面新增 API/状态模型，兼容面反而扩大。
- D：维护窗内跨 edge/frontend/auth-service 同时变化，兼容错误只能上线后暴露。

### 2.2 事务与部分失败

Casdoor 的 org/user/role/permission 是多个远端对象，四个方案都不能获得本地数据库意义的原子事务。

- A：顺序步骤 + current/desired 重算 + journal + token postcondition；不自动回删未知/既有对象。
- B：数据准备仍依赖 A/C；蓝绿只隔离运行流量，不解决数据事务。
- C：可用 saga/outbox 状态机表达部分成功，但仍无法与 Casdoor 远端原子提交。
- D：靠人工记忆恢复点，最差。

### 2.3 并发与幂等

- A：每 org 单写者，默认 dry-run，apply 以 current state 为准；满足本轮规模。
- B：两套入口不应同时管理 Casdoor；若未定义单一控制面仍会冲突。
- C：数据库唯一约束和 plan hash 最强，但实现复杂。
- D：多个管理员并发修改会覆盖；无可靠 idempotency key。

### 2.4 性能与可用性

- A：edge 每次请求本地验 JWT，性能模式不变；首次/轮转 JWKS 依赖 Casdoor。Casdoor 登录/token endpoint 成为人类登录单点。
- B：可独立扩 green edge，但 Casdoor 自身仍需 HA；双入口资源成本高。
- C：reconcile 增加 Casdoor Admin API 压力，应分页、限速、退避；不应进入请求判权热路径。
- D：无渐进观测，峰值登录时才可能发现 Casdoor 容量问题。

### 2.5 安全

- A：未知 scope 丢弃、单 org、默认不 prune；但 dual 期 legacy 仍是绕行路径，必须限时并监控。脚本 secret 只从环境/Secret 输入且不得打印。
- B：域名、CORS、CSP、redirect 增多，配置攻击面上升；流量隔离有利。
- C：高权限 client secret 常驻且具全局写能力，是新的高价值攻击目标。
- D：人工误配和审计不足风险最高。

所有方案共同风险：当前主代码不能证明 chat/agent/channel/eval/vision/voice/analytics 服务端 scope 门禁。它是 release blocker，不得被方案评分掩盖。

### 2.6 数据迁移

- A：最适合“只重建三条 demo seed，不读 auth DB”；manifest 可审阅。
- B：仍需 A/C 准备数据，方案本身没有迁移能力。
- C：适合真实生产持续迁移，但本轮过度设计。
- D：少量 demo 可做，但无法自动保证角色分配后 permission 重建。

### 2.7 灰度与回滚

- A：每一层都有反向开关；Phase C 前半仍可快速回退。删除 route/secret/DB 后回滚成本上升，因此延后。
- B：绿权重归零最快；完整蓝栈保留时最佳。
- C：控制面与运行面仍需 A/B 的灰度机制；自身 apply 回滚复杂。
- D：一次性切换，恢复跨多个对象，最差。

## 3. 隐藏失败场景

1. **角色已加但 permission 未刷新**：新 token 无 scope；用户能登录但所有门禁 403。
2. **脚本把 401/500 当“角色已存在”**：实际未写入却继续运行；现脚本 `|| true` 存在该风险。
3. **valid Casdoor token + dual API Key**：Casdoor 成功时 key 被剥离，不会合并补权；这是正确语义但易被误判为 fallback。
4. **JWKS 冷启动/轮转失败**：dual 期可能回落 session/key，造成指标看似成功；oidc-only 则 401。
5. **旧 access token 未过期**：Casdoor 撤权后仍可用旧 scope，直到 TTL 到期；必须用“最大 access TTL”定义收敛窗口。
6. **前端 client ID 与 audience 不同**：全量 Casdoor token 401；必须在发布前静态检查。
7. **两个 allowlist 漂移**：前端显示与服务端实际不一致；edge 仍是最终安全边界，但用户体验和测试失真。
8. **token 超过 header 限制**：edge 直接 431，应用指标看不到；需入口层 header-size 指标/日志和上限测试。
9. **Casdoor owner 错误或多 org**：数据写到错误 tenant；不得允许客户端 header 覆盖 owner。
10. **OIDC 全量后旧 RBAC UI 仍可写 auth-service**：形成双真相源；必须提前关闭 legacy admin writes 和隐藏控制台。
11. **直接访问下游**：`InternalTokenAuthFilter` 不拒绝匿名，且各服务默认允许 API Key fallback；生产必须靠网络边界并关闭 fallback，不能把该 filter 描述成统一 401 门。
12. **前端回滚假象**：Phase C 删除 `/auth` 后只把前端改回 apikey 并不能恢复账号密码登录；需要同时恢复 edge route/session filter/auth-service。

## 4. 评分规则

每项 1–5，5 为最好。对“复杂度、测试难度、回滚成本”，5 分分别表示**复杂度低、容易测试、回滚成本低**。等权总分 35；不以分数代替发布阻塞判断。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 声明式单栈 strangler | 5 | 4 | 4 | 4 | 3 | 4 | 4 | **28** |
| B 蓝绿入口 | 5 | 4 | 2 | 3 | 5 | 3 | 5 | **27** |
| C 长期控制器 | 5 | 2 | 1 | 5 | 5 | 2 | 3 | **23** |
| D 人工大爆炸 | 2 | 1 | 4 | 1 | 1 | 1 | 1 | **11** |

评分诚实性说明：

- A 并非绝对最优；它的扩展性弱于 B/C，且 shell 的错误处理和 JSON 规范化必须认真测试。
- B 只比 A 少 1 分，但当前项目没有显示成熟的双入口 OIDC 流量基础设施，完整采用会增加非业务故障面。
- C 的正确性潜力最高，但“要先造控制器才能迁三条 demo user”会制造新的长期系统和攻击面。
- D 的表面复杂度低不代表总体风险低；人工操作把复杂度转移给运行期。

## 5. 评审结论

不机械全选 A。最终采用：

- A 的 manifest/reconcile、单栈开关顺序和 runtime scale-to-zero；
- B 的“独立 oidc 前端 canary URL/构建”用于 build-time flag 的真实小流量灰度，不复制下游；
- C 的 plan/diff、journal、单写者、drift 报告原则，但不建设在线控制器；
- 拒绝 D 的人工硬切。

最终方案的已知弱点必须保留在验收记录中：机器 API Key 仍是例外真相源、Casdoor 多对象写不具事务、单 org 限制、token 撤权有 TTL、scope 服务端门禁前提当前未被代码证实。
