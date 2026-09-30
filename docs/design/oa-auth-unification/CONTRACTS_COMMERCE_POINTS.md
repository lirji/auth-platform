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
|CE04-PTS2|PTS1|积分员工SSO页与三个独立hint|真实读写/未知重试/错误/撤权/503，1440/390截图|TODO|

公开DTO及路径沿MemberPointsApi/MemberPointsController，不新增审批状态字段。GET policies、GET member wallet/ledger，POST policies、member adjust/expire分别映射上述五能力；MEMBER_PATHS只保留绑定本人。policy只scope、不得构造会员事实；钱包及账本在真实Member范围内读后复核；两会员写guard先路由再会员版本，截止复核早于原幂等回执。人工调整仍有原原因和预期账户版本，两种版本不同职责，不把动态Member或Account版本混入稳定身份摘要。

原会员草案CE04-G/T/B/C/P的最后P指积分类别，现细化为PTS0/1/2，已交付基础会员P0/1/2的稳定ID保持。积分商品独立CE04-O，后续再细化，不借PTS赋予商品定义或客户兑换能力。
