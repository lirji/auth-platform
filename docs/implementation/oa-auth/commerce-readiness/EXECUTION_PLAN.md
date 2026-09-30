# 商城剩余接入执行记录

2026-09-29；用户已批准剩余8步，并进一步选择本轮扩展其他模块、先补能力与权限契约。

|步骤|当前状态|产物/出口|
|---|---|---|
|1 入口、旧授权写入与后台盘点|完成首轮|HTTP_INVENTORY.md 218条；下方缺口矩阵；详细动作绑定归CE-02|
|2 已批准CATALOG完整页面接入|本地验证通过|commerce任务分支feat/central-catalog-entry；复用P6真实API，不影响原运行商城|
|2a 新增模块能力契约|BUSINESS_APPROVED|CONTRACTS_COMMERCE_EXPANSION.md；D1—D4已明确，继续CE-02|
|3 迁移审核/增量准备|本地验证通过|prepare-review；本地新只读快照与旧快照比较；真实OA映射未签字|
|4 目标运行配置|目标待定|TARGET_ACCEPTANCE.md已整理配置/容量/恢复/灰度/退出输入；不可填造生产连接/Owner|
|5 目标规模与恢复验收|目标待定|本地P7已有600请求及恢复证据，本轮不重复当成生产接受|
|6 P7-07发布评审|BLOCKED|真实映射、完整接入、目标/SLO/Owner尚未齐备|
|7 P7-08实际发布观察|BLOCKED|目标及实际部署授权未指定|
|8 旧授权收缩|BLOCKED|全量接管/影子差异清零/旧写入和任务关闭/Owner签字后才执行|

## 页面、API与旧写入缺口

|页面/作业|当前数据与写入|中央状态/接下来|
|---|---|---|
|operations/products、collaboration/products|CentralProducts/ScopeController；商品读改和限时导出|既有P5完成；保持独立能力|
|旧skus页：SKU/SPU、展示、类目模板、条码、渠道价、批量计划|ProductOperations、CatalogMerchandising、CatalogScheduling；6个operations API族|后端P6完成；本轮增独立SSO壳/CATALOG请求上下文|
|商家/门店/经营授权|CommerceController、StoreAccessController；StoreAccessService/Mapper|读写商家门店待CE-03；旧store-grants不新增中央写权限，改走治理委派|
|库存额度|OrderController → InventoryApi/Mapper|待独立read/receive+门店Owner约束|
|会员档案/成长/标签/行为/周期/积分/积分商品|Member*Controller、PointOfferController|现有ADMIN/MEMBER分离；员工能力待CE-04，不将OA成员当客户会员|
|活动/效果/规则/预算/优惠券/发券/权益/人群/旅程|Marketing*、Campaign*、Segment/Journey/Coupon/Entitlement Controller|待CE-05；发布审批、发放、调整及后台引用分开|
|订单/支付/履约/售后/退款|Order/Payment/Console/Aftersale Controller|待CE-06；由真实订单关联门店，资金操作独立|
|低代码页面/事件/运行恢复/重放/总览|OpsPage/Payment/RuntimeRecovery/Dashboard|待CE-07；页面动作还检查目标业务能力，总览聚合不能泄露未授权域|
|客户自助商城/钱包/购买/售后申请|MEMBER_PATHS+各Owner本人绑定|本轮盘点，身份权威需另定；员工SSO不自动接管|
|平台监控、sandbox事实注入|PlatformRuntimeController；Payment/Aftersale sandbox|平台角色独立；生产清单不发布sandbox能力|

旧权限写入：platform_credential的本地配置/seed/测试工具、store_operator_grant的API/StoreAccessService/Mapper/seed以及直接SQL；准确P6来源见原P6-01_INVENTORY，不重复改OA写入。当前catalog_authority_route只覆盖CATALOG，不能当成所有新能力的迁移状态；扩展需按tenant+能力族持久化接管状态、旧写锁及代际，CE-02定schema，不能复用单CATALOG状态放行其他域。

12条后台车道见EventWorker：payments/refunds/orders/events/segments/journeys/cycles/points/deliveries/catalog-jobs/replay/retention。仅catalog-jobs已有P6中央引用实测；其余按新契约区分用户意图和既有系统履约，不以员工撤权丢弃已承诺退款。

## 已批准CATALOG页面补充契约

固定`GET /operations/catalog?tenant_id=<UUID>&store_id=<ID>`；OIDC回调允许回此同源路由，仅保留tenant/environment/store_id，不携Token。静态壳不授权；后端6族API和P6服务端校验不变。

因为`commerce.store.read`与`commerce.catalog.operate`独立，页面不把全量门店列表自动赋给CATALOG。首版通过已知门店编号或门店深链进入，实际数据始终由后端按资源重新判权；没有自动授权门店选择器，不宣称该能力已完成。

复用原商品经营Tabs/表格/编辑Modal及现有Ant Design主题。React RequestContext只向本页面的3个经营组件提供中央token+tenant，不写旧控制台全局凭据。切门店重建组件，401隐藏业务页并提供重新登录，403保留明确拒绝，503显示服务不可用；POST未知结果保留原幂等键。原P5商品页仍保持原能力与接口。

验收：1440/390视口、真实API读/编辑提交、跨门店/跨租户、退出和401、真实撤权与依赖停止；完整Java回归；私有截图/Token不提交。原商城8602不切换、不重启。

验证结果见[TEST_RESULT](TEST_RESULT.md)：Java388通过/5跳过、迁移22项、隔离34项通过；Git/CI交付记录另记。

2026-09-29：用户D1—D4已批准。CE-02-D逐入口/受限Actor/分族接管契约完成，真实源码覆盖218入口，122能力、34角色快照，离线9项测试通过；见[CE02_TEST_RESULT](CE02_TEST_RESULT.md)。继续CE-02-A有限资源协议、CE-03-I库存用例，未发布新能力。

CE-03-I库存read/receive后端已通过本地完整/真实中央联调，见[CE03结果](CE03_INVENTORY.md)；下一片CE-03-U员工页面，尚未完成全模块接入。

2026-09-29 CE03-U本地DONE：真实库存SSO页/动作提示/未知结果幂等恢复；398项Java393PASS/5skip，隔离58项PASS含库存9条/原CATALOG9条浏览器细分，1440/390截图已查看。见commerce-readiness/CE03_INVENTORY.md失败历史和限制。Git/CI交付进行中；下一步CE03-D集合/创建协议及商家门店接管，其他模块未完成。

CE03当前状态（2026-09-29）：I/U/D0/D1已正常合并推送，两仓远程CI SUCCESS。D1 SDK固定来源遗漏由commerce49274a8修复，CI36667650544 SUCCESS；auth378313c CI36667456414 SUCCESS，失败36667458010保留。D2目录员工页本地DONE：402项397PASS/5skip、99项真实隔离检查（目录11条浏览器、库存/CATALOG各9条），1440/390及错误/创建截图已查看，详见commerce-readiness/CE03_DIRECTORY.md。D2 Git交付进行中；下一READY为CE04会员纵向切片技术细化，CE04—08未实施，生产输入HOLD不变。

CE04最新：P0 auth dd07223已推送，CI36668768692 SUCCESS（含D2基线）；D2 commerce CI36668436980 SUCCESS，auth旧run36668435670取消。P1本地DONE：406项401PASS/5skip、实际隔离114PASS（rehearsal-451c622849b4）、36项工具/223入口契约及hygiene无阻断，见CE04_MEMBER.md。下一CE04-P2 READY，后续会员和CE05—08未完成；原63节点DAG与生产HOLD不变。

CE04最新：P1 auth2cb8c11/commerce1bc81f2已合并推送，两仓CI36669783915/36669785165 SUCCESS。P2本地DONE：407项402PASS/5skip、真实隔离137PASS含会员12条浏览器及共享壳回归、当前截图已查看；36项Python/227入口/hygiene无阻断，见CE04_MEMBER。下一CE04-G成长协议/Owner/UI细化；其余会员和CE05—08未完成。

CE04-P2已普通合并推送auth21380a4/commerce359ac02，CI36670574749/36670578849待查。CE04-G0本地DONE（五成长执行能力）：既有251单测+新增Owner1、5真实PG+graph IT、SDK/Boot4/package/hygiene通过，见CE04_MEMBER与CONTRACTS_COMMERCE_GROWTH。下一G1 READY，商城成长业务/API/V53尚未实施。

CE04-G1本地DONE：411项406PASS/5skip、真实成长中央联调151PASS（fb6d4a99a58b），V53已应用不可改历史；五能力/独立路由/真实Owner/幂等/事务审计均验证。G0 CI36670815592及P2 commerce36670578849 SUCCESS。G1 Git交付中，下一G2页面与浏览器；发券500ms预算时序敏感测试的失败历史见CE04_MEMBER，不隐去。

CE04-G2本地DONE：413项408PASS/5skip、真实隔离183PASS（324fea9731d5），成长11/会员12/目录11/库存9/CATALOG9浏览器细分检查，当前1440/390/表单/钱包/冲突/未知/重算/停机截图已查看；36项工具、231入口及hygiene无阻断。原公平性回归暴露的游标缺陷单独commerce45b74ee修复，旧版确定性FAIL/新版PASS与原公平性检查保留，详见CE04_MEMBER和commerce独立修复说明。下一CE04-T标签技术细化，其他会员与CE05—08仍未完成。

CE04-G2已推送auth8351043/commerce646ebd5；CI待查。CE04-T0本地DONE：标签3有限执行能力、define scope-only、252单元/真实PG+graph6项/SDK Boot4 package/hygiene通过，见CE04_MEMBER与CONTRACTS_COMMERCE_TAG。下一T1真实Owner/标签字典实际审计目标/V54，尚未实施。
