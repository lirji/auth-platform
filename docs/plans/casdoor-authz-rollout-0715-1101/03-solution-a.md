# 候选方案 A：声明式脚本 reconcile + 单栈 feature-gated strangler

## 1. 架构

保留现有单套 edge 和业务服务，用 Casdoor 无密钥 manifest 描述 org/user/role 期望态；把现有 `casdoor-seed.sh` 演进为“默认 dry-run、显式 apply、只增改不删”的 reconcile。运行面按已有/新增开关逐层切换：edge Casdoor on，前端 dual，前端 oidc，legacy session off，auth route off，auth-service scale-to-zero。机器 API Key 始终保留。

```text
manifest -> casdoor-seed.sh(reconcile) -> Casdoor objects -> token postcondition
                                                   |
frontend apikey -> dual -> oidc                  edge(-120)
frontend session ---------------------------> session(-110,逐步关)
machine X-Api-Key --------------------------> api-key(-100,保留)
```

## 2. 模块职责

- manifest：只存 owner/org、username、期望 role、是否 enabled，不存密码/client secret；
- reconcile 脚本：读取 current、规范化 diff、逐 org 单写、写 journal、后置签 token 校验；
- Casdoor：人类用户、tenant organization、角色与 permission 的唯一管理面；
- edge：验 Casdoor token、allowlist、换内部 JWT；统计 Casdoor/session/api-key 使用情况；
- 前端：用构建期模式做流量迁移；oidc 后隐藏 API Key 和 legacy RBAC 控制台；
- auth-service：先只读冻结，再取消外部路由，最后缩容为 0；
- 运维：按环境和阶段推进、在每个回滚点签字。

## 3. 核心流程

1. 对每个 tenant 建/核对同名 org，核对关联 application/model（字段和写 API 待本地验证）；
2. 建用户并保持单 owner；建该 org 下 5 个角色；
3. 先把用户分配到角色；
4. 再更新或重建 11 个 permission；
5. 为每个角色样本签发新 token，校验 owner/sub/权限集合；
6. 开 edge、再 dual、再 oidc；跑 5×11 矩阵；
7. legacy 使用为零后，先阻断新 session，再关 session filter；API Key filter 仅留机器；
8. auth-service scale-to-zero，源码/DB 保留至观察期结束。

## 4. 改动范围

- 小到中：脚本/manifest/smoke、edge 指标与 session 开关、Compose/Helm、前端发布参数和运维文档；
- 不改变内部 JWT、业务 DTO、数据库、消息结构；
- 若服务 scope 403 矩阵失败，需先处理独立发布阻塞，不能继续灰度。

## 5. 并发、事务与幂等

- Casdoor 多对象写没有跨对象事务：用“org→user→role assignment→permission→token probe”的有向步骤和 journal 补偿；
- 每 org 单写者，CI/job 级互斥；脚本重跑从 current state 重新算 diff；
- 默认不 prune，失败后留下的对象不参与运行流量，因此 Phase A 可安全重跑；
- permission delete+add 仅允许在 Phase A 或维护窗。若实测存在 `update-permission`，优先原位更新；接口与字段待验证；
- 角色变化后的 permission 重建是强制 post-hook，而不是操作人员记忆事项。

## 6. 扩展性

- 几十个 org/数百用户可接受；manifest 可按 org 分片；
- 角色数量增长不改 edge，permission name 仍受固定业务 scope allowlist 限制；
- 若进入大规模持续生命周期管理，脚本的并发、状态和审计会成为瓶颈，可升级到方案 C。

## 7. 实施成本与弱点

- 成本：中低；最大程度复用已合入链路；
- 弱点：脚本不是长期控制器，多对象写非事务；前端 build-time flag 需要双构建做百分比灰度；Casdoor 变更到 token 撤权受 TTL 延迟；机器 API Key 仍是第二真相源例外。

## 8. 适用结论

最匹配本轮 dev/演示重建、可回滚灰度和不扩展在线架构的约束，建议作为最终主方案，并吸收方案 B 的隔离 canary 和方案 C 的审计思想。
