# CE04-C 会员周期与周期权益员工接入契约

本片消费已批准的七个HIGH、完整TENANT_ALL能力，不新建岗位或默认人员授权，沿现有发布、考核与权益履约流程。生产映射、人员审核和部署不在本地实现授权内。

|能力|中央资源类型|使用方式|
|---|---|---|
|commerce.member_cycle.policy.read / publish|commerce_member_policy|版本化策略集合许可|
|commerce.member_cycle.read / evaluate|commerce_member|实际会员Owner事实|
|commerce.cycle_benefit.read / define|commerce_member_policy|按策略版本查询/定义礼包的集合许可|
|commerce.cycle_benefit.grant|commerce_member|实际会员Owner事实|

## 授权与一致性

中央只增加上述有限组合，最长60秒HUMAN引用，必须完整TENANT_ALL；策略类不能伪造资源执行检查，会员类必须真实Owner。商城分别使用MEMBER_CYCLE、CYCLE_BENEFIT两个接管族，旧ADMIN在CENTRAL/STOPPED不能绕过。只允许新增V56，不修改已执行V49—V55。

员工公共读取先核对相应scope，会员读取还使用Member实际身份/版本；读取结束复核范围指纹和Owner版本。发布、考核、礼包定义、人工补发在Commands原事务内，路由锁、Owner锁和本地期限复核在旧幂等回执之前；中央模式把稳定主体/代际纳入命令摘要，旧管理员模式保持原摘要。业务行、Outbox、命令回执和身份审计同事务，身份审计失败必须回滚业务效果。5秒为本地准入期限，不声称跨库瞬时撤权。

周期策略审计目标为真实不可变策略版本commerce_member_policy/cycle-policy-<version>；礼包定义中央授权类型仍policy，实际审计种类固定commerce_cycle_benefit与bindingId。只扩本地审计允许分类，不增加中央授权资源类型或HTTP自选分类。考核、补发审计实际会员；无奖励的补发仍是明确命令，不能伪造成功奖励数量。

## 保持既有业务与系统履约

周期策略version>0，生效时间取毫秒且在当前前60秒到未来一年内，periodDays为1—366，1—8个唯一等级，首档0、门槛递增且不超过1e12。历史策略稳定版本游标；evaluate沿既有会员锁、来源净贡献、当前/保级周期和Outbox规则，read没有隐式考核。客户current与绑定本人read不转成OA员工身份。

礼包定义沿真实policyVersion/level/storeId、1—8个唯一权益引用与实际有效窗口校验，list按policyVersion查询；人工grant只评估当前周期，原source唯一摘要防止重复发放。已接受周期策略推进、考核事件和权益履约继续以系统职责执行，不因员工撤权中止已承诺效果。

既有MemberBenefitService通过cycles.policy(actor)/read(actor)读取其内部依赖；直接套员工门禁会错误要求额外cycle.read。增加最小内部policyForOperation/viewForOperation端口，明确tenant与MANDATORY现有事务，只供已授权礼包用例或可信事件处理使用；不向HTTP暴露，不附赠员工周期读取能力。系统事件处理去除只用于传tenant的伪ADMIN，复用真实Member Owner锁和ACTIVE规则，保留迟到周期/等级信号忽略、可靠重试/隔离与权益来源幂等。不得借此重写全局身份模型或后台治理。

## 有序切片

|ID|依赖、影响与可观察验收|状态|
|---|---|---|
|CE04-C0|B2；auth ExecutionAuthorization有限七组合；既有协议/SDK格式不变，252单元及真实PG+graph新矩阵、SDK Boot4/package|DONE（本地）|
|CE04-C1|C0；MemberCycleService/MemberBenefitService、最小内部API、EmployeeAccess/Authority及精确HTTP绑定，V56；真实MySQL及中央联调独立能力、真实Owner、事务回滚和系统事件兼容|TODO|
|CE04-C2|C1；独立周期与礼包SSO页/动作提示；真实浏览器读写、错误恢复与1440/390截图查看|TODO|

串行实施，无新增Runtime组件/版本升级；复用本地专用MySQL、独立PG/图/IdP与现有测试工具。C1验收覆盖无read的publish/evaluate/define/grant、两个族互不旁路、Owner竞争/外租户、参数边界/稳定分页、原键重试/跨代际冲突、撤权/STOPPED/503、审计故障整笔回滚、既有客户本人及系统考核/迟到事件/配额不足回滚。真实浏览器不以模拟成功响应替代真实效果，可在服务端成功后丢响应来核验原键恢复。

C2沿既有AntDesign/SSO，周期页分政策查询/发布与指定会员读取/考核；礼包页按已知policyVersion查询，定义使用已知版本/等级/门店/权益引用，人工补发使用已知memberId。不要求其他域列表权限；每个写动作独立提示，submit在服务端重新判权。未知结果固定原输入/键，409保留输入、401卸载、403独立拒绝、503失败关闭；已知实际结果展示，不编造奖励。

依据：member/cycle/MemberCycleApi及MemberCycleService；benefit/memberbenefit/MemberBenefitApi及MemberBenefitService；已批准COMMERCE_PERMISSION_BINDINGS.json中的7能力与原HTTP路径；现有MemberCycleTest真实周期/礼包/配额与迟到事件回归。详情实现遵从原DTO/URI，不扩大为其他营销或交易授权。
