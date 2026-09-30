# CE05-CD 优惠券定义员工权限契约

复用已批准coupon_definition.create(HIGH)/read(NORMAL)、coupon_definition/TENANT_ALL。先接权益域的券定义，随后权益定义/实例、活动规则、人群/发券、旅程等有序片；不重定业务边界或新增审批。

CD0：中央增加稳定coupon_definition类型常量和两个有限HUMAN/60秒集合引用；create面向不存在的新版本，read为目录集合，均不凭空构造已有对象事实。ScopeResourceBindings现有TENANT_ONLY保持。真实PG+授权图验证有限组合、代际/撤权/到期、拒绝借用会员/门店/point_offer类型；SDK格式不变。

CD1：COUPON_DEFINITION接管族及后续追加V60（实施前查可用序号）；审计约束同迁移扩展coupon_definition且store_id为空，不重复V58遗漏。CouponService.create原Commands使用集合permit，route/期限guard先于回执，中央稳定身份加入hash、旧模式原hash保持；实际门店及商家ACTIVE/原金额、额度、两种发行方式、相对有效天数/资方比例规则不变。成功审计实际definitionId，真实version在原命令响应中持久化，可按同actor/operation/key关联；不把100字ID与版本拼成长于resource_id上限的伪资源。

GET /v1/admin/coupon-definitions与POST创建精确员工接入；共享/coupon-definitions的非MEMBER调用在Service也必须校验，旧ADMIN不能旁路。MEMBER只返回PUBLIC最新版本；员工read返回包含SOURCE_ONLY的真实本租户门店目录，返回前scope复核。SQL现有每definitionId最大version，after按definitionId稳定分页，不宣称返回全部历史版本；store只业务归属过滤，不构造门店授权。只读不占额度。

MEMBER领取/钱包以及可信兑换/活动/发券/旅程来源发放保留原身份、资格、发行额度/来源幂等和事务。员工停止不撤销已发券，不停掉既有订单履约。真实MySQL验证本片仅create一种写入的审计原子回滚、版本唯一、原键/代际、scope/Owner实际门店/旧ADMIN旁路、客户只PUBLIC及受控发放不受员工撤权影响。真实HTTP联调无read创建，撤权后旧回执403、503、精确审计及客户/积分兑换回归。

CD2：沿SSO壳两Tab目录和创建定义、独立create-access；已知store/definitionId/version，显示最新定义及issued实际发行数；金额十进制字符串、布尔stackable显式选择、issuanceMode PUBLIC/SOURCE_ONLY显式选择，nullableplatformFundingBps/validityDays保留服务端语义而不发明默认业务值。未知原键重试、409保留输入、401/403/503、实际1440/390与真实提交审计验证。日期时区明确；无自动发券或OA审批。

## 实际字段与兼容规则

复用CouponApi.Definition/DefinitionView，不新建业务DTO或扩大金额范围。minimumSpend为0—999999999999.99 CNY，discountAmount必须大于0且同上限；服务端Money拒绝分以下精度丢失，输入字符串最长32。UI用精确十进制字符串最多两位小数，不经过JS浮点运算。name128，标识沿Identifiers最长100，version正整数，quota1—1000000；validFrom/validTo UTC瞬间且前者早于后者。stackable显式布尔。platformFundingBps为null或0—10000，持久化null按现有0；issuanceMode为null/PUBLIC/SOURCE_ONLY，null按现有PUBLIC。validityDays为null或0—366：null/0仍原绝对有效期，正数按实际领取时刻计算；活动绑定另需正数，不由定义创建权限暗中批准。页面可留空nullable项且不发明业务默认值。

定义版本独立不可变，重复(id,version)冲突，沿原命令响应/幂等摘要和数据库唯一性。最新目录按tenant+definitionId全局最大version，再使用store/发行方式过滤，不改为每店最大版本、不回退暴露旧PUBLIC版本。issued是已发行数，不宣称可用余额；预留与客户资格仍由原领取/履约路径决定。身份审计actual definitionId，加原命令响应中的实际version可关联；不改已执行迁移，不写其他服务表。

## 有序实施切片

|ID|结果与Owner|Needs|验收|Runtime/并行|状态|
|---|---|---|---|---|---|
|CE05-CD0|auth ScopeDtos/ScopeResourceBindings/ExecutionAuthorization两有限能力；SDK格式兼容|CE04-O2 DONE|HUMAN/TENANT_ALL/60秒集合；错误类型/能力/代际/环境/到期、撤权重授不复活；真实PG+SpiceDB与SDK兼容|复用本地PG/图；串行|DONE（本地）|
|CE05-CD1|commerce CouponService/EmployeeAccess/Authority/精确HTTP与V60（实施前复核）|CD0 DONE|真实MySQL创建/幂等/审计回滚/Owner/分页；共享客户目录无ADMIN旁路；真实中央撤权/503、客户领取及受控发放兼容|既有MySQL/隔离身份；串行|TODO|
|CE05-CD2|固定SSO券定义目录/创建两Tab、独立hint|CD1 DONE|真实提交/未知原样重试/409纠正/401/403/503、1440/390截图查看、SQL审计无重复|既有Vite/Playwright；串行|TODO|

CD0已验证，限定四个中央协议/执行授权/集成测试文件，不触碰商城Owner。CD1/CD2依赖上片验证及交付；生产人员/映射/环境仍HOLD，不新增服务、缓存、MQ或OA审批。后续权益实例/活动/旅程等另沿原批准CE05契约细化，不以券定义一片宣布CE05完成。
