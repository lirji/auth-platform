# MG09：受限发布契约 v1

状态：FROZEN_DESIGN（2026-10-03），用于 MG10／MG11；遵循阶段 A CONTRACTS 与 MG05 原命令／固定预览语义。运行 gate HOLD，启用未批准：安装版机器认证及真 PG 验收待 MG10，通过前默认关闭。

## 固定目标与身份

- 一个权限数据库有唯一受控发布 instance UUID 和 environment；仅受控初始化写入，已有记录不得被重绑定。启动只读核对配置与主库，一致后才装配可选机器边界。
- authz.governance.publisher.enabled 缺省 false。依赖既有治理 Runtime；前缀 /api/catalog-publisher/v1/ 与 HUMAN /api/governance/v1/ 分开，不回退旧 JWT／内部业务凭据／个人上下文。
- 私密固定配置最多16个发布 slot，每个含 publisher_id（UUID）、SERVICE principal UUID、固定 application、instance UUID、environment、issuer、JWKS、Casdoor client ID／audience／subject 和 introspection／版本探针凭据。客户端须非共享应用，只允许 client_credentials，JWT／RS256，专用 audience，无登录重定向／密码／注册能力。
- Token 必须签名、发行方、原 iat／exp、access-token 用途、type=application、azp=client ID、sub=受控应用 ID、唯一 audience 精确匹配；0<exp−iat≤300秒，当前未到期，iat不得晚于当前时间。每请求实时版本／introspection；不保存跨请求正向身份状态。
- VerifiedMachine 与 VerifiedLogin 分开，仅已认证事实进入 SERVICE 用例。可信IdP可以复用，测试／生产client ID、audience和secret必须不同；生产配置使用HTTPS，回环HTTP只供隔离开发验证。Token、secret 不进 DTO、日志、审计或前端；未知认证依赖返回503、明确凭据错误401。
- 每次精确查主库实例、当前 ACTIVE SERVICE 与登录映射、当前 ACTIVE HUMAN Owner、该 Owner 给予固定 slot 的 ACTIVE／未到期委派。目录没有租户全局读取人员的机器例外。

## Owner 委派（HUMAN 专用）

所有路由仍在既有 Owner Bearer 链，服务端从固定 slot 取得身份及目标，不接受调用方自选 issuer／subject／client secret／operator。

| 操作 | 路由／请求（snake_case，未知／重复字段拒绝） | 语义 |
|---|---|---|
| 建立 | POST /api/governance/v1/catalog/publisher-delegations；application_id, publisher_id, command_id, valid_until, reason | 当前Owner；原 command UUID 唯一，期限在当前之后且不超过30天，固定 slot 必须属于当前应用／实例／环境。仅登记 SERVICE 发布身份及委派，不创建人员／角色／Grant。冲突身份不重绑定、不恢复停用主体。 |
| 禁用 | POST /api/governance/v1/catalog/publisher-delegations/disable；application_id, delegation_id, expected_version, command_id, reason | 版本CAS，ACTIVE→DISABLED单向；同应用锁与机器发布串行。已有禁用原命令可重试，不自动恢复或续期，创建新委派需新客户端／slot。 |
| 查询 | GET /api/governance/v1/catalog/publisher-delegations?application_id=... | 当前Owner，最多100条；不足以完整返回时明确拒绝，不以一页假装全量。仅元数据，没有Token／secret。 |

委派回执包含 delegation_id、publisher_id、application_id、target_instance_id、environment、service_principal、owner_principal、status、version、valid_until、command_id、reason 和审计时间。建立／禁用事件不可变，审计失败整个事务回滚。同键异体409，当前Owner或主体失效403。

## 机器接口

受控 slot 由已验证唯一客户端确定，body 只能确认固定目标，不能选择新 slot 或数据库。所有请求严格重复字段／未知字段／标量类型／尾随JSON，预览总字节≤141312，发布与查询≤4096；沿用100菜单／200能力等现有上限。

| 操作 | 路由 | 字段及结果 |
|---|---|---|
| 预览 | POST /api/catalog-publisher/v1/preview | target_instance_id, environment, manifest, source, reason；source必须含固定40/64位commit与64位artifact_hash。decision／impact／operator禁止。返回 eligibility、准确 preview、候选摘要／双摘要／基础版本、target／publisher信息和可选 ticket。 |
| 发布 | POST /api/catalog-publisher/v1/publish | target_instance_id, environment, command_id, preview_id；只有固定服务器候选，不能提交替换manifest。成功返回固定Release与SERVICE actor／委派／目标回执。 |
| 原命令查询 | POST /api/catalog-publisher/v1/receipt | target_instance_id, environment, command_id；仅当前有效委派自己的已提交发布，未发现404。不能查别的机器、HUMAN发布或整个Owner历史。 |

### 自动发布资格

- AUTOMATION_ALLOWED：资源类型及全部能力定义完全相同，已有每个可执行或带any_of菜单的稳定code、route、any_of完全相同；允许名称、排序、父级调整及无route／无any_of的纯分组增删。版本必须递增，来源必填。
- REQUIRES_OWNER_REVIEW：首次目录或上述不变量不成立；返回差异及原因，ticket为空、不改变目录。新增尚未授权能力也不自动发布，不能自行提交SEPARATE_AUTHORIZATION_REVIEW绕过。
- ILLEGAL候选仍按现有契约拒绝，不把非法变更报告成允许。风险报告不是全人员影响分析；机器无诊断资格，影响状态不虚报零或COMPLETE。
- 只有GUARDED应用可生成机器票据。票据10分钟、绑定当前HUMAN Owner和机器委派，另存不可变关系；机器不得消费HUMAN票据／别人的机器票据。

### 发布原子性与幂等

固定应用锁→身份／Owner／委派当前锁→MG05票据／版本／双摘要→自动资格重新核验→同事务写原快照、显示、指针、发布回执、SERVICE actor和审计。提交前主库 clock_timestamp 重验委派与Token期限。PG状态是发布资格权威；不承诺与发行方 HTTP 状态跨库原子。

原成功命令在通过当前身份／委派后，先于票据到期和更高目录版本返回同一回执；相同command换preview、换委派或换候选一律409。请求超时结果未知时查询／重试原命令，不生成新键。已禁用委派不能利用成功重试取得认证豁免。

原 published_by／operator_ref 写实际 SERVICE 主体；独立actor记录SERVICE、HUMAN Owner、委派、instance／environment、原command与时点。旧HUMAN回执字段不变，旧历史不回填假机器元数据。机器不修改角色／Grant／能力状态／人员／execution reference；调用其旧管理接口由HUMAN边界拒绝。

## 错误、兼容与必要验收

400 INVALID_ARGUMENT：结构／类型／上限／UUID／来源错误；401 INVALID_CREDENTIAL：缺失／错误／到期Token；403 ACCESS_DENIED／MEMBERSHIP_UNAVAILABLE：错误目标、应用、SERVICE／Owner／委派无效或错误票据来源；409 VERSION_CONFLICT／COMMAND_CONFLICT／BINDING_CONFLICT：旧依据、异体或绑定冲突；404 NOT_FOUND：自己的未提交命令；503 DEPENDENCY_UNAVAILABLE：IdP／PG／审计依赖不确定。统一trace_id，不回显输入或底层堆栈。

MG10：固定版本实际client_credentials／introspection、过期和错误用途／受众、禁用与轮换、第二隔离数据库错误目标、HUMAN与SERVICE交叉拒绝、语义变更报告与纯展示发布、固定票据替换／并发旧基础／原回执、真实PG撤销竞争和审计回滚、角色Grant等前后不变。测试／生产实例都用隔离测试目标模拟，绝不操作真实生产。

MG11：默认dry-run，候选来自固定提交和制品摘要，URL／凭据只从受控目标配置取；持久化准确差异／资格报告。启用隔离目标发布需本契约的委派与资格，源文件在预览后替换被摘要栅栏阻止。原命令未知结果恢复、投影状态与UNKNOWN运行状态分别显示，不把正常Git推送当成生产部署。

源码commit／artifact_hash属于发布声明，服务端固定候选保证不被替换，不把该声明当作独立构建签名或实际部署证明。机器运行状态仍沿用MG06 UNKNOWN，直至取得可信实际制品核验。
