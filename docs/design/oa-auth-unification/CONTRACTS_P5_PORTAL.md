# P5 门户增量契约

原P1—P4契约继续有效；以下为本轮P5授权范围的兼容新增，不改旧请求/响应字段。治理Bearer验证、snake_case、code/trace_id错误继续沿用。只读不授予任何权限。

## P5-01

GET `/api/governance/v1/me/organizations` 返回`[{membership_id,tenant_id,tenant_code,member_kind,generation}]`。数据来自当前有效本人Membership和租户code，不提供全企业目录。无有效绑定按原401/403边界，不伪造空结果。

GET `/api/governance/v1/me/applications?tenant_id=<UUID>&after=<application/environment>` 返回`{items:[{application_id,environment,management,entry_state,menus:[{code,parent,href}],capability_hints:[]}],next_cursor}`。每页最多20个关联候选，在完整tenant范围及当前成员代际内查本人Grant/目录组关联或管理委派；业务可见入口仍通过既有当前SQL/图判权产生。本人历史Grant保留应用上下文以查申请/撤权结果；无当前权限时menus为空，不能跳转。无本人成员/委派/Grant关联的内部应用不公开。游标只定位、不授权，回放其他租户游标也只能访问当前可信租户。

management只表示本分区存在当前委派，业务入口不因此放行。应用URL来自已登记安全origin和清单route，不接受浏览器任意回跳，不拼Token。每页处理预算10秒，超预算整页报503。P5-02补充：单应用图依赖失败或AUTHZ_STATE_NOT_READY返回该已关联项entry_state=UNAVAILABLE并清空menus/capability_hints，仅保留申请/管理上下文，不返回业务ALLOW；正常分别为AVAILABLE/NO_ACCESS。成员或分区校验失败仍整体拒绝。查询结束重验成员版本/代际。最大候选页20，组织数沿用P1上限。

两接口复用presentation.enabled/access.enabled/governance.enabled开关，默认关闭。前端上下文存在于URL/标签页会话；更换组织和主体立即丢弃旧应用、游标、详情和草稿。

## P5-02 管理展示补齐

新增GET `/api/governance/v1/access/management`（完整tenant/application/environment参数），返回`{membership_id,generation,max_duration_seconds,capabilities:[{code,resource_type,risk_level,disabled}],catalog_owner,manifest_version,policy_state,directory_state,desired_epoch,applied_epoch}`。每次使用与已有access/state相同的当前成员和分区委派校验；capabilities仅为当前可授予上限与已发布清单交集，不赋予任何权限。catalog_owner来自当前principal匹配固定应用Owner；没有Owner的管理员不能发布清单。

GET `/access/members?...&after=<UUID>` 返回`{items:[{membership_id,generation,member_kind,valid_to}],next_cursor}`，限当前可信租户、有效人类成员，每页100；不返回登录凭证或其他企业目录。成员只作为授权目标选择，提交仍由原grant用例检查当前代际、自授予、期限、能力上限。

GET `/access/role-impact?...&role_id=<UUID>` 返回`{role_id,previous_role_id,added,removed,referencing_grant_count}`。比较同编码的前一已发布版本；引用数量是所选固定版本的Grant总数，不声称创建新版本会迁移旧Grant。扩权需要新授予/重新申请，旧Grant继续引用旧快照。跨分区ID拒绝403。

写操作仍使用原`/access/roles`、`/access/scoped-grants`与`/catalog/preview|publish`。UI在一次提交时固定command_id与原payload；未知结果提供原样重试，不默默换键重提。精确范围用结构化资源类型/范围类型/值列表编辑；不把SCOPED显示成TENANT_ALL，不使用JSON输入框作为普通授予表单。投影UPDATING/BLOCKED及202不能显示实际生效；分区进度单独展示，列表ACTIVE仅标识原图确认事实。

P5-02验收发现投影故障会阻断管理/申请进度入口，因此上述entry_state兼容字段明确区分应用不可达与无权限；这是展示契约修正，原`/me/access`与业务判权的503/拒绝语义不变。

## P5-03 邀请与申请

原 `/requests`、`/{id}/execution`、`/{id}/cancel`、`/policies`、`/notifications` 继续作为权威；PolicyView兼容新增role_code/role_version/capabilities，均来自同分区固定RoleVersion。新增GET `/access/request-policies` 返回管理者当前分区的策略页（包含固定审批成员/代际、enabled），只供配置核对，不让普通申请人读取审批人员目录。注册继续使用原POST `/requests/policies`；发布新策略不修改已提交快照。

邀请新增 `/api/governance/v1/portal-invitations`：GET `/authority`返回受控issuer/max_invitation_seconds/max_membership_seconds；GET根路径按当前操作者分页；POST根路径创建，POST `/{id}/revoke`撤销待接受邀请。所有请求含当前完整partition，查询游标after最多100项。创建字段command_id/invitation_id/target_subject/member_kind(PARTNER或GUEST)/token/expires_at/membership_valid_to/reason；撤销字段command_id/expected_version/reason。返回id/target_issuer/target_subject/member_kind/expires_at/membership_valid_to/state/version，不返回证明或其摘要。

邀请管理权限默认关闭。私密治理配置`portal.invitation.count=0..100`及1起下标条目的tenant-id/application-id/environment/membership-id/generation/max-invitation-seconds/max-membership-seconds明确委派；上限分别7天/365天。当前成员必须是同租户有效EMPLOYEE，代际匹配配置且仍具当前应用管理委派。issuer固定为invitation.user.issuer；操作者/负责人由服务端派生，浏览器不可指定。关闭配置或撤销管理委派立即拒绝。邀请只建立成员，不自动授予应用能力。

浏览器为每个创建意图生成256位随机证明并冻结命令；原样重试，证明只在本次页面内显示供受控交付，不放URL、持久化浏览器缓存或自动发送。邀请接受使用原POST `/invitations/accept`，独立受保护路由`/invitations/accept`不依赖已有组织；要求用户输入邀请ID和证明，登录身份必须与邀请精确匹配。接受成功只说明已加入组织。

我的申请按URL request/after保留详情与分页，仅本人当前代际；申请详情分列原固定能力/范围/期限、审批事实和实际执行状态。未知结果重试复用原命令，取消202显示取消/回收受理，明确其他合法来源不会一并撤销。通知只显示安全模板与本人详情，不复制员工待办。OA已有待办页面增加中央权限申请依据抽屉，GET `/api/v1/flow/central-access/tasks/{taskId}`原接口逐条验证当前办理人；读取失败不在该抽屉提供审批按钮。

## P5-04 来源解释、撤权与审计

GET `/me/permissions`（完整partition，after=Grant UUID）分页返回本人当前代际的直接/OA或当前目录组关联Grant，每页最多100。字段grant_id/member_id/generation/group_id/role_id/role_code/role_version/capabilities/scope/scope_rule/source_type/source_id/valid_from/valid_to/grant_state/effective_state/grant_version/operation_id/policy_state/directory_state。状态来自当前SQL事实、双栅栏与当前Grant版本回执；ACTIVE表示投影已确认且SQL条件仍有效，不替代业务每次实时图检查。能力/范围/来源始终按同一Grant显示，不能拼接成额外权限。TENANT_ALL时scope_rule为空并显式返回scope，不臆造资源类型；组授权返回GROUP_CHECK_REQUIRED，组资格与时段继续由真实业务请求确认。

GET `/access/explanations?grant_id=...`、GET `/access/audit?after=...`另需显式诊断授权。私密配置`portal.diagnostic.count=0..100`及条目的tenant-id/application-id/environment/membership-id/generation；默认0。每次同时核对当前成员代际及应用管理委派。普通应用管理员不自动取得他人诊断/审计。说明读取只返回请求分区内Grant；跨分区ID拒绝并追加诊断审计。

审计页合并当前分区的角色/Grant/申请/策略相关既有审计与门户诊断访问记录，不展示其他应用或全企业日志；字段id/operator_ref/operation/target_id/target_version/occurred_at/outcome。诊断读取成功/拒绝落独立追加表V17（不记录Token、证明或任意业务正文），拒绝证据在独立短事务提交，不因随后抛出拒绝异常回滚。查审计本身也校验同一诊断范围；不提供修改/删除审计接口。

管理撤权沿用POST `/access/strict-revoke`与GET `/access/revocation-receipt`，202显示回收处理中；仅COMPLETED及实际operation_id显示该来源回收完成，不宣称所有来源均撤销。本人解释列表继续列出其他合法Grant。申请来源本人回收仍走原cancel接口；管理者回收单条Grant走原委派检查和版本命令。

## P5-05 商城内部试点

中央入口在既有commerce前端新增`/operations/products`；复用React/AntD主题，独立OIDC business客户端使用授权码+PKCE，sessionStorage限本标签页，不进入旧凭据登录壳。固定`/iam/callback`仅恢复允许的内部/协作路由及URL上下文；无Token跳转或浏览器client_secret。OIDC复用auth已使用的oidc-client-ts 3.5.0（Apache-2.0；官方UserManager/PKCE文档https://authts.github.io/oidc-client-ts/），不新增认证服务。

业务Bearer/X-Tenant-Id只作为待验证输入；本地身份桥仍要求匹配当前principal/member/generation，旧本地ADMIN不可回退。`GET /v1/operations/scoped/product`及`/resources/{id}`复用P3真实商品Owner范围查询。新增GET `/resources/{id}/actions`返回`{update:boolean}`，当前read允许后检查同资源`commerce.product.update`，无权false、依赖错误503不伪装无权。POST `/resources/{id}`为受控商品元资料修订，body仅expectedVersion/title/category/brand，Idempotency-Key必填。中央用例重新读取真实资源事实并检查update，限定同一plan/context；Owner事务以tenant/store/id/version及完整范围谓词条件更新、检查影响行数、命令与审计原子落库。不得由浏览器指定Owner事实/本地actor/范围。无效输入400，越权403，版本/资源/范围变化409或403，授权依赖失败503。

网络判权在本地事务前完成；SQL仅接受最多5秒且不超过plan validUntil的在途决策，超时拒绝。撤权完成后的新请求必须拒绝；不承诺跨数据库原子取消已进入提交的请求，代码回滚不撤销已经修订的商品。原legacy商品接口及未试点功能保持原规则，不给中央JWT建立旧管理员凭据。

## P5-06 指定门店协作与限时导出

同一商城使用隔离路由`/collaboration/products`，从真实商品Owner范围查询获取数据，不生成供应商订单。邀请仍经P1/P5入口接受，独立read Grant绑定指定门店；申请固定product.export策略，经OA审批/回执后才能导出。外部身份不自动取得OA员工待办或商品update。

既有product导出路径`/v1/operations/scoped/product/exports`（提交/开始/推进/状态/下载）全部改为显式`commerce.product.export`判权；不会从product.read拼范围。GET `/export-access`只返回`{export:boolean}`体验提示，依赖故障仍503。过滤器仍需当前合法product.read入口，随后导出用例逐次检查export和当前上下文。每批最多50、单任务最多1000、原幂等/配额/持久检查点/下载资源版本复核不变。只有read的旧调用者会失去导出，属安全收紧；上线先登记固定export能力、审批策略、客户端/服务范围，再切业务后端；不能回滚为read代替export。已有product旧任务fingerprint含read能力，与新export不匹配，拒绝续用，需当前授权下新建。store范围试点保留P3契约，不扩大本轮业务范围。

页面保持组织/检索/任务在URL，详情刷新重新检查；通过固定配置的统一工作台Origin跳到本人申请页，不接受浏览器任意跳转目标。无导出权限明确提示申请；提交202只显示排队，开始和每次推进显式触发，完成后下载仍重新判权。到期/撤销使旧任务与旧下载链接拒绝；独立read Grant继续提供合法商品查询。取消申请只撤该OA来源。商品已下载到用户设备的内容不宣称能远程回收。
