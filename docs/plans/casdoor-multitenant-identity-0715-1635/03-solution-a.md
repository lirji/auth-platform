# 候选方案 A：一租户一 org + 一 app + 动态注册表（推荐）

## 1. 架构

保持仓库已实现并由 token claim 证明的核心映射：`Casdoor owner = tenantId`。每个租户仍拥有独立 organization/application；新增一份版本化、无密钥的 tenant identity registry，作为前端登录路由和 edge 验证 `(owner,aud)` 的共同输入。

```text
tenant manifest
  -> reconcile(org -> app -> user -> role -> permission -> token probe)
  -> atomic registry snapshot publish
       -> SPA lookup tenant -> clientId -> tenant-scoped UserManager
       -> edge hot reload -> verify signature/iss/time + (owner,aud) binding
  -> owner/sub/scopes internal JWT
  -> existing Phase B-0 -> services -> ReBAC(<owner>_<resource>)
```

注册表只含 `tenantId/organization/application/clientId/enabled/revision/redirectUris` 等公开配置；不含 client secret、密码或 token。字段是本计划的新协议，不声称当前仓库已有。

## 2. 登录流程

1. 登录页输入/选择 tenant alias，向 edge 的公开 discovery endpoint 做精确 lookup；默认不返回完整租户列表，避免无意枚举。
2. 前端用返回的 `issuer/clientId/organization` 动态构造该 tenant 的 `UserManager`。若固定版本实验证明 Casdoor 需要 `organizationName`，通过 `signinRedirect({extraQueryParams:{organizationName}})` 加入；否则 clientId 已唯一绑定 org，不发送该参数。
3. redirect 前把 `pendingTenant + registryRevision` 写入 tab-scoped sessionStorage；OIDC state/nonce 仍由 `oidc-client-ts` 管理。
4. callback 用 pending tenant 恢复相同 manager 完成 code 兑换；解 token 后前端做 UX 级 `owner===expectedOrg` 断言，edge 做真正安全校验。
5. active tenant 与 manager key 写入 sessionStorage，bootstrap/silent renew/signout 都按它恢复；切租户必须先清 active User 和 API key，再发起新登录。
6. 所有租户 app 登记同一环境的 callback、silent、post-logout URI；URI 数量随环境数增长，不随租户路由路径增长。

## 3. edge 验证与热更新

- `CasdoorTenantRegistry` 在内存中维护不可变双索引：`tenant/org -> record` 与 `clientId -> record`，加载时拒绝重复 tenant、org 或 clientId。
- `CasdoorTenantRegistryRefresher` 定时拉取或读取完整 snapshot，校验 schema/revision/hash 后以 `AtomicReference` 一次替换；失败继续 LKG并把 readiness 标 stale。
- `CasdoorDecoderConfig` 的 validator 从当前静态 `audienceValidator(List)` 演进为：JWT 任一 aud 对应启用 record，且 JWT `owner` 等于 record.organization；issuer/timestamp/JWKS 逻辑不变。
- 请求热路径只读内存索引，不调用 Casdoor Admin API和 registry 服务；1000 tenant 不产生 O(n) 查找。
- 静态 `CASDOOR_AUDIENCES` 只作显式 break-glass fallback；动态和静态模式必须互斥，避免不清楚哪个是真相源。

## 4. 租户开通与事务边界

按同一 org 的有向 saga 执行：

1. 获得 org lease，验证 manifest 与 redirect origins；
2. create/update org；
3. create/update app（clientId 唯一，secret 外部 Secret 管理）；
4. create/update users；
5. create roles并完成 user assignment；
6. create/update permissions；若固定版本只支持 delete+add，则维护窗执行并记录空窗；
7. 为角色样本签 token，断言 `owner/aud/sub/permissions`；
8. GroupSync 写 membership/默认 space；历史主体按 crosswalk 同批 TOUCH new + DELETE old；
9. 发布新 registry snapshot；
10. 标记 tenant active，释放 lease。

步骤 1–8 任何失败都不发布 registry，新租户对登录不可见；已有对象保留供重跑，默认不 prune。registry publish 是可见性提交点，不是 Casdoor 多对象事务。

## 5. admin 方案

- 最终前端 admin 通过 edge 访问 auth-platform-admin 的 Casdoor-backed adapter，不直接调用 Casdoor Admin API。
- adapter 写 desired manifest/operation journal，planner 产出 diff，applier 复用同一 reconcile；UI 展示 pending/succeeded/failed/compensating，而不是维持 legacy “三次 HTTP 都像同步事务”的假象。
- legacy `/auth/admin/**` 只在 backfill/双写窗口保留：先 Casdoor shadow write+对账，再 Casdoor authoritative、legacy read-only、最终 route off。
- `GroupSyncService` 收到 operation 完成信号或定时对账，只同步 group membership 到 SpiceDB；不管理 application/audience/scope。

## 6. 对现有模块的影响

- 前端：中等；OIDC manager 生命周期、登录页和 admin key 改动明显，但 token claim 映射不变。
- edge：中等；新增 registry 与动态 validator，内部 JWT/DUAL/ONLY/Phase B-0 不变。
- auth-platform deploy/admin：较大；manifest、reconcile、journal、multi-org GroupSync 和 admin adapter。
- knowledge/ReBAC：低；资源前缀规则不变，只需主体 crosswalk和多 org groups。
- 运维：每租户对象数量线性增长；Casdoor org/app 管理成本较高，但故障和配置可按租户隔离。

## 7. 数百/上千租户扩展性

- 登录/验 token：内存索引 O(1)，单 snapshot 数 MB 级可控；需做 10k client 压测。
- Casdoor：org/app/user/role/permission 对象线性增长，reconcile 必须分页、限流、按 org 有界并行；不能每次全平台 delete+add permission。
- redirect URI：每 app 重复同一环境 URI，配置量为 tenant×environment；需要自动化和 drift scan。
- issuer/JWKS 共用时 edge 仍只有一个 decoder；若未来每租户独立 issuer，本方案需升级为 issuer registry，不在当前目标。

## 8. 风险评审

- **兼容性**：最强，owner→tenant、ReBAC 前缀和现有 token extraction 不变。
- **事务**：Casdoor 多对象不可原子；以 registry publish 作流量可见性门，journal 重跑收敛。
- **并发/幂等**：同 org lease + current/desired diff；不同 org 可并行。多副本 lease 实现待选（数据库/CI 单写者），不能依赖 Java `synchronized`。
- **性能**：edge 无远程热路径；registry reload 必须避免大对象频繁复制和 GC 峰值。
- **安全**：关键是 `(owner,aud)` 成对验证、registry 完整性和 adapter 高权限 secret 隔离。
- **回滚**：registry revision 回退、前端 dual/apikey、edge DUAL/off 均可逆；已创建 Casdoor 对象无需立即删除。

## 9. 已知弱点

1. org/app 数量与租户线性增长，Casdoor UI、redirect URI 和 secret 生命周期的运维成本最高。
2. registry 成为新的安全关键配置，需要签名/认证、LKG、版本回退和漂移监控。
3. 新 tenant 虽免 edge 重启，但不是“零控制面动作”：仍需完整 reconcile、token probe、snapshot publish。
4. admin adapter/reconcile 是高权限控制面，必须最小权限、网络隔离、审计和限速。
5. 用户跨租户需要多个 Casdoor user/sub；无法提供一个全球 userId 的自然统一视图。

## 10. 适用结论

它最大程度复用仓库已验证的 owner→tenant 与 `<tenant>_` ReBAC 规则，同时满足免重启和大租户数要求。最终方案以 A 为主，并吸收方案 B 的“稳定 tenant claim contract”思想（但不更换 owner）、方案 C 的登录别名/子域体验、方案 D 的组同步治理。
