# P4 权限申请契约

本契约细化用户已批准的v0.2 P4，基于P3既有关系库、固定ScopeRule和可靠投影。首版单项、自助申请；不接管OA旧IAM申请，不把普通限时授权映射成JIT。

## P4-01 固定内容

管理员通过现有治理Token和当前管理委派登记不可变申请策略版本：完整tenant/app/env、固定RoleVersion、固定ScopeRule、最长秒数、指定审批成员及代际。策略不授予审批人新的管理资格；登记和最终批准均需当前上限覆盖。发布人和指定审批人都须有效，管理委派摘要固定。策略停用后不能新申请或按旧策略批准。

申请人由VerifiedLogin解析当前成员，仅能为自己申请；不得是最终审批人。只接受策略ID、固定UTC窗口和非空有界理由。角色、能力JSON、scope JSON、管理策略摘要、成员有效期上限、申请人主体、受益成员及代际都来自服务器。有效期不能超过成员截止时间、策略时长或审批人当前管理上限；数据库精度统一微秒。

每次新申请request_version=1、item_id=single；内容变更创建新申请，state_version独立推进。相同成员代际和command_id同体返回原申请，改体409。提交内容、审计、启动Outbox原子提交，未审批不创建Grant。数据库触发器拒绝修改固定内容。新角色版本不改变已有策略或申请。

HTTP仍使用/api/governance/v1前缀和独立治理Bearer链，新增requests开关缺省关闭。请求JSON沿用snake_case、严格未知字段拒绝和16KiB上限。策略注册POST /requests/policies；提交POST /requests；本人GET /requests/{id}（显式tenant_id/application_id/environment）；可申请策略GET /requests/policies。仅返回当前成员允许的策略和本人请求，分页ID游标每页至多100。外部不返回审批人目录、管理上限原文和其他成员申请。

## 后续切片绑定

OA使用request_id:request_version作为business key，接收固定快照；OA身份桥必须显式配置，不能猜测UUID与旧userId相等。启动Outbox有有界领取、租约、重试和耗尽状态；超时重试先按业务键查结果。回调只引用固定申请，服务身份和实际审批人分开验证。Inbox键producer/event_id，同ID改体单独隔离，不能以异常回滚冲突证据。

批准仍需当前申请非终态、请求版本/摘要/实例/策略匹配、审批人为指定成员且非受益人、双方成员有效与当前管理上限满足。创建source_type=OA_REQUEST、source_id=request_id:single:1的唯一Grant和固定scope、P3投影意图、审计与Inbox同事务。APPROVED不等于ACTIVE。

取消与批准串行化同一分区和申请；取消先提交则旧批准不授予，批准先提交则取消撤销本来源。到期/成员退出实时判权先拒绝，有界扫描负责持久化回收；不影响其他来源。查询从当前Grant、P3栅栏/回执和当前成员状态计算展示，不伪造图就绪。

首版通知为数据库持久站内状态通知，独立Outbox重试；邮件/短信渠道未授权且不宣称验证。申请/Inbox/Outbox/审计保留，未制定生产保留期限前不物理删除去重证据。

## 取消与回收

`POST /api/governance/v1/requests/{id}/cancel`接收完整分区、command_id和state_version。只允许当前代际本人；旧视图针对不可变同一申请的取消仍有效，若批准先提交自动回收对应OA_REQUEST来源。APPROVED保留历史事实，由execution返回REVOKING/REVOKED。调用方不能指定其他Grant。RequestLifecycleCli每轮最多100条，按数据库时间及当前成员资格收敛到期/离职，实时鉴权不依赖此任务。

## 本人列表、状态和站内通知

GET requests、requests/policies、requests/notifications返回`{items,next_cursor}`，固定最多100条，`after`为服务端返回游标。策略游标来自过滤前扫描页。GET requests/{id}/execution包含启动/回调技术状态、真实投影operation_id和other_active_grant_count（仅提示存在其他来源，不等于同一权限仍可用）。所有本人入口强制当前成员代际，不接受代查身份。通知仅站内渠道，安全模板REQUEST_PROGRESS_UPDATED由页面映射为“申请进度已更新，请查看详情”。RequestNotificationCli单轮最多20条，独立五次退避后DEAD，申请/Grant保持原状态。

## OA审批依据

OA `GET /api/v1/flow/central-access/tasks/{taskId}`沿用真实JWT及oa:flow:todo:view，再验证当前实际指派、固定审批人桥接及分区，返回原固定Start快照；OA沿用camelCase Result封装。外部申请人即使知道taskId也不能读取。实际办理继续使用原todos/{taskId}/complete，首版仅指定本人批准，不支持代理批准。

能力紧急停用在申请策略、批准重新校验和执行展示时检查，停用后不能创建新Grant或继续显示ACTIVE。P3实时判权仍是业务执行权威。
