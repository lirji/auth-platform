# 方案 A：最小同步桥接（业务服务直接双写）

## 1. 架构主张

保留 auth-service 作为 RBAC 权威源，只替换 edge 登录验签为 Casdoor；knowledge-service 在现有未提交 `KnowledgeAuthz` 草稿上补齐 `SubjectResolver`、`@CheckAccess`、space 与关系写。auth-service 的管理写成功后同步调用 AP，把用户/组/角色关系直接写入 SpiceDB。

```text
Casdoor token -> edge -> internal JWT -> TenantContext
                                   -> knowledge -> AP SDK -> auth-platform-server -> SpiceDB
auth-service AdminService mutation -> AP SDK -> SpiceDB role/group/space tuples
```

## 2. 模块职责

- edge-gateway：Casdoor token 校验与内部 JWT 换发。
- auth-service：继续保存 Role/Group/UserGroup/TenantRole，并在 `AdminService` 写用例中同步投影。
- knowledge-service：space/document 业务权威；同步写关系；单资源 AOP 判权、query/list 批量判权。
- auth-platform-admin：保留现有 Casdoor GroupSync，只承担 Casdoor 原生组；LP RBAC 主要由 auth-service 直写。

## 3. 核心流程

1. `AdminService.replaceGroupMembers/updateGroup/replaceTenantRoles/assignRoles` 在 auth DB 事务提交后调用新增投影器。
2. 投影器把角色/组拆成 tenant-scoped SpiceDB group，再绑定 space relation。
3. `DocumentService.upload` 完成业务写后 TOUCH owner/parent_space；delete 先撤关系再删数据。
4. controller 调拟新增 application service，后者的 public 方法接收服务端构造的完整 authz ID 并用 `@CheckAccess`。

## 4. 改动范围与成本

- 中等：AP SDK/server 加固、edge SSO、knowledge space/ReBAC 都是必需；另需把 AP client 依赖引入 auth-service。
- 不新增消息基础设施；主要新增同步 adapter、补偿任务与配置。
- 预计实现速度四案最快。

## 5. 扩展性

- 复用 SDK 后可快速复制到其他服务。
- 但 auth-service 与 AP 协议强耦合；每个 RBAC mutation 都要维护投影代码，新增关系类型会持续扩散。

## 6. 风险评审（risk-reviewer）

| 维度 | 评审 |
|---|---|
| 兼容性 | auth-service 仍是本地账号数据模型，Casdoor 又是身份源，容易形成 userId/组双权威；旧 Admin API 可继续但语义变复杂 |
| 事务 | auth DB 与 SpiceDB 无共同事务；若在 DB 事务内远调，会延长锁并可能回滚；若 after-commit 调，失败产生漂移 |
| 并发 | 两管理员并发写虽有 If-Match，但 after-commit 投影可能乱序；必须带版本并忽略旧版本 |
| 幂等 | TOUCH 具备幂等性；删除/重建需精确 current tuple 和版本水位 |
| 性能 | RBAC 管理写多一个远程 RTT，通常可接受；批量成员替换可能超过 SpiceDB 单批限制，需分页 |
| 安全 | auth-service 继续持有身份/RBAC 写权，Casdoor 统一身份只完成一半；两套管理面扩大攻击面 |
| 数据迁移 | 需要 userId crosswalk 与一次全量 backfill；迁移后仍需双向一致性治理 |
| 灰度 | 可按 `projection.enabled`、knowledge shadow/enforce 分步开，灰度友好 |
| 回滚 | 关闭投影与 ReBAC 即可恢复旧 scope 路径；Casdoor 登录切换仍需独立回滚 |

### 失败场景

- auth DB 已提交但 SpiceDB 写失败：新权限不生效（收紧）或旧权限未撤（放大），需 outbox/reconcile，否则不可接受。
- Casdoor sub 与 `USERS.USER_ID` 未迁移：同一人生成两个 SpiceDB subject。
- 角色更新后要更新大量 space bindings，管理请求超时。
- auth-service 实例重试旧 mutation 覆盖新投影。

## 7. 测试重点

- `AdminService` 每个 mutation 的投影 payload、版本乱序、远端超时/重试。
- auth DB commit 成功 + AP 失败的 reconcile。
- 同一角色跨多个 space 的批量绑定。
- Casdoor sub crosswalk，旧 username 不得出现在新 tuple。

## 8. 结论

适合短期试点和快速止血，但不满足“身份与授权权威真正收敛”的长期目标。若选择，只能作为有明确退役日期的过渡方案。
