# P5-03 TEST_RESULT

状态：PASS。新增本人申请/详情/取消/通知、受保护策略登记与固定角色显示、显式配置邀请管理/接受、OA实际任务审批依据。契约及配置见CONTRACTS_P5_PORTAL和RUNTIME_SPEC。

## 已通过验证

- 12项PortalPostgresIT及该reactor单测PASS，真实PG；覆盖邀请默认拒绝、分区/操作者/代际隔离、期限与类型限制、同命令重放、已接受邀请不可撤销、固定角色策略及普通成员管理拒绝。邀请写入短事务先锁分区，再锁员工负责人/主体/租户，复核代际；与领域邀请摘要/审计同事务，没有远程调用。
- auth-console构建PASS；OA console构建及CentralAccessReview 3项组件测试PASS（未读取依据禁止办理、固定范围显示、刷新失败清除旧办理入口）。组件测试使用契约fixture，只验证UI行为，不替代真实链路。
- auth真实Casdoor/PG/图浏览器：shell-0f49e50f63，P5-01/02回归11项及P5-03新增7项PASS。管理者登记STORE-A限时导出策略；PARTNER提交/URL刷新/查询/取消；跨租户详情和管理员代读本人申请403；邀请创建、指定身份接受、无隐含授权、另一个待接受邀请撤销；外部管理页拒绝。
- OA首次完整浏览器+P4整链e2e-468fd034c0 PASS：真实Flowable任务固定快照、按钮办理、签名回调丢ACK重试、批准待生效、真实图故障及恢复、实际ACTIVE回执、短期到期拒绝、OA停止仍日常判权、来源单独撤销且合法查询保留。外部直调审批依据403。最终e2e-44c5f7f6f2完整62项检查PASS，OA浏览器3项PASS；外部页面完成加载后显示明确权限拒绝，截图已实际查看，审批依据和待办均未泄露。
- Code Hygiene两仓IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅FORMAT_TOOL_NOT_AVAILABLE，沿用既有样式；独立测试脚本使用显式HTTP状态常量。

## 视觉、故障与范围

1440策略详情、申请表单/详情、邀请表单/接受完成及390申请详情已实际查看；长ID和能力自动折行，抽屉可滚动，按钮清晰。修正策略变更后期限校验信息未刷新的问题；截图等待抽屉完全进入视口，不保留动画中间态。含邀请证明的成功页面刻意不截图、不录trace，证明只存在本次页面和隔离测试内存。

OA独立控制台15276首次POST被CORS拒绝，已仅为隔离实例配置精确Origin；未放宽共享或生产配置。不可变审批依据不因窗口焦点变化瞬时禁用按钮，权限版本变化和手动刷新仍重新核验；实际办理后端继续逐次检查指派。

本片没有完成真实商城业务试点或完整交互OIDC登录，它们仍属P5-05..07。邮件/短信未配置，站内通知沿用P4状态权威。OA原有CODEX_PROGRESS、identity-authz-governance文档、tmp/.local均保留，不入本片提交。

SKILL_HANDOFF: slice=P5-03, status=COMPLETED, gate=PASS_WITH_ASSUMPTIONS, next=P5-04。完整P5仍进行中。
