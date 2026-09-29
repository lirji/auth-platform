# P5 门户增量契约

原P1—P4契约继续有效；以下为本轮P5授权范围的兼容新增，不改旧请求/响应字段。治理Bearer验证、snake_case、code/trace_id错误继续沿用。只读不授予任何权限。

## P5-01

GET `/api/governance/v1/me/organizations` 返回`[{membership_id,tenant_id,tenant_code,member_kind,generation}]`。数据来自当前有效本人Membership和租户code，不提供全企业目录。无有效绑定按原401/403边界，不伪造空结果。

GET `/api/governance/v1/me/applications?tenant_id=<UUID>&after=<application/environment>` 返回`{items:[{application_id,environment,management,menus:[{code,parent,href}],capability_hints:[]}],next_cursor}`。每页最多20个关联候选，在完整tenant范围及当前成员代际内查本人Grant/目录组关联或管理委派；业务可见入口仍通过既有当前SQL/图判权产生。本人历史Grant保留应用上下文以查申请/撤权结果；无当前权限时menus为空，不能跳转。无本人成员/委派/Grant关联的内部应用不公开。游标只定位、不授权，回放其他租户游标也只能访问当前可信租户。

management只表示本分区存在当前委派，业务入口不因此放行。应用URL来自已登记安全origin和清单route，不接受浏览器任意回跳，不拼Token。每页处理预算10秒，失败整页报503，不保留部分ALLOW。查询结束重验成员版本/代际。最大候选页20，组织数沿用P1上限。

两接口复用presentation.enabled/access.enabled/governance.enabled开关，默认关闭。前端上下文存在于URL/标签页会话；更换组织和主体立即丢弃旧应用、游标、详情和草稿。
