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
|CE04-P0|CE03-D|auth协议Owner可签发并复核4会员执行引用|真实PG+graph：四能力独立、TENANT_ALL、错误事实/类型/租户/代际、60秒、撤销/重授；HTTP显式Owner门禁；旧库存/CATALOG/目录回归；无Runtime变更|DONE（已推送/CI SUCCESS）|
|CE04-P1|CE04-P0|commerce基础会员API中央接管|真实MySQL身份幂等/审计、版本并发/合法状态、旧ADMIN/聚合拒绝、客户/内部原行为；实际中央跨进程|DONE（本地）|
|CE04-P2|CE04-P1|基础会员员工页面可用|真实API/SSO/操作与错误恢复，当前版本1440/390截图实际查看|DONE（本地）|
|CE04-G/T/B/C/P|CE04-P2及前一片|成长、标签、行为、周期、积分各自API+页面薄片|各片执行前细化对应已批准能力、Owner、合法迁移/幂等/审计/客户回归；后台系统职能留CE08|TODO|

各片串行修改共享协议/路由/schema；没有并行Agent授权。本文件不提前授予未实施能力，也不将生产输入不足扩散为本地开发阻塞。

## P1具体准入与事务（实施前细化）

EmployeeAccess新增ResourceFact(type/id/version)及ResourcePermit(ScopePermit, fact)，复用现有集合许可的路由锁/身份审计，不新增另一套权威或Token。先scope确认四能力全租户资格，再由Member Owner读取真实记录；resource(actor, scope, fact)仅对member.read/profile.update/status.update调用execution-check，复核相同主体/代际、路由与5秒截止。ScopePermit不代表已检查对象，Owner必须持有ResourcePermit；create只用ScopePermit。

list/stats先scope、SQL本来就按可信tenant过滤、返回前复核相同范围指纹；history先全租户资格、Owner真实会员事实、resource-check、SQL游标查询，返回前再次scope并校验Owner版本未变。change选定封闭action后先scope、真实Owner读取和resource-check；Commands guard先共享锁权威路由、再FOR UPDATE锁会员并比较与许可事实版本，之后才读回执。新命令继续原expectedVersion、CLOSED终态、状态允许集合和影响行数规则。同键重试重新取得当前事实，不将易变资源版本放入幂等摘要，稳定principal/member/generation加入摘要；真实变更/history和身份审计共事务。

新增V52仅允许MEMBER_PROFILE族和commerce_member审计资源（store_id为空、resource_id非空），保留V51旧库存/目录兼容与状态触发器，不改变原业务表或旧迁移。HTTP仅精确GET/POST members及单ID的profile/status/history，未知action/额外路径不匹配；旧客户current与业务内部requireActive/lockForOperation保持，未接管其他能力不能使用本片OPERATOR引用。SKU/目录/inventory既有测试全回归。

## P2页面与动作提示细化

固定GET /operations/members?tenant_id=<UUID>，沿用现有SSO、同源safeReturn允许列表和AntDesign主题。只由本页客户端携中央Token和X-Tenant-Id，精确允许基础会员列表/创建、单ID profile/status/history和下述三个提示，不提供旧控制台通用客户端。

新增GET /v1/operations/members/create-access、profile-access、status-access，各固定映射独立member.create/profile.update/status.update。返回{allowed:true}，调用原scope前后指纹比较；必须OPERATOR+执行引用，旧ADMIN拒绝。提示只说明当前租户操作资格，不授权任何对象；实际提交仍由P1读取真实Owner事实并核对。无读权限也可按已知编号/版本创建或修改，不能隐含member.read。

页面主任务为会员列表（50条游标）及独立新建/资料修改/状态修改Tabs；列表行可打开审计Drawer。独立修改岗位按已知会员编号/版本填写，不伪造详情。创建字段复用Create，修改复用Change及固定profile/status路径，历史复用History按version游标。表单名称唯一；中文状态ACTIVE/FROZEN/CLOSED；注销显示不可恢复提示并按原工作流提交，不新增OA审批。审计显示真实前后值、原因、操作人、时间和版本。

所有提示403仅影响对应操作；读取403不隐藏已授写入口。401隐藏业务页、503不当空列表；409保留输入并提示核对最新版本。POST网络/5xx结果未知锁定原body+Idempotency-Key，原样重试；其后403不得遗失未知意图。切Tab保留输入，退出/浏览器离开提醒未保存内容，审计关闭返回原列表游标。

验收包括真实SSO、列表/审计Drawer、创建独立于读、资料/状态独立、注销终态、未知结果同键重试、409冲突、撤权、真实依赖停止、退出/401、1440/390无页面横向溢出和当前各布局截图实际查看。旧目录/库存/CATALOG共享登录壳消费者回归。无新Runtime/依赖/schema。
