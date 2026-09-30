# CE04-PTS 积分员工权限接入契约

采用已批准五个HIGH/TENANT_ALL能力：points.policy.read/publish使用commerce_member_policy集合；points.read/adjust/expire使用实际commerce_member。PTS避免与已交付基础会员CE04-P编号冲突。后续积分商品单独CE04-O，不在本片扩大权限。

PTS0：中央仅增加这五个有限组合，HUMAN引用最长60秒，政策只集合许可、会员真实事实；复用协议/SDK格式；252单元和真实PG+SpiceDB有限矩阵、SDK/Boot4/package。
PTS1：MEMBER_POINTS接管族、追加V57、EmployeeAccess/Authority精确绑定六员工HTTP入口，MemberPointsService按原Commands事务核验路由/Owner锁/身份版本/期限，旧回执之前检查；稳定中央身份进入摘要、旧模式保持历史摘要。策略不可变真实版本审计，adjust/expire审计实际会员；钱包/账本只读复核scope和Owner，不隐式过期写入。调整仍ACTIVE、账户expectedVersion、非零±1e9及256字原因；获取率精确两位0—1000、有效期1—366、消费兑换率1—100000、抵扣比例0—10000bps。发布不加积分，正调整需当前有效策略，负调整沿有效批次与欠款规则；expire单命令最多100到期批次，不改为全库重放。
客户current/本人账本、积分兑换、订单奖励/退款事实和系统到期任务保留原有身份/事务/幂等，不受员工撤权中止；内部积分结算不用员工ADMIN旁路。人工调整不新增OA逐笔审批。
PTS1真实MySQL验证无read发布/调整/过期，实际会员不存在/外租户/Owner竞争、版本冲突/原键/代际、三写审计故障整笔回滚（批次/账户/账本/命令一起）、两个历史政策游标、客户本人/兑换/持有/退款/系统过期回归。真实中央HTTP验证撤权后旧回执拒绝、STOPPED/503、精确身份审计。
PTS2：固定SSO积分页，策略查询/发布、已知会员钱包与账本、独立调整、独立到期推进；三hint独立，不要求会员列表/points.read。表单保持实际DTO，未知保留原键与输入/409可纠正/401卸载/403独立/503失败关闭；展示available/held/debt/credit/version，账本stable sequence游标；正负校准不假装支付。到期结果为实际钱包，无伪造批次数。实际1440/390截图及真实提交丢响应重试、SQL审计验证。

数据约束/库/公共中间件沿现状；V56以前不改。发布/回退采用认识MEMBER_POINTS族的兼容版本和STOPPED，不回退旧ADMIN绕过版本；已发生积分效果走既有调整/退款，不能靠代码回退销账。生产人员和映射待Owner，不自动授予ADMIN。


## 有序实施与证据

|ID|前置|Owner与影响|验收|状态|
|---|---|---|---|---|
|CE04-PTS0|CE04-C2本地DONE|auth ExecutionAuthorization/现有集成矩阵；无新协议类型|五能力有限HUMAN/TENANT_ALL/60秒、集合或真实会员事实、范围/代际/撤权/到期，SDK共存|DONE（本地）|
|CE04-PTS1|PTS0|commerce MemberPointsService、EmployeeAccess/Authority、6员工HTTP绑定、V57|真实MySQL事务/Owner与既有积分业务回归、真实中央联调|DONE（本地）|
|CE04-PTS2|PTS1|积分员工SSO页与三个独立hint|真实读写/未知重试/错误/撤权/503，1440/390截图|DONE（本地）|

公开DTO及路径沿MemberPointsApi/MemberPointsController，不新增审批状态字段。GET policies、GET member wallet/ledger，POST policies、member adjust/expire分别映射上述五能力；MEMBER_PATHS只保留绑定本人。policy只scope、不得构造会员事实；钱包及账本在真实Member范围内读后复核；两会员写guard先路由再会员版本，截止复核早于原幂等回执。人工调整仍有原原因和预期账户版本，两种版本不同职责，不把动态Member或Account版本混入稳定身份摘要。

原会员草案CE04-G/T/B/C/P的最后P指积分类别，现细化为PTS0/1/2，已交付基础会员P0/1/2的稳定ID保持。积分商品独立CE04-O，后续再细化，不借PTS赋予商品定义或客户兑换能力。


## PTS2页面与三个动作提示

固定 /operations/member-points?tenant_id，沿已交付SSO/AntDesign。单页五工作区：政策查询、发布积分政策、指定会员钱包/账本、调整积分、推进积分到期（五Tabs）。三个提示独立 /v1/operations/member-points/{policy|adjust|expire}-access；不要求基础会员列表或积分读取。

政策表 actualversion/effectiveFrom/earnPerYuan/expiryDays/spendEnabled/pointsPerYuan/maxDeductionBps，稳定after版本分页limit50，日期本机显示。发布form version正安全整数、datetime-local转UTC、获取率字符串两位小数0—1000、expiry1—366、spendEnabled显式开关、pointsPerYuan1—100000、maxDeductionBps整数0—10000且页面说明10000=100%。不会自动赠分或改历史订单。无等级数组。

钱包展示available可用、held冻结、debt待偿扣回、credit有效批次余额、version账户版本和实际memberId，不把成长或现金金额混入。账本显示真实ADJUST/EARN/REVOKE/EXPIRE/HOLD/SPEND/RELEASE/REFUND/EXCHANGE稳定码对应中文，以及delta/available/held/debt/policyVersion/reason/time/sourceId，sequence游标50，安全整数检查。已知会员查询policies保留字冲突明确拒绝，沿当前API不更改身份契约。

调整form memberId/expectedVersion>=0/非零整数delta±1e9/reason<=256；不凭空读取最新版本，不附赠read。到期form只有memberId，明确单次最多100已到期批次、只推进到期，不延长有效期、不生成新增积分；结果真实Wallet，不编造完成批次数。三写各自固定原键/体/路径unknown冻结+原样重试、切Tab/取消退出保留、409保留可纠正、401卸载、403独立/503失败关闭。

实际browser policy-only/adjust/expire/read/revoked/outage：独立无read、政策边界、真实409原输入保留、三写服务端成功丢响应原样重试、wallet五余额、真实账本、源数据到期由演练SQL显式设置、无隐式读取写入、跨租户清除/撤写保留读/401/503、1440/390截图实际查看。命令数/账本数/政策真实版本和身份审计SQL一致。既有周期/行为/其他页回归保持。页面实现及验收完成前保持IN_PROGRESS。
