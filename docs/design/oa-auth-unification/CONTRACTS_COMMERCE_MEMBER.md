# CE04 会员员工接入实施契约

消费已批准EXPANSION/ADAPTER和122能力绑定，先完成会员基础资料的薄路径，再成长/标签/行为/周期/积分。会员权威是商城，OA只负责员工身份；不赋予客户SSO、不把OA部门或门店当成会员归属、不新增逐笔审批。复用原事务、SQL和Runtime，没有新中间件。

## CE04-P0：基础会员执行协议

前置CE03-D本地DONE，auth3a8f7e9/commerce5587d53已推送（远程CI另记）。issue增加有限四组合member.read/create/profile.update/status.update→commerce_member，HUMAN引用最长60秒；仅完整TENANT_ALL路径。未知会员能力、错误类型、跨租户/调用环境、长引用拒绝。未修改真实清单/Owner配置，Owner仍须显式登记。

execution-scope复用已有Plan JSON/SDK方法，允许上述四能力返回集合范围；用于列表、创建和动作提示，不伪造资源。execution-check增加member.read/profile.update/status.update的会员事实：可信Owner的tenant/type/id/version，所有store/owner/department/supplier字段必须空、ancestors为空。事实类型必须等于引用及请求类型；create不能用虚构已有对象调用resource-check。HTTP execution-check同issue/scope要求显式Owner类型。旧CATALOG、库存、目录四能力期限/范围与JSON保持兼容；旧SDK方法可传已有受支持commerce_member类型，无新端点或缓存。

当前有效Grant与原签发完整路径取交集，绑定原身份/成员代际、目录epoch及到期；撤销后新Grant不能复活旧引用。故障保持503，不返回旧范围。本片只提供协议，不代表会员Owner或页面已接管。

## 后续Owner与页面边界

CE04-P1接管会员基础4能力与MemberService list/create/change/history/stats。MEMBER_PROFILE独立族只覆盖基础资料，不使成长/积分等半迁移后旁路。列表及stats明确全租户；真实会员history/profile/status须从本域获取事实，事务内按版本/状态更新和同事务身份审计。create无对象事实，使用全租户ScopePermit；Outbox注册事件与命令同事务保持。变更action封闭PROFILE/STATUS映射独立能力，未知值失败关闭。新增兼容V52扩展族/审计类型，不更改已执行V49—51。requireActive/current/lockForOperation保留内部业务与客户身份语义，不把后台自动成长交易套员工权限。

DashboardController聚合调用stats；MEMBER_PROFILE迁移后旧ADMIN不能通过聚合绕过，完整中央聚合接入留CE07逐源授权。用户已批准的业务审批流程不变。授权路由一旦CENTRAL/STOPPED，旧ADMIN、去头和关闭中央配置都拒绝基础员工入口。

CE04-P2复用现有SSO、AntDesign和真实会员列表/创建/历史/修改状态与资料契约，逐动作提示；真实租户范围、401/403/409/503与未知结果幂等恢复，桌面/窄屏和完整表单交互验证。UI具体接口在P1后按已实现API细化，不编造字段。

## 依赖有序切片

|ID|依赖|可观察结果/Owner|范围与验收|状态|
|---|---|---|---|---|
|CE04-P0|CE03-D|auth协议Owner可签发并复核4会员执行引用|真实PG+graph：四能力独立、TENANT_ALL、错误事实/类型/租户/代际、60秒、撤销/重授；HTTP显式Owner门禁；旧库存/CATALOG/目录回归；无Runtime变更|DONE（本地）|
|CE04-P1|CE04-P0|commerce基础会员API中央接管|真实MySQL身份幂等/审计、版本并发/合法状态、旧ADMIN/聚合拒绝、客户/内部原行为；实际中央跨进程|TODO|
|CE04-P2|CE04-P1|基础会员员工页面可用|真实API/SSO/操作与错误恢复，当前版本1440/390截图实际查看|TODO|
|CE04-G/T/B/C/P|CE04-P2及前一片|成长、标签、行为、周期、积分各自API+页面薄片|各片执行前细化对应已批准能力、Owner、合法迁移/幂等/审计/客户回归；后台系统职能留CE08|TODO|

各片串行修改共享协议/路由/schema；没有并行Agent授权。本文件不提前授予未实施能力，也不将生产输入不足扩散为本地开发阻塞。
