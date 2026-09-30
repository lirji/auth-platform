# CE04-G 成长员工接入实施契约

已批准成长5能力：growth.policy.read/publish→commerce_member_policy；growth.read/adjust/recalculate→commerce_member。均完整TENANT_ALL、HIGH、HUMAN执行引用最多60秒，显式Owner登记。G0只扩展有限执行能力和集合类型；政策列表和追加发布使用Scope，不伪造已有政策对象，政策resource-check不开放；三个成长实例能力使用真实会员Owner事实。

G1 MEMBER_GROWTH独立族（不与MEMBER_PROFILE/后续标签混迁）。EmployeeAccess资源许可允许有限成长实例能力，集合许可对五项均要求完整租户；持久V53扩族与commerce_member_policy审计类型，已执行V52不改。政策权威为GrowthMapper version主键，新增版本发布使用scope/路由锁/原Commands事务，幂等摘要中央时加稳定principal/member/generation，审计resource_id编码真实policy版本（例如growth-policy-<version>），保留原时间/精度/门槛/不可变规则。

wallet/ledger员工分支先scope、真实会员事实、resource-check；读取后scope指纹/Owner版本复核。MEMBER本人分支沿用byActor，不允许OPERATOR靠actorId变成客户。current、observe、facts/scan、cycles系统调用保持内部职责；不把员工撤权套已发生订单退款成长对账。

adjust/recalculate：先真实Owner许可，Commands guard路由共享锁→实际会员FOR UPDATE并核对事实版本→再次期限检查，早于旧回执。原ensureAccount、accountCurrent、版本冲突、CLOSED限制、cycles贡献/等级与Outbox共原事务；guard不能提前产生业务效果。审计与每个动作同事务。不同主体代际不能读旧回执，撤权不能重试；生成新成员版本不破坏已确认幂等摘要。精确接管6个/admin/member-growth方法，不把同Controller标签入口提前接管。

G2独立SSO /operations/member-growth，复用AntDesign。原MemberGrowth组件会额外读取标签/行为/积分，不直接整体复用。针对真实5能力显示政策列表/发布、指定会员钱包/账本、调整/重算；动作提示独立、金额字符串和大数边界遵循DTO；客户当前页不改。实际浏览器、409/未知/撤权/依赖停机和原页面回归。

验收G0真实PG+graph+Boot4；G1真实MySQL+实际中央（政策独立/创建无读、真实会员范围、成长版本并发/幂等/审计/撤权、订单与客户回归）；G2真实API/SSO、表单/详情/1440/390截图查看。三片先后交付，不宣称成长片覆盖周期/标签/积分或后台CE08。


## 有序切片与当前状态

消费CONTRACTS_COMMERCE_EXPANSION与已批准5能力，技术细化不增加岗位授权或新业务流程。前置P2 auth21380a4/commerce359ac02已推送，完整407项402PASS/5skip和137项真实隔离通过，CI另记。用户沿用审批决定继续有效。

| ID | Owner与验收 | 状态 |
|---|---|---|
| CE04-G0 | auth有限5能力/两类型60秒执行scope，已有会员3能力check；完整租户/错误类型/事实/Owner/代际/撤权/重授/超时，旧11组合回归；SDK Boot4兼容 | DONE（本地） |
| CE04-G1 | commerce MEMBER_GROWTH/V53/真实政策和成长Owner、稳定身份幂等、事务审计、真实MySQL并发失败与实际中央联调、客户/系统事实回归 | DONE（本地，见CE04_MEMBER） |
| CE04-G2 | 真实成长API/SSO及5独立权限页面，错误恢复与1440/390实际截图/交互 | DONE（本地） |

G0政策能力不接受已有对象execution-check（ACCESS_DENIED），创建新版本不能伪造资源；仅execution-scope用于集合/创建。ScopeDtos新增政策类型常量只替代既有相同字符串，不增加ScopeResourceBindings允许语义或SDK API。有限类型匹配仍先于判权，不以startsWith通配新增能力。Owner注册、实际manifest和Grant仅在授权隔离演练里使用，不修改生产配置。

## G1锁与兼容细节

新增五个Capability共用MEMBER_GROWTH族，仅绑定六个既有成长HTTP入口（GET/POST policies、GET单会员/ledger、POST adjust/recalculate），同Controller标签和客户/me入口不接管。policy实际版本审计ID固定`growth-policy-<version>`，不是授权Facts；发布前只获取集合资格，原normalized Policy在中央模式追加稳定身份摘要。原政策生效时间、精度、门槛、不可变版本/唯一约束保持。

成长实例的guard在命令回执前依次锁路由、锁真实会员并比较许可版本、再校验准入截止；guard不创建成长账户。原用例随后ensureAccount/accountCurrent、expectedVersion校验、贡献/等级/Outbox与身份审计在同一事务。钱包/账本员工分支读前Owner判权、读后范围/会员版本复核；MEMBER本人仍走byActor，current与observe/facts/scan系统接口保持。没有改变既有recalculate对状态的业务规则，没有给增长调整新增OA审批。V53已在本地专用MySQL成功应用后保持不可变。

## G2页面与提示实施契约

固定/operations/member-growth?tenant_id=<UUID>，现有SSO与AntDesign。五能力对应政策列表/发布、成长钱包/账本、人工调整、等级重算；只发送对应精确API，未接管标签/行为/积分不能被旧MemberGrowth聚合组件隐式读取。政策读和钱包读独立，三个写提示GET /v1/operations/member-growth/{policy|adjust|recalculate}-access各固定能力，OPERATOR+scope双检查，仅提示，不替代提交时Owner。

钱包以已知会员编号查询，无额外member.read依赖；真实Wallet字段成长余额、净消费字符串、等级/政策版本/账户版本，ledger按sequenceId游标，Policy按version游标，不伪造总数。发布沿用Policy DTO：version、安全整数、effectiveFrom本机输入转UTC、growthPerYuan字符串、1—8个levels（code/minimumGrowth），首档0/递增约束仍后端权威。人工调整沿用Adjustment expectedVersion/delta/reason，delta上下限1e9且非0；重算只需已知memberId，独立能力不隐含人工调整。

复用三种命令意图的未知结果处理：网络/5xx保留原body/path/key且锁输入，之后403不能丢失旧意图；4xx明确未执行可核对后提交新意图，409保留输入。403只影响对应域，401移除业务，503明确服务不可用。切Tab保留输入/结果，退出/离开提示未保存内容。只对真实已授权隔离库操作。

验收真实SSO、政策publish-only、读取独立、调整无read、版本409/改正、重算独立、政策或调整真实响应丢失同键重试、撤权/跨租户/401/故障、输入校验/多等级和游标、1440/390列表/表单/错误截图实际查看；共享会员/目录/库存/CATALOG回归。无新依赖、schema或Runtime。

G2表单按既有服务约束提前校验：成长率0—1000、最多两位小数；等级编码遵循1—100位业务Identifier，门槛不超过9000000000000000且首档0/严格递增，版本使用JavaScript安全整数。政策/流水每批50，下一页分别取真实末行version/sequenceId，不把返回条数当总数。
