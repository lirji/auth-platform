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
