# 方案 D：Casdoor + SpiceDB 一次性权威切换（退役 auth-service 身份/RBAC）

## 1. 架构主张

把 auth-service 的账号、角色、租户角色、组和成员一次性迁入 Casdoor/SpiceDB；切换后 Casdoor 是身份/组权威，SpiceDB 是资源授权权威，auth-service 不再承载登录、refresh session 或 RBAC 管理写。edge 只接受 Casdoor access token。

```text
一次性迁移：auth DB -> validated export -> Casdoor + SpiceDB

运行态：Casdoor -> edge -> internal JWT -> services
       Casdoor webhook/reconcile -> AP admin -> SpiceDB groups
       knowledge -> AP SDK -> SpiceDB resource tuples/checks
```

## 2. 模块职责

- Casdoor：用户、组织、组、SSO、服务身份。
- AP：admin 管组同步、space relation、审计；server 管 check/write。
- LP auth-service：从 edge route 移除；数据库只读封存一个保留期后下线。
- capability frontend：OIDC client，不再出现本地注册/demo 密码/API key。

## 3. 核心流程

1. 冻结 auth-service 写，导出一致快照和 crosswalk。
2. 在 Casdoor 创建/匹配用户，确认每个用户的稳定 sub；导入 tenant-scoped groups。
3. 在 SpiceDB 写 role/group membership、space bindings 和 existing document parent_space；只有存在可审计 owner manifest 时才写历史 document owner，禁止推断。
4. dry-run 核对后切 edge 到 Casdoor-only；关闭本地登录 open paths、session secret、api-key secret。
5. 观察一个发布周期后退役 auth-service route/deployment。

## 4. 改动范围与成本

- 业务代码最终最简，但一次迁移和组织变更最大。
- 必须同步改前端、机器调用方、部署 Secret、运维脚本、RBAC 控制台归属。
- 对 Casdoor token claim 能力依赖最强。

## 5. 扩展性

- 权威边界最清晰；后续其他平台直接复用 Casdoor/AP。
- 如果 Casdoor 无法表达动态 scopes/tenant selection，需要额外 directory/claim enrichment 服务。

## 6. 风险评审（risk-reviewer）

| 维度 | 评审 |
|---|---|
| 兼容性 | 本地 login/register/refresh、API key、admin UI 合同一次失效，客户端协调成本最高 |
| 事务 | 运行态双权威最少；切换本身是跨系统批量迁移，必须冻结窗口和可重复脚本 |
| 并发 | 冻结后简单；若冻结不严，cutover 期间增量会丢 |
| 幂等 | import/upsert、关系 TOUCH 可重跑；Casdoor 创建用户的幂等键需实测 |
| 性能 | 运行态最短；edge 仅本地/JWKS 验 token，AP 做授权 |
| 安全 | 长期最好；短期错误 claim mapping 可能全员 401 或跨租户 |
| 数据迁移 | 四案最难；密码不可逆，用户不能无感迁移本地密码，需激活/重置或账号绑定 |
| 灰度 | big-bang 天生较弱；可按 tenant 分批，但同一前端/edge 多模式会增加复杂度 |
| 回滚 | Casdoor sub 已产生、组已变更后回到 legacy userId 很昂贵；必须保留旧 DB 与 edge compatibility image |

### 失败场景

- 本地 BCrypt 密码无法导入为 Casdoor 可用凭据，用户迁移需要重置/绑定流程。
- token 不含 tenant/scopes，edge 无法完成内部 JWT 映射。
- 一次性导入把全局 group 误当单租户，批量越权。
- 回滚到本地 session 后新 Casdoor 用户没有 legacy 账号。

## 7. 测试重点

- 全量迁移 dry-run/apply/reapply/rollback；源与目标 cardinality、抽样权限矩阵。
- 真实浏览器 OIDC、silent renew/logout、JWKS rotation。
- 每个 tenant 独立 canary；legacy 与 Casdoor token 身份对照。
- auth-service 摘流后所有内部/外部调用清单无遗漏。

## 8. 结论

这是理想目标态，但不适合无中间对账层地直接上线。应吸收其“单一权威”原则，并用方案 B 的 pull reconcile、dry-run 和分租户灰度降低切换风险。
