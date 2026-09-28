# P1 可信身份与成员契约 v1

状态：P1-00 冻结。当前用户已授权继续实施。沿用 v0.2 方案和既有进程；这里冻结薄路径，不改变后续角色、Scope 或图一致性契约。

## 数据权威与持久化

auth 拥有治理数据。新增 `auth-platform-governance` Maven 库模块，供既有 admin/server 使用，不增加服务。PostgreSQL 16 独立业务库、固定 `auth_governance` schema、Flyway 独立序列；不访问或迁移 Casdoor、SpiceDB、OA、commerce 业务表。模型 ID 为 UUID 的字符串表达，时间为 UTC Instant。

| 模型 | 稳定字段与不变量 |
|---|---|
| Principal | 全局 id；kind=HUMAN/SERVICE；status=ACTIVE/SUSPENDED；version>=1。HUMAN 可有多个显式绑定的登录身份；SERVICE 不使用员工登录身份 |
| LoginIdentity | 精确匹配 issuer+subject，唯一映射 principal；邮箱/显示名不参与绑定。不在首次登录时自动建管理员或合并人 |
| Tenant | 全局 id；唯一 code；status=ACTIVE/SUSPENDED；version>=1 |
| Membership | id、tenant_id、principal_id，唯一 tenant+principal；member_kind=EMPLOYEE/PARTNER/GUEST；status=ACTIVE/SUSPENDED/LEFT；generation/version>=1；valid_from 包含、valid_to 排除。PARTNER/GUEST 必须有本租户 sponsor_membership_id，邀请接受不授予业务角色 |
| LegacyIdentityBinding | source_system+source_tenant_ref+source_subject_ref 唯一，指向现有 principal/membership；本阶段仅接受隔离映射。未知或冲突绑定返回冲突，不改旧主键 |
| CommandRecord | 操作者+目标租户+操作+command_id 唯一；规范输入摘要相同则重放同一结果 ID，摘要不同返回冲突；与业务变更同事务提交 |
| AuditEvent | 追加的操作/操作者/目标/版本/时间/command_id；与被审计变更同事务，写审计失败则整个命令回滚；不记录 Token、邀请原文或秘密 |

数据库承担 FK、唯一性、枚举、有效期与版本检查。SQL 集中 MyBatis XML；应用服务明确事务边界，关键写入校验影响行数。读取有效成员同时核验主体、企业、成员状态及有效期，权威为当前数据库，没有跨请求 ALLOW 缓存。重新加入只能从 LEFT 转为 ACTIVE，generation+1/version+1；停用不等于离职，不用重新加入绕过停用。

## P1-01 受控初始化

入口为离线运维 CLI，从显式指定的配置文件及命令文件读取；凭据文件权限 0600。运行者须具有目标治理库写权限，不能匿名经 HTTP 创建主体。命令显式提供 operator_ref、command_id、UUID、issuer/sub、旧隔离引用及有效期。首片只允许 EMPLOYEE，创建 ACTIVE HUMAN/Tenant/Membership；已有事实必须精确一致，不恢复停用、不延长成员期限。重复返回同一成员 ID。

CLI 只输出非秘密的成员引用/类型/状态/代际；以参数选择 bootstrap 或读取本人绑定成员。失败只报告稳定错误码，内部数据库信息不回显。这个入口不提供应用准入、平台管理员或业务权限。P1-02/03 的认证 HTTP 入口由后续片实现，不能将 CLI 成功写成 Token 验证已完成。

## P1-02/03 认证及接口

新增路径为 `/api/governance/v1/me/memberships`（admin 的本人入口）和 `/internal/governance/v1/context/resolve`（server 的内部双身份入口）。均仅在显式启用治理配置时装配；默认关闭不改变旧九项协议。协议 DTO 不引用 OA 私有实体。未知 JSON 身份/app 字段拒绝，不能影响上下文。

管理入口使用配置绑定的 issuer、JWKS、非空 audience，算法仅 RS256，校验签名、nbf/exp、issuer、audience 和 Access Token 用途；不使用 Casdoor isAdmin 或旧接口可选 audience 作为治理管理权限。JWT 声明不足以证明用途时须用发行方 Access Token introspection 校验 active、client、iss、sub、aud 与 JWT 一致，外部故障默认拒绝且有超时/并发上限；没有正向认证缓存。Casdoor 文档和当前源码分别描述此端点与按类型检索，实际安装版必须实测，不能根据 master 宣称安装版支持。[Casdoor OAuth](https://casdoor.org/docs/how-to-connect/oauth/), [Casdoor Token 实现](https://github.com/casdoor/casdoor/blob/master/object/token.go)

内部入口同时要求服务 Bearer 和独立 `X-User-Access-Token`。服务凭据由受控配置绑定 caller_service_id、application_id、environment、允许操作及用户 issuer/audience/client，不从 body 选择。请求体仅 tenant_id 和可选 expected_membership_generation；用户身份由验证后的 issuer/sub 唯一映射。HUMAN 有效成员可生成只读 AccessContext；SERVICE 的业务准入在 P2 明确前拒绝。P1 上下文解析成功不等于应用准入或业务授权。

返回字段：principal_id、membership_id、membership_generation、membership_version、principal_version、tenant_id、application_id、environment、caller_service_id、actor_type=HUMAN。trace_id 由服务器产生，不将调用方值当作身份。本人列表只含该主体当前有效成员引用，不泄露企业员工目录。上下文选择 tenant 必须与本人当前成员交叉验证。

错误：401 INVALID_CREDENTIAL；403 IDENTITY_NOT_BOUND/MEMBERSHIP_UNAVAILABLE/GENERATION_MISMATCH；409 BINDING_CONFLICT/COMMAND_CONFLICT/VERSION_CONFLICT；400 INVALID_ARGUMENT；503 DEPENDENCY_UNAVAILABLE。响应仅 `{code,trace_id}`；不回显 token/SQL/内部堆栈。

客户端沿用现有 SPA 授权码+S256 PKCE，state/nonce/精确 redirect 边界在真实认证夹具与所属前端片验证。P1 不增加 Token 签发服务或默认 BFF。签名 JWT 不代表当前成员有效；停用后旧 Token 仍须被当前治理状态拒绝。

## 后续片与未决业务边界

P1-04 唯一正式目录源仍待 Q-DIR；不能默认为 OA 是全部员工权威。事件必须有 source/event_id/aggregate/version/hash，重复与冲突分开，旧事件不能复活成员，未证明完整的快照不删人。

P1-05 邀请绑定精确 issuer/sub；目标成员 PARTNER/GUEST、同租户有效 EMPLOYEE sponsor、有效期。高熵单次 token 只存 SHA-256，接受 CAS+唯一约束，同目标重复返回同一成员；无内部默认角色/应用。具体邀请人管理权和页面在该片先冻结，不能套用 isAdmin。

P1-06 生命周期命令必须来自受控操作权或选定来源，expected_version、command_id、原因及审计；成员停用与主体全局停用分别处理。回退保留停用事实。正式目录接管、存量导入和生产部署仍不在自动执行权限内。
