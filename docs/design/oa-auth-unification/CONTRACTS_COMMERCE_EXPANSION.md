# 商城全模块中央权限扩展契约 v1

状态：BUSINESS_APPROVED；2026-09-29用户明确采用员工端/租户与门店范围边界，并选择“先拆权限，沿用现有审批流程”。用户已选择“本轮扩展其他模块，先补能力与权限契约”。2026-09-29。本文是原P1—P7之外的增量设计，不改变原63节点完成历史。新增能力尚未发布、未授予任何人、未用于放行业务。

## 已核对事实与设计边界

commerce基线31dbdcd，auth基线74d2e75。入口逐项见 [HTTP清单](../../implementation/oa-auth/commerce-readiness/HTTP_INVENTORY.md)，包含218条注解展开路径（含本轮新增静态壳）。原P6完整CATALOG已经接管商品经营API与后台任务，P5商品读改/导出是另一组独立能力；库存、会员、营销、交易等仍由原ADMIN/MEMBER边界保护。

F1：会员实体只有tenant/member/actor关系，没有可信门店、OA部门归属（MemberMapper.xml）。不把员工所在部门当成客户数据归属。
F2：库存以tenant/store/sku定位（InventoryMapper.xml）；订单、履约、售后及退款必须由Owner逐级读取关联订单/门店，不接收前端Facts。
F3：旧Actor.requireAdmin广泛存在，不能只给中央用户套ADMIN就宣称完成迁移；HTTP、领域用例、非HTTP调用和任务都要覆盖。
F4：营销规则、会员政策等包含租户级配置；页面虽有storeId，其当前render会聚合租户级数据，因此首版也只接受TENANT_ALL。没有门店归属的记录不能通过一个获授权门店顺带开放。
F5：后台有payments/refunds/orders/events/segments/journeys/cycles/points/deliveries/catalog-jobs/replay/retention共12条车道（EventWorker）；CATALOG之外尚无统一中央执行身份验收。

技术决定：复用现有Java/MySQL领域用例、P1主体/组织、P2清单/角色快照/委派、P3范围/执行引用和P4申请；不拆服务、不加中间件、不读写OA业务库。OA只为员工/部门权威，商城客户会员继续由商城拥有。仅增加必要的Owner事实适配与授权用例入口。所有新增技术部署需求为零；现有兼容性继续沿用已验证版本。

## 稳定能力与资源矩阵（业务边界已批准，非已发布清单）

代码前缀统一`commerce.`。下面每个后缀都是独立能力，不支持通配符或read隐含write。NORMAL只用于非敏感公开业务查询；其余列明HIGH。含个人数据的读取也为HIGH。资源类型首次发布后不得改义。

|业务作业/现有API族|能力后缀|resource_type及首批范围|风险|
|---|---|---|---|
|商品经营现有operations族|`catalog.operate`（保留）|store；既有P6范围|HIGH|
|现有门店读、商品读/改/导出|`store.read`、`product.read`、`product.update`、`product.export`（保留）|沿原P2/P3/P5，不由CATALOG隐含获得|沿已发布值|
|商家目录GET/POST admin/merchants|`merchant.read`、`merchant.create`|merchant；读TENANT_ALL/指定资源，创建仅TENANT_ALL|NORMAL/HIGH|
|门店目录GET/POST admin/stores|`store.directory.read`、`store.create`|store；读TENANT_ALL/指定门店，创建仅TENANT_ALL|NORMAL/HIGH|
|库存GET admin/inventory、POST receipts|`inventory.read`、`inventory.receive`|store；TENANT_ALL/指定门店；SKU属于该门店|NORMAL/HIGH|
|会员列表/历史、创建、资料、状态|`member.read`、`member.create`、`member.profile.update`、`member.status.update`|commerce_member；首批TENANT_ALL，禁止伪门店范围|全部HIGH|
|成长政策、账本、调整、重算|`growth.policy.read`、`growth.policy.publish`、`growth.read`、`growth.adjust`、`growth.recalculate`|commerce_member_policy（政策）/commerce_member（实例）；TENANT_ALL|全部HIGH|
|标签字典/分配/查询|`member_tag.read`、`member_tag.define`、`member_tag.assign`|commerce_member；TENANT_ALL，分配同时检查真实目标会员|全部HIGH|
|行为资料/事件查询、画像修改/重建|`member_behavior.read`、`member_behavior.update`、`member_behavior.rebuild`|commerce_member；TENANT_ALL|全部HIGH|
|周期政策/评估/结果|`member_cycle.policy.read`、`member_cycle.policy.publish`、`member_cycle.read`、`member_cycle.evaluate`|commerce_member_policy/commerce_member；TENANT_ALL|全部HIGH|
|周期权益定义/查询/发放|`cycle_benefit.read`、`cycle_benefit.define`、`cycle_benefit.grant`|commerce_member_policy（read/define）/commerce_member（grant）；TENANT_ALL|全部HIGH|
|积分政策、钱包/账本、调整、到期处理|`points.policy.read`、`points.policy.publish`、`points.read`、`points.adjust`、`points.expire`|commerce_member_policy/commerce_member；TENANT_ALL|全部HIGH|
|积分商品创建/上下架/管理查询|`point_offer.read`、`point_offer.define`、`point_offer.status.update`|point_offer；TENANT_ALL|NORMAL/HIGH/HIGH|
|活动创建/列表/预览/提交/审批/发布/暂停|`campaign.read`、`campaign.create`、`campaign.preview`、`campaign.submit`、`campaign.approve`、`campaign.reject`、`campaign.publish`、`campaign.pause`|campaign；首批TENANT_ALL|全部HIGH|
|规则查询/字段目录/创建/发布|`rule.read`、`rule.create`、`rule.publish`|marketing_rule；TENANT_ALL|NORMAL/HIGH/HIGH|
|预算查询|`budget.read`|campaign；TENANT_ALL；没有新增预算调整接口|HIGH|
|人群定义/列表/运行结果/调度/刷新/控制/推进|`segment.read`、`segment.create`、`segment.schedule`、`segment.refresh`、`segment.control`、`segment.pump`|segment；TENANT_ALL|全部HIGH|
|人群快照创建/读取|`audience.create`、`audience.read`|audience；TENANT_ALL|全部HIGH|
|优惠券定义创建/查询|`coupon_definition.create`、`coupon_definition.read`|coupon_definition；TENANT_ALL|HIGH/NORMAL|
|定向发券创建/名单/查询/控制/推进|`coupon_delivery.create`、`coupon_delivery.read`、`coupon_delivery.control`、`coupon_delivery.pump`|coupon_delivery；TENANT_ALL|全部HIGH|
|权益定义创建/查询、管理台账、异常处置|`entitlement_definition.create`、`entitlement_definition.read`、`entitlement.read`、`entitlement.resolve`|entitlement_definition/entitlement；TENANT_ALL|全部HIGH|
|旅程定义创建/校验/预览/查询/状态流转|`journey.create`、`journey.validate`、`journey.preview`、`journey.read`、`journey.submit`、`journey.approve`、`journey.reject`、`journey.publish`、`journey.pause`|journey；TENANT_ALL；action须按现有状态机枚举逐项匹配，未知拒绝|全部HIGH|
|旅程实例/扫描读取、创建/控制/重试/推进|`journey_instance.read`、`journey_instance.create`、`journey_instance.control`、`journey_scan.read`、`journey_scan.retry`、`journey.pump`|journey_instance/ journey_scan；TENANT_ALL|全部HIGH|
|营销效果/执行记录查询、重建|`marketing_effect.read`、`marketing_effect.rebuild`、`marketing_execution.read`|marketing_report；TENANT_ALL|全部HIGH|
|运营订单/详情/支付状态、主动支付核对|`order.read`、`payment.read`、`payment.reconcile`|store（Owner读取订单关联）；TENANT_ALL/指定门店|全部HIGH|
|履约列表/发货/送达|`fulfillment.read`、`fulfillment.ship`、`fulfillment.deliver`|store（Owner读取关联订单）；TENANT_ALL/指定门店|全部HIGH|
|售后队列/审批/拒绝/收货|`aftersale.read`、`aftersale.approve`、`aftersale.reject`、`aftersale.receive_return`|store（Owner读取关联订单）；TENANT_ALL/指定门店|全部HIGH|
|退款查询/核对|`refund.read`、`refund.reconcile`|store（Owner读取关联订单）；TENANT_ALL/指定门店|全部HIGH|
|订单到期扫描/失败重试|`order.expire`、`order.expiry.retry`|store；后台扫描必须只处理获授权门店或独立系统职能|全部HIGH|
|经营总览|`dashboard.read`|commerce_tenant；首批仅TENANT_ALL，聚合源须同时满足相关数据读取能力|HIGH|
|页面定义/渲染/版本、预览、生命周期、执行动作|`ops_page.read`、`ops_page.create`、`ops_page.preview`、`ops_page.submit`、`ops_page.approve`、`ops_page.reject`、`ops_page.publish`、`ops_page.pause`、`ops_page.rollback`、`ops_page.execute`|ops_page；TENANT_ALL；内嵌动作另校验目标业务能力，页面发布不授予执行权|全部HIGH|
|租户事件健康/查询、重试/推进|`event.read`、`event.retry`、`event.pump`|commerce_runtime；TENANT_ALL|全部HIGH|
|停止任务/恢复/重放分类与查询、dry-run/提交/控制|`runtime.read`、`runtime.recover`、`runtime.replay.preview`、`runtime.replay.create`、`runtime.replay.control`|commerce_runtime；TENANT_ALL；底层副作用仍校验其业务授权|全部HIGH|

约束：创建动作没有现存对象ID时，以可信本地租户/既有父门店作为资源，禁止为通过校验伪造不存在对象Facts；若resource_type为merchant等实体但仅TENANT_ALL创建，用无资源实例的能力检查，禁止SPECIFIED_RESOURCES用于创建。单角色不能跨不同resource_type共用一个ScopeRule，岗位以多个固定角色快照组合，能力与范围不得交叉拼接。

动态动作核对（实际业务枚举，不新增动作）：

|路由动作|现有允许值|中央绑定要求|
|---|---|---|
|journeys/{id}/{version}/{action}|submit/approve/reject/publish/pause|逐个绑定同名journey能力；版本/状态迁移仍由JourneyService.change约束|
|journey-instances/{id}/{action}|cancel/retry|journey_instance.control；重试重新验证执行引用，取消不撤销已发权益|
|ops-pages/{id}/{version}/{action}|submit/approve/reject/publish/pause/rollback|逐个绑定同名ops_page能力；rollback会重新发布已暂停版本，不是数据库恢复|
|ops-pages/.../actions/{action}|已发布页面声明的action ID|先查真实页面版本与action，再检查其目标活动/发券/旅程能力，不能只凭任意字符串拼能力|
|segment-runs/{id}/{action}|cancel/retry/retry-announcement|segment.control；retry-announcement仍受相同源结果和当前授权约束|
|coupon-deliveries/{id}/control body.action|CANCEL/RETRY/REVOKE|coupon_delivery.control；REVOKE有补偿副作用，不表示已发优惠券全部可撤回|
|runtime/replays/{id}/control body.action|PAUSE/RESUME/CANCEL|runtime.replay.control；保留ReplayGate对资金/外部副作用的禁止规则|

来源：commerce的JourneyService.change/control、OpsPageService.change/execute、SegmentService.control、CouponDeliveryService.control及EventReplay.control。低代码render当前聚合campaign/journey/budget/coupon/entitlement，不仅execute，其各数据段也必须验证相应read能力；未获权片段不能泄露条数和数据。

本表是已批准边界内的能力目录设计，部署清单需通过CE-02校验。真正发布前必须把218条入口逐项绑定能力，展开`{action}`及请求体control枚举，并核对现有业务状态机支持项；没有枚举绑定的动作默认拒绝，不因本表提到候选名字就新增业务动作。清单上限200能力；发布生成器必须计数和校验，不能截断。

## 不混合的身份与管理边界

- 员工经营端：中央Bearer + X-Tenant-Id；tenant/application/environment来自已验证服务与成员上下文。OA权威员工身份显式映射到商城操作员，不映射为客户MEMBER。
- 客户自助端（/members/me、购物、领取、支付、售后申请、兑换）：原本人数据约束继续生效。若需要客户SSO，另需明确客户身份权威与绑定，不能把OA员工ID直接作为客户member_id。本轮盘点覆盖，自动转换HOLD。
- 平台 `/v1/platform/runtime` 仍为PLATFORM_OPERATOR命名空间；租户TENANT_ALL不是跨租户平台权限。平台SSO需要独立管理应用/职责，不在commerce租户能力中开后门。
- `admin/store-grants` 不转换为新的商城发权能力；已迁移单元旧写路径冻结，发权统一P2委派/角色/Grant和P4审批。权限管理员不是业务超级管理员。
- sandbox支付/退款事实注入，仅本地测试配置+原角色；不得随生产中央能力开放。会员购买过程的内部库存/资金规则保持原Owner约束。

## 请求、事务与失败契约

沿用现有业务URI、DTO、分页/金额字符串、UTC与version字段。中央适配通过独立显式路由/过滤链接管受登记方法+路径，逐用例校验；不以JWT生成长效旧token，不向全部旧admin路径注入ADMIN。需要扩展本地执行者模型以携带受限中央上下文，旧角色校验迁移为业务用例自己的能力门禁，不能全局禁用requireAdmin。

缺失/过期认证401；有效身份无当前能力、跨组织/门店、停用或撤权403；合法资源上下文内不存在404；版本或幂等冲突409；身份/授权依赖不可用503；参数400；持久化异步受理202保持原任务状态语义。拒绝与依赖异常均不得回退旧凭据。响应带追踪号、不含堆栈/Token/图水位。

每个请求重新获取当前授权；查询在SQL分页、统计、聚合之前过滤。资源所属、状态和版本由Owner读真实记录；门店范围不能在分页后过滤。写入前判权，并在Owner事务按tenant/资源关系/version/状态条件写入、核对影响行数、原子记录命令及审计。复用已有Idempotency-Key作用域，接入中央主体后命令归属须加入principal/member/generation防身份替换；同命令不同主体不能读取他人回执。不把网络判权包在长数据库事务中。

撤权拒绝之后的新请求；跨数据库已进入提交的动作仍遵循P3/P5已声明在途窗口，不承诺分布式原子撤销。已发生库存、积分、退款效果必须用业务补偿，不能仅回退版本。

## 后台任务契约

|任务性质|迁移要求|
|---|---|
|用户发起的发券、旅程实例、人群刷新、目录任务、主动重放|提交时持久化中央execution引用、分区、身份代际、授权范围与截止；开始/每批/恢复再次校验。Token不入库。撤权停止未提交批次，不能复活老任务|
|支付核对、退款履行、订单到期、已发生事实的事件投递|属系统履约责任；独立最小服务职能及租户范围，不借某员工Grant。员工离职不能丢弃已经承诺的退款；发起新业务意图仍需用户权限|
|自动积分到期/周期/调度规则|由已批准且有效的版本化业务政策驱动；政策撤销对将来批次的行为需业务决策，保留检查点/审计|
|保留期删除|独立数据生命周期规则，默认关闭；不把本轮“开始执行”视为清空授权|

12条车道逐个登记源表、领取条件、Owner、幂等、预算和撤权行为，未完成前不能接受全商城任务迁移。延续现有有界批次与公平调度，不新建调度平台。

## 已批准业务决定与剩余输入

D1：首批员工岗位模板为会员运营、商品库存运营、营销编辑、营销审批、交易履约、财务核对、租户运维、权限管理员。只产生角色模板，不给任何现有ADMIN自动授予全部模板；每人映射须Owner签字。
D2：会员/营销租户级数据首批仅TENANT_ALL，并通过岗位拆分控制；不虚构门店/部门归属。若必须按门店隔离，需要先建立真实数据归属，不可继续现有列表全租户返回。
D3：已批准先拆权限，沿用现有审批流程。活动/旅程/页面提交、批准、拒绝、发布独立命名；积分人工调整、退款核对、重放为独立高风险能力。本轮不新增OA逐笔审批、额度阈值或同人审批禁止规则；岗位和人员由Owner审核，独立权限不等于已实现双人审批。
D4：客户自助SSO和平台运维SSO不使用OA员工隐式绑定；已批准本轮完成员工运营端，客户/平台另定身份契约。
D5：真实身份/岗位审核人、永久源授权有限有效期、动态商家范围仍沿P6待决；生产目标/SLO/RTO/RPO/Owner沿P7待决。

## 实施次序与验收

CE-00：入口和旧写入/12车道清单（本轮）；CE-01：本契约及业务D1—D4定稿；CE-02：逐方法/动作能力表、角色快照、Owner事实和受限Actor适配；CE-03：库存/门店员工端；CE-04：会员/成长/周期/积分；CE-05：营销/权益/旅程；CE-06：订单/履约/售后/退款；CE-07：页面/事件/运维；CE-08：各车道执行引用/系统职能、增量对账与收缩验收。依次处理共享Actor与schema，不能并行改同一权威入口。

每片同时包含真实API、页面状态、越权/跨租户/撤权/到期/依赖故障、重试/并发/审计、原有会员端与未迁移旧入口回归；使用新建本地隔离库。每个源动作必须有明确中央能力和Owner事实证据才能接管。生产容量未知时只报告本地实测，不补造SLO。
