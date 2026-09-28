# P1-05 外部邀请与重新加入契约

P1-03 已完成，P1-06 已提供受控停用；按原 P1-05 节点实施。此片不依赖 OA 新员工首次建成员策略，邀请本身就是受控的显式身份绑定依据。外部业务资源/角色试点仍待 Q-EXT，不影响身份层 PARTNER/GUEST 邀请验证。

## 权限入口与边界

创建和撤销先使用离线 InvitationCli，沿用 0600 配置文件和专用治理库操作权；操作者、企业、负责人三项来自受控配置，不允许命令文件覆盖。配置键为 `invitation.operator-ref`、`invitation.tenant-id`、`invitation.sponsor-membership-id`，外加现有 jdbc 属性。它不是面向员工开放的数据库入口，也不构造临时 isAdmin 权限。后续管理页面与委派权限仍走 P4/P5。

接受入口为 admin 的 `POST /api/governance/v1/invitations/accept`，仅在治理和邀请两个开关都显式打开时装配。独立窄路径安全链验证配置于 `invitation.user.*` 的 Casdoor Access Token；发行方、受众、算法、实时 introspection 和版本检查沿用 P1-02。这个入口允许尚未有 LoginIdentity 的受邀人通过 Token 认证，其他治理接口继续要求已有显式绑定。

接受请求 JSON 只有 `invitation_id`、`token`，最多 4096 字节，拒绝重复/未知字段、尾随 JSON 和重复认证 Header。主体来自已验证的 issuer/sub，并与邀请目标精确匹配；不使用邮箱、姓名或请求 principal。令牌、配置和原始异常不得进入响应或日志。错误沿用 P1 既有稳定错误码。

## 创建、令牌与撤销

邀请只允许 PARTNER/GUEST；同租户负责人必须是当前有效 EMPLOYEE，且主体和企业有效。每次接受时再次验证负责人，不能以创建时曾有效替代当前事实。

创建输入：command_id、invitation_id、target_issuer、target_subject、member_kind、expires_at、membership_valid_to、reason；时间为 UTC、微秒精度，必须 `数据库当前时间 < expires_at < membership_valid_to`，两种期限都显式指定。不提供无限期外部成员或隐含业务角色。

令牌由安全随机源生成至少 32 字节，以 base64url 无填充传递；数据库只保存 SHA-256。CLI 将原文写到调用者显式指定、首次排他创建的 0600 文件，不打印原文。重试复用同一私密文件和命令 ID；文件缺失时不能恢复摘要对应的原文，也不能用新令牌悄悄覆盖原邀请。

创建命令将操作者、固定范围/负责人、全部业务输入和令牌摘要纳入规范摘要，沿用命令唯一键/事务去重。同键异内容返回 COMMAND_CONFLICT。邀请 ID、令牌摘要由数据库唯一约束保护。

撤销仅允许同一受控操作者、企业、负责人范围中的 PENDING 邀请，带 command_id、expected_version、reason。`PENDING → REVOKED`，版本 +1；已接受的邀请不能通过撤销去改变成员，应走独立停用命令。过期由接受时数据库时间判定，不引入过期调度器。过期/撤销链接不能产生新成员。

## 接受事务与代际

接受之前完成 Token 网络验证；事务内不调用 IdP。先锁定邀请，恒定长度比较令牌摘要及精确身份，再检查状态/期限/负责人。并发同邀请只能产生一个结果。

- 未绑定 issuer/sub：显式邀请允许创建 ACTIVE HUMAN Principal 及精确 LoginIdentity，不创建 Casdoor 账号。唯一键冲突不可覆盖其他绑定，事务回滚后返回冲突。
- 已绑定身份：必须为当前 ACTIVE HUMAN，复用原 Principal；SERVICE 或全局停用主体拒绝。
- 同主体同企业没有成员：创建指定 PARTNER/GUEST，generation/version 从 1 开始，valid_from 为数据库当前时间，valid_to 为邀请指定值。
- 已有 ACTIVE 成员：不同邀请不得延长、改类型或替换负责人，返回冲突；相同已接受邀请重放返回同一仍有效成员引用。
- 已有 SUSPENDED 成员：拒绝重新邀请绕过停用。
- 已有 LEFT 外部成员：仅同类型的受控新邀请允许重新加入，保留成员 ID，generation +1、version +1，按新邀请设置期限/负责人；不能将历史 EMPLOYEE 隐式变成外部成员。

邀请持久化 ACCEPTED、结果成员 ID 和接受时 generation。旧邀请重放发现成员代际已变化时返回 GENERATION_MISMATCH，不指向新代成员冒充原邀请结果；当前停用/到期也不能返回有效成员确认。

接受响应仅含 invitation_id、membership_id、membership_generation、membership_status、服务端 trace_id。它不是应用准入/业务 ALLOW，不自动创建 TenantApplication、角色、Grant、组关系或 SpiceDB 元组。

## 持久化、审计和兼容

新增增量迁移，不修改已执行 V1–V3。Invitation 使用企业及同企业负责人/结果成员外键、唯一 token_hash、封闭状态 PENDING/ACCEPTED/REVOKED、版本/期限/接受结果完整性约束。新状态使用显式稳定 code，映射沿用治理 MyBatis TypeHandler。

创建、接受、撤销、首次身份/成员建立或重新加入、命令回执、追加审计必须同一事务。通用 audit_event 保存索引事实，邀请审计明细扩展以 audit_event_id 外键保存原因、前后邀请状态/版本与邀请 ID，不扩大既有成员生命周期状态枚举。审计失败全部回滚。纯查询或请求重放不重复写审计。

默认关闭；旧接口和旧 schema 读写保持兼容。退出新邀请功能不删除已接受成员、代际、停用或审计事实。暂不自动清理审计/邀请，正式保留期限在后续治理阶段确认，不编造生产保留政策。

## 必需验收

1. 真实 PostgreSQL：单次接受、同邀请并发、唯一身份/成员约束、同命令幂等/异内容冲突、审计失败回滚。
2. 错误主体/用途/受众、随机令牌、过期、撤销、负责人停用/跨企业、已有成员不扩权、SUSPENDED 不复活均拒绝。
3. LEFT 外部成员受控重新加入增加代际，旧邀请和旧代上下文拒绝；不得修改内部成员类型。
4. 真实独立 Casdoor 邀请夹具完成外部 Token → 接受 → 成员查询/上下文的验证，使用独立组织/客户端，保留 P1-02 未绑定外部账号负例。
5. 证明无角色、应用和图关系副作用；本片无 UI 改动，浏览器视觉 N/A。完整登录升级仍受已记录 redirect_uri Gate 限制，不将本片通过视为共享 IdP 可切换。
