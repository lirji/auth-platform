# 需求分析（requirements-analyst）

## 1. 任务定义

本任务只形成实施设计，不修改业务代码。目标是把 `/Users/liruijun/personal/LLM/langchain4j-platform`（下称 LP）接入 `/Users/liruijun/personal/LLM/auth-platform`（下称 AP），并同时完成三层收敛：

1. knowledge-service 以 AP SDK + SpiceDB 实现 space/document 细粒度 ReBAC。
2. 把 LP `auth-service` 的既有 RBAC 角色、用户组及成员关系迁移/投影为 SpiceDB group，并把角色语义绑定到 space relation。
3. 外部身份统一为 Casdoor SSO；edge-gateway 不再以 `X-Api-Key` 或 auth-service 本地账号会话作为最终入口，但 edge-gateway 签发内部 JWT、下游恢复 `TenantContext` 的接缝保持不变。

## 2. 已确认的现状与业务规则

以下规则有代码依据，不是方案假设：

- 两仓库父 POM 均为 Spring Boot `3.3.5`、Java `21`。
- AP `knowledge.zed` 已定义 `user/group/organization/space/folder/document`；space/document 支持 user 与 `group#member`，document 的 `view/edit` 可继承 parent_space。
- AP 明确约定知识资源 ID 带租户前缀；本任务进一步固定 document 为 `<tenantId>_<docId>`，space 同理采用 `<tenantId>_<spaceId>`。
- AP SDK 的 `SubjectResolver` 是消费方 SPI；knowledge-service 必须从 `TenantContext.current().userId()` 返回 `SubjectRef.user(...)`。
- AP SDK 的 `@CheckAccess` 只能从被代理方法的、名称等于 `resourceIdParam` 的实参取完整资源 ID；它不会自动拼 tenantId，也不支持 SpEL。
- LP 内部 JWT 当前 claims 为 `sub=tenantId`、`uid=userId`、`scopes`；`InternalTokenAuthFilter` 将其恢复为 `TenantContext.Tenant`。本次不得改变这个内部契约。
- 统一身份后 `TenantContext.userId` 必须是 Casdoor access token 的 `sub`，SpiceDB `user:<id>` 也使用同一值。
- LP 当前 document ID 是 `SHA-256(tenantId + ":" + displayName)` 的前 16 个十六进制字符；同租户同名上传视为版本更新。
- LP 现有 `DocumentInfo` 不记录上传者/owner，历史数据无法仅凭现有 registry 可靠还原 owner；迁移时不得把当前操作者或任意管理员伪造为历史 owner。
- LP 当前公共库使用 `tenantId=__public__`，读查询会并入公共分区，公共写仍受 `public-ingest` scope 控制；公共图片不支持。
- LP 当前 RBAC 有四层有效 scope：个人直配、个人角色、租户基础角色、用户组角色。`Role`/`Group` 是全局定义；组不嵌套；用户属于租户。
- AP `GroupSyncService.sync()` 是 Casdoor 期望态与 SpiceDB 当前态的 TOUCH/DELETE 差量同步，并有 webhook + scheduled reconcile 入口。
- SpiceDB schema 的 `group.member` 允许 `user | group#member`；因此可以把 LP 的“组→角色”表示成嵌套 group，但现有 `GroupSyncService` 尚不能安全地对嵌套直接元组做差量（它用 `lookupSubjects` 读展开后的 user）。
- LP 工作树当前已有一组**未提交**的 knowledge authz 草稿（SDK 依赖、`KnowledgeAuthz`、`RealKnowledgeAuthz`、查询过滤及集成测试）。实施 Agent 必须保留并审查这些用户改动，不能按干净基线覆盖。

## 3. 功能需求

### 3.1 身份与租户

- 浏览器使用 Casdoor OIDC Authorization Code + PKCE 登录、续期与登出。
- edge-gateway 校验 Casdoor access token 的签名、issuer、audience、exp/nbf；不得把外部 Bearer 原样转发到内网。
- edge-gateway 从经校验的 token 取：
  - `userId = sub`（硬规则）；
  - `tenantId`（claim 名及生成规则为待验证项）；
  - 继续服务旧有粗粒度门禁所需的 scopes（来源为 Casdoor token claim 或显式 group→scope 映射，待 M0 验证）。
- active tenant 必须来自受签名保护且能证明用户 tenant membership 的 claim/交换结果；不得从客户端自定义 header/query 直接接受 tenantId，也不得只验证格式不验证成员资格。
- edge-gateway 仍调用 `InternalToken.mint(TenantContext.Tenant)`，下游仍只消费 `X-Internal-Token`。
- `X-Api-Key`、`SessionBearerAuthFilter` 使用的本地 session JWT、`/auth/login|register|refresh|logout` 必须退出主路径；灰度期允许有明确到期日的兼容开关，但不能与 Casdoor 身份静默混用。
- 机器调用方（eval、脚本、外部集成）改用 Casdoor 可验证的服务身份 access token。Casdoor 是否支持目标环境所需 grant 必须在 M0 实测；不能把长期用户 token 当服务密钥。

### 3.2 space/document 领域与 ReBAC

- `space` 是知识库，`document` 是文档；现有无 space 实体的文档迁移到每租户 `default` space。
- 新文档至少写：
  - `document:<tenant>_<doc>#owner@user:<casdoorSub>`；
  - `document:<tenant>_<doc>#parent_space@space:<tenant>_<space>`。
- 新 space 至少写：
  - `space:<tenant>_<space>#owner@user:<casdoorSub>`；
  - `space:<tenant>_<space>#parent_org@organization:<tenant>`；
  - 对应 `organization:<tenant>` 的组织关系（是否写 organization admin/member 需结合租户治理确认）。
- 单资源读写用 `@CheckAccess`；查询/列表是多资源场景，必须用 `checkBulk`/lookup 后过滤，不能用一个伪造的 query 资源替代逐文档授权。
- 上传到 space 要求 space `edit`；文档详情要求 document `view`；删除/替换要求 document `edit`；space 成员管理要求 space `manage`。
- `DocumentController.list/get/delete`、`KnowledgeQueryController.query` 都不能泄露未授权文档的正文、元数据或存在性。未授权详情统一按产品选择返回 403 或 404；最终方案建议外部返回 404 以降低枚举，审计内保留 deny 原因。
- AP 不可用时，enforce 模式 fail closed（403/503 分开）；不得自动退回 Noop 放行。需要 `disabled / shadow / enforce` 三态，而不是一个含义模糊的 boolean。
- 公共库的现有行为不得意外收紧或放大。目标态用 `public_viewer@user:*` 表示公开读；灰度期可双算并比较现有 `shared` 短路与 SpiceDB 结果。

### 3.3 RBAC/组投影

- LP 现有 `Role`、`Group`、`USER_ROLE`、`TENANT_ROLE`、`GROUP_ROLE`、`USER_GROUP` 数据必须有一次可核对的迁移，不允许只迁 demo 种子。
- 全局 group 名必须按 tenant 拆分后进入 SpiceDB，避免 AP 当前“短组名”造成跨租户同名碰撞。
- 推荐规范（均为拟新增约定）：
  - `group:lc4j_<tenant>_group_<groupName>`：原用户组；
  - `group:lc4j_<tenant>_role_<roleName>`：角色组；
  - role group 可直接包含 user，也可包含原 group#member；
  - space relation 只绑定 role group，具体 role→relation 由显式配置表决定。
- role→space relation 不可从 scope 名隐式猜测。默认建议映射须经业务确认：`admin→admin`、`editor→editor`、`viewer/analyst/approver→viewer`；自定义角色未配置时 fail closed，不自动给 viewer。
- ReBAC 映射与粗粒度 scopes 迁移分开核对：迁移前由 `EffectivePermissionResolver` 计算的每用户有效 scope 集，应与 Casdoor token 中允许词表后的 scope 集一致（经审批废弃项除外）。
- 迁移后 Casdoor 是用户和组成员权威源；auth-service 账号密码和刷新会话不再是身份权威。auth-service 的旧 RBAC 是否短期保留为只读迁移源，必须有退役点。
- 同步必须幂等、可重跑、能处理成员清空、组删除、角色降权和 space 删除；删除类变更使用 fully-consistent 对账或等价强一致策略。

## 4. 非功能需求与边界条件

### 4.1 一致性、事务和并发

- knowledge 元数据/向量/ES/图谱/Redis 与 SpiceDB 之间不存在分布式事务，方案必须定义 PENDING/ACTIVE/DELETE_PENDING 状态、补偿和 reconcile，而不是宣称“同一事务”。
- TOUCH 用于幂等创建；删除需按精确 tuple 或资源过滤器执行。版本重传不得累积多个 owner。
- 删除顺序必须优先撤权、后删业务数据；失败时宁可数据暂存但不可读，不能数据已删而权限残留长期存在。
- 多实例不能依赖进程内 `AtomicReference<ZedToken>` 保证全局 read-after-write；试点 enforce 建议 critical read 使用 fully consistent，待监控后再优化一致性策略。
- `@CheckAccess` 必须位于经 Spring 代理的 public 方法上，禁止同类 self-invocation 绕过；完整 resource ID 必须由服务端根据 TenantContext 构造，不能信任客户端传入 tenant 前缀。

### 4.2 性能

- `/rag/query` 必须一次批量判权，不得 N+1；候选池过滤后可能不足 topK，需要可观测的 over-fetch/补召回策略。
- 列表按批次 checkBulk 或 `lookupResources` + 本地交集；要为大租户预留分页。
- 记录 authz check 总量、p50/p95/p99、deny 比例、transport error、候选过滤率和 topK 不足率。

### 4.3 安全

- AP `auth-platform-server` 当前 `/v1/relationships` 等端点无 Spring Security；正式接入前必须用内网 service credential/mTLS 与 NetworkPolicy 加固，尤其关系写端点。
- SDK transport error 映射 503，授权 deny 映射 403/404；两者不能混淆。
- OIDC 只接受 access token，不接受 id_token 代替 API 凭证；校验 audience，防止其他 Casdoor 应用 token 被复用。
- group/tenant/resource ID 需要规范化与长度限制，日志不得输出 token、client secret 或完整敏感文档内容。

## 5. 明确非目标

- 不重写 SpiceDB 引擎，也不把 knowledge-service 改为直连 SpiceDB gRPC。
- 不改变内部 JWT 的 claims 形状、头名和 `TenantContext` 传播模型。
- 不在本次把 folder、评论、公开链接管理 UI 全部产品化；schema 中已有能力可后续使用。
- 不把所有 LP 服务一次性改造成 ReBAC；试点只在 knowledge-service，但 SSO 入口变化会影响所有经 edge-gateway 的调用方和回归测试。
- 不保留生产可用的长期明文 `X-Api-Key` 作为“临时永久方案”。
- 不把 Casdoor/SpiceDB 的底层表作为业务服务直接读写的数据接口。

## 6. 歧义与必须前置验证的决策

| 编号 | 待验证/决策 | 不确认的后果 | 建议硬门槛 |
|---|---|---|---|
| A1 | 真实 Casdoor access token 中 tenant、groups、roles/permissions/scopes 的字段与格式 | 无法可靠构造内部 JWT | M0 用真实 token 固化 claim contract；缺字段不得上线 |
| A2 | 一个用户是否可属于多个 LP tenant；一次 token 如何选择 active tenant | 可能跨租户投影或错误签发 tenant | 试点先限制一 token 一个 active tenant；多租户另设计切换/二次授权 |
| A3 | role→space relation 的业务映射及自定义角色策略 | scope 与 ReBAC relation 语义不等价 | 显式配置并由业务 owner 签字；未知角色拒绝投影 |
| A4 | 现有全局 Group 是否允许包含多租户用户 | 同名/混租组会污染关系 | 导出报告并按成员 tenant 拆组，空/跨租户异常需人工确认 |
| A5 | space 是否需要本次提供 CRUD/成员管理 API，还是只落 default space | 影响领域模型与接口范围 | 最终方案按“最小 CRUD + 旧接口默认 default”规划；若仅默认库可裁剪 UI，不裁剪数据模型 |
| A6 | 未授权文档详情返回 403 还是 404 | 客户端契约与枚举风险不同 | 建议外部 404、内部审计 deny；须固化契约测试 |
| A7 | Casdoor 的机器身份 grant 与 token 生命周期 | eval/脚本无法从 X-Api-Key 迁移 | 在关闭 api-key 前完成 service-account E2E |
| A8 | auth-service RBAC 迁移后是只读保留还是立即退役 | 双权威源会持续漂移 | 建议一个发布周期只读，核对完成后从 gateway 摘除本地登录与管理写 |
| A9 | 公共库是否纳入 SpiceDB `public_viewer` | 双模型可能结果不一致 | shadow 期双算；enforce 前固化公共文档规则 |
| A10 | 历史文档 owner 是否存在可审计外部来源（日志、导入 manifest） | 无法生成可信 owner tuple | 无可信证据则 owner 保持 unknown，仅由 default space 角色授权；不得推断或补造 |
| A11 | 目标 Casdoor 版本的 group/user membership mutation API、幂等键与分页语义 | 无法安全执行/续跑 group import | M0 用隔离 organization 实测 create/match/update/retry；冻结请求合同后才允许 apply |

## 7. 验收标准（需求级）

1. Casdoor 用户 A 的 `sub` 在 edge、内部 JWT `uid`、TenantContext、SpiceDB user 四处完全一致。
2. 无/伪造/错误 issuer/audience/过期的 Casdoor token 均在 edge 返回 401，且不产生内部 JWT。
3. 生产 profile 下 `X-Api-Key` 和 auth-service 本地 session token 不能访问业务 API。
4. 同租户未授权用户无法通过 query/list/get 获取文档；owner、space role group、document viewer 分别能按 schema 继承得到正确权限。
5. 跨租户即使 docId/spaceId 相同也互不授权，实际 SpiceDB ID 符合 `<tenantId>_<id>`。
6. LP 既有 RBAC 导出数、Casdoor 导入数、SpiceDB 直接 tuple 数均可对账；跨租户同名组不合并。
7. 重复执行迁移/reconcile 不增加重复关系；成员移除、角色降级和组删除能收敛为拒绝。
8. AP/SpiceDB 故障时 enforce 不泄露数据；恢复后 reconcile 将 PENDING 项收敛。
9. 旧 `/rag/documents`、`/rag/query` 调用不传 spaceId 时仍使用 default space，响应的既有字段语义保持兼容。
10. 单元、契约、集成、E2E、迁移演练、灰度/回滚演练全部通过；详细指标见 `test-plan.md`。
