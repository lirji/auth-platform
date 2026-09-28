# P2 角色/直接授权契约（v1）

本契约细化P2-02～04，沿用已批准直接成员、TENANT_ALL和单执行者边界；P3的组/细粒度Scope/多实例栅栏未启用。

- `Partition(tenant_id,application_id,environment)` 的应用开通、角色、授权和管理委派分别限定。受控初始化显式登记管理成员代际、允许授予能力集合、最长授权秒数；业务权限不产生管理权。该初始化不暴露HTTP。
- `RoleVersion` 按partition/role_code/version唯一；权限集合固定且引用当前清单合法能力。历史版本由DB触发器禁止更新/删除；新增版本不改旧授权。
- `AccessGrant` 固定成员及generation、role_version_id、scope=`TENANT_ALL`、source_type=`DIRECT`和source_id、UTC有效期、版本和状态。其他范围及主体类型拒绝。授权管理者不能给本人授权；最多当前有效/待处理100笔/partition/member，检查读101防静默截断。管理最长时间由受控策略必填，未配置拒绝。
- 所有命令的幂等作用域：当前管理成员/tenant/操作/command_id；同键改体409。拒绝当前无效成员、跨租户/应用/环境、超委派能力或期限。查询有界100条，以稳定id游标分页。
- 管理事务写授权、审计、投影意图；创建状态PENDING，图确认水位持久化后ACTIVE。撤销立即REVOKED并递增版本，图删除可稍后重试；旧创建意图不能复活撤销记录。
- 每partition存在未完成投影时新检查不ALLOW。每次检查读取当前有效主体/成员、generation、准入、有效期、固定角色能力和授权状态；逐Grant检查图eligible，再重新读取SQL资格。任何缺失/故障不ALLOW。不启跨请求缓存。
- P2执行器为显式单进程有界drain，数据库advisory session lock防误并行；图HTTP超时有界。每次最多50、最多5次失败，保留意图供受控恢复。没有远端CAS，不声明多实例/旧进程远端写栅栏通过。
- 图使用独立专用SpiceDB实例/数据库与独立凭据，只运行 `gov_membership` / `gov_grant` schema；旧API/组同步无该目标凭据。成员对象包含generation；业务项目只持有中央检查服务凭据。

## 管理HTTP（P2-03）

沿用 `/api/governance/v1` 管理Token独立受众，strict snake_case JSON和稳定错误；`ACCESS_DENIED`403、冲突409、故障503。只支持后端验证的当前用户。

- POST `/catalog/preview`、`/catalog/publish`：清单body；publish命令ID在 `X-Command-Id`。
- POST `/access/roles`：tenant_id/application_id/environment/command_id/role_code/role_version/capabilities。
- POST `/access/grants`：partition + command_id/member_id/member_generation/role_id/scope/source_id/valid_from/valid_to。
- POST `/access/revoke`：partition + command_id/grant_id/expected_version。
- GET `/access/state`：partition + after_role/after_grant（可选），返回角色与授权分页及管理能力，不泄露其他partition。
- 创建Grant返回202，状态以响应为准；撤销返回当前记录，REVOKED不冒充图清理完成。

公开DTO由protocol声明；外部时间字符串为ISO-8601 UTC，领域/SQL使用Instant。前端不传principal、不传任意SQL或图关系。

## 有界失败恢复

POST `/api/governance/v1/access/retry-projection` 与撤销请求字段相同，但只重置当前Grant版本已失败5次、仍未完成的投影意图。当前分区管理委派、能力上限、expected_version、command_id幂等与审计全部保留；不改授权状态、不写图确认。非耗尽/已完成/旧版本409。修复依赖后调用，再运行单执行者ProjectionCli；CLI仍有pending时非零退出。已被撤销新版本取代的旧耗尽意图由drain安全跳过，不导致分区永久阻塞。
