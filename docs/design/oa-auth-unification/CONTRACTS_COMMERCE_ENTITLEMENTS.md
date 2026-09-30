# CE05-E 权益定义与实例员工权限契约

消费已批准扩展契约中的四个HIGH能力：`entitlement_definition.create/read`、`entitlement.read/resolve`。定义与实例分别由商城权益模块提供权威事实，首批均TENANT_ALL；OA员工不成为客户会员。此细化沿现有EntitlementApi/Service/Mapper/Controller，不新增业务状态、外部扣款或OA逐笔审批。

## 有序实施切片

|ID|结果与Owner|Needs|验收|运行边界|状态|
|---|---|---|---|---|---|
|CE05-E0|auth两资源类型、四独立有限执行能力|CD2本地DONE且Git交付|定义只集合；实例读取集合/实际grant，resolve实际grant；HUMAN/TENANT_ALL/60秒，错类型/能力/代际/环境/到期、撤权重授不复活，真实PG/授权图与SDK兼容|既有本地PG/SpiceDB，串行|DONE（本地）|
|CE05-E1|commerce两个接管族、真实Owner/事务审计和追加迁移|E0验证交付|独立两写、真实Owner、审计回滚、幂等/撤权/STOPPED/503；客户核销/退款欠项与既有系统发放兼容|专用MySQL与新建隔离演练库，串行|DONE（本地）|
|CE05-E2|独立权益定义页和权益实例页及各自操作hint|E1验证交付|真实读写/409/未知原键/401/403/503、1440/390截图查看、SQL精确审计|既有SSO/AntDesign/Vite/Playwright，串行|READY|

E1/E2可在实施前按定义/实例拆稳定子ID以控制单片范围，但不改变四能力边界。生产映射/目标/Owner继续HOLD；不新增服务、缓存或消息组件。

## E0 有限协议

稳定资源类型`entitlement_definition`与`entitlement`。定义创建面向不存在的新版本、定义读取面向最新目录，均只接受集合许可，不能用伪对象事实取得单版本执行权。实例读取可取租户集合；实例resolve使用实际grantId和version。独立resolve hint可校验完整租户资格，但返回提示不产生可复用许可，提交仍核对真实目标。

沿现有Reference/ScopeCheck/Check协议，HUMAN且最长60秒，无新JSON字段；每个能力精确绑定类型。TENANT_ONLY禁止将门店、会员、定义或其他资源事实混入。引用检查保留期限、租户、身份代际、调用方/环境、原授权路径交集和目录版本；撤权后同组重新授予不复活旧引用。旧能力组合与SDK序列化兼容。

## E1 业务、持久化与失败语义

两个独立接管族`ENTITLEMENT_DEFINITION`、`ENTITLEMENT`。追加迁移序号实施前核对（当前V60已用），同时扩族与两资源的身份审计约束，store_id为空；不修改V49—V60。按已验证路由状态/代际执行单权威，无中央失败后的旧ADMIN退路。

定义POST/GET精确接入`/v1/admin/entitlement-definitions`。创建使用集合permit，route/期限guard先于旧回执；原command input摘要在旧模式不变，中央仅加入稳定身份；实际benefitId审计和定义、command同事务，version由真实响应关联。沿原门店/商家ACTIVE规则。读取沿原Identifiers和分页，不新增ACTIVE过滤：tenant+benefitId全局最大version，然后store过滤，按benefitId稳定游标；返回前scope复核。

实例GET `/v1/admin/entitlements`按tenant+grantId游标返回所有状态，不伪造门店粒度。POST `/v1/admin/entitlements/{id}/resolve`先以tenant+id读取实际grantId/version判权；事务内先锁route，再锁真实grant并复核事实/期限，guard先于旧回执。原摘要`Map.of(id,input)`在旧模式保持，中央只加入稳定身份；动态version不进入幂等摘要。只有原状态`COMPENSATION_REQUIRED → COMPENSATED`，remaining/debt归零，版本CAS、原RECOVERED/WRITTEN_OFF账本（units为原debt、reference为实际凭据）、command和身份审计原子提交。影响行数冲突不当成功；不新增客户端expectedVersion字段，不执行外部资金补偿。

客户wallet/consume/ledger保留members.current实际归属。受信任订单、积分、等级、旅程及既有`internal-entitlement-grant-v1`消费者继续原幂等和事务，员工撤权不停止已承诺发放。真实验收优先业务链路构造待补偿实例；如使用隔离SQL夹具必须明确记录，不能冒充真实退款验收。核对客户消费、超扣拒绝、退款欠项及事件继续履约，并精确核对账本/command/身份审计。

## 原API字段与E2页面

Definition：benefitId/version/storeId/name/units/quota/validFrom/validTo/validityDays。Identifiers为`[A-Za-z0-9_.:-]{1,64}`，name128，version正整数（页面用安全整数），units1—10000，quota1—1000000，validityDays1—365，UTC窗口from<to。DefinitionView是content+reserved+issued，预留和已发分别展示，不将任一当成可用余额。最新目录不是历史版本列表。

实例View沿grantId/orderId/memberId/benefitId/benefitVersion/name/status/units/remainingUnits/debtUnits/expiresAt/version/sourceType/sourceId；Resolution仅RECOVERED或WRITTEN_OFF和最长128字的reference，枚举未知值拒绝。实例页面展示实际状态/剩余/欠项/来源；不新增员工客户核销入口。

两固定SSO页`/operations/entitlement-definitions?tenant_id`和`/operations/entitlements?tenant_id`，分别目录/创建与目录/处理补偿。独立GET提示`/v1/operations/entitlement-definitions/create-access`、`/v1/operations/entitlements/resolve-access`，不附赠门店、会员或其他读权限。中央客户端固定允许列表，逐键校验查询参数并正确编码Identifiers字符；不向旧控制台写入凭据。

沿现有真实API，不制造DTO字段；结果未知冻结原键/体/路径，切Tab/取消退出保留。409保留输入可纠正，401卸载工作区，403仅拒绝独立动作，503关闭写表单并允许重核验。真实浏览器验证无read写入、分页/跨组织清除、两补偿结论、撤权后保留读、实际停机与原样重试，当前1440/390截图需实际查看，并用真实SQL证明无重复审计/业务效果。


E1验收：完整460项455PASS/5skip及真实独立477PASS；详见CE05_MARKETING。V61已应用且不可修改，E2不新增迁移。
