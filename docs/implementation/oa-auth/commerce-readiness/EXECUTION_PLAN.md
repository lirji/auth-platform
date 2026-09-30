# 商城剩余接入执行记录

CE05-A0本地DONE：稳定audience类型、read/create两独立有限集合能力；完整252单元、真实自有PG86a26d467cc2/SpiceDB共14项ExecutionAuthorizationIT、Boot4 1项及最终forceCreation install通过，两个运行Jar内嵌依赖和四源码摘要一致。hygiene无阻断，既有Java formatter/静态分析限制保留，无新迁移，自有PG已finally停止。A0 Git交付中，下一A1/A2与其他CE05—08未完成。R2已推送auth70b6228/commerce654d903，commerce CI36706768255 SUCCESS，auth CI36706764297仍运行中；保留commerce现有refactor/b-console-experience分支，待确认是否并行任务，原生产2HOLD不变。

CE05-R2本地DONE：固定SSO规则目录/创建/发布三Tab、两个独立hint与两个原意图分别恢复；完整469项464PASS/5既有skip、规则8专项全PASS，最终真实200dbeb9873c（子网10.254.114.0/24）609PASS，规则12条浏览器与全部既有员工页回归通过。42工具/259入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce10源码摘要一致；实际8条规则身份审计、UI创建/发布各1次，同键不重复，旧1=PUBLISHED/最新2=DRAFT。1440/390表单/目录、409/未知/成功/503已查看；稳定布局后的正文与PNG均390，表格内部横滚。无新迁移，V49—V62不可改；下一CE05-A人群快照，其余CE05—08和原生产2HOLD未完成。

CE05-R1本地DONE：RULE独立接管族、规则read/create/publish、真实不可变版本锁、原回执前权限复核与同事务审计，V62已应用不可改。完整468项463PASS/5既有skip，规则7项全PASS；最终真实7b1ff652c183（10.254.110.0/24）518PASS，无浏览器。42工具/256入口/122能力/34角色、两仓hygiene及auth2/commerce12源码摘要一致。下一R2规则员工页；其余CE05—08与原生产2HOLD未完成。

CE05-R0本地DONE：marketing_rule稳定类型和read/create/publish三个独立有限能力；创建只集合，读取/发布绑定实际资产版本。完整252单元、真实自有PG5ff5f702ccbc/SpiceDB共13项ExecutionAuthorizationIT、SDK Boot4和最终forceCreation install通过，两个运行Jar嵌套依赖及4源码摘要一致。hygiene无阻断，Java formatter/静态分析限制保持。无新迁移，自有PG已finally停止；下一R1商城RULE族/实际Owner/事务审计，R2与其余CE05—08未完成。

CE05-E2本地DONE：权益定义/实例两个固定SSO页、独立create/resolve提示和安全重试。完整461项456PASS/5既有skip、权益8项全PASS；真实0078780a5d6b（10.254.108.0/24）563PASS，其中两个权益页各10条浏览器检查，含所有既有员工页回归。37工具/256入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce11源码摘要一致。1440/390目录/表单及409/未知/取消退出/成功/503截图已查看。恰8身份审计、UI定义和两个补偿各1条；真实客户兑换/核销和撤权后履约兼容。无新迁移，V49—V61不可改。下一CE05-R营销规则细化；其余CE05—08及原生产2HOLD未完成。

CE05-E1本地DONE：ENTITLEMENT_DEFINITION/ENTITLEMENT两族、四独立权限、真实grantId/version、旧回执前路由/事实/期限复核与同事务身份审计，V61已应用不可改。完整460项455PASS/5既有skip，新增7项全PASS；真实独立58007c181423（10.254.107.0/24）477PASS，无浏览器。37工具/252入口/122能力/34角色、两仓hygiene及auth1/commerce8源码摘要一致。下一E2权益定义/实例页面；其余CE05—08和原生产2HOLD未完成。

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
|商家/门店/经营授权|CommerceController、StoreAccessController；StoreAccessService/Mapper|CE03目录API/页面已交付；旧store-grants不新增中央写权限，改走治理委派|
|库存额度|OrderController → InventoryApi/Mapper|CE02独立read/receive、门店Owner及员工页已交付|
|会员档案/成长/标签/行为/周期/积分/积分商品|Member*Controller、PointOfferController|CE04各员工API/页面已交付；客户身份保持独立，不将OA成员当客户会员|
|活动/效果/规则/预算/优惠券/发券/权益/人群/旅程|Marketing*、Campaign*、Segment/Journey/Coupon/Entitlement Controller|CE05进行中：券定义CD0/CD1已交付，CD2本地DONE；其他待实施，审批/发放/调整/后台引用分开|
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

CE04-T1本地DONE：完整417项412PASS/5skip与最终标签5项窄测试（含后补64上限）通过，真实隔离c1893723fb4a共182PASS，无浏览器；V54已应用不可改。36项工具/231入口/hygiene无阻断，见CE04_MEMBER。下一T2独立标签页，其他会员与CE05—08仍未完成。

CE04-T2本地DONE：419项414PASS/5skip、真实隔离fdda68438ef6共221PASS（标签10条浏览器，含所有既有员工页回归），1440/390及错误/重试/撤销/停机截图已查看；36工具/234入口与hygiene通过。T1两仓CI36674310063/36674311269 SUCCESS。下一CE04-B行为技术细化，其他会员及CE05—08仍未完成。

CE04-B0本地DONE：3行为执行能力，重建集合许可/真实PG+graph7项/252单元/SDK Boot4 package/hygiene通过；T2已推送两仓，CI待查。下一B1实际会员Owner和最小订单来源重建，V55尚未实施。

CE04-B1本地DONE：424项419PASS/5skip与真实无浏览器31b246afdc39共221PASS，V55不可改历史；36工具/234入口/hygiene通过。两次脚本失败及修复证据见CE04_MEMBER。B0 CI36675295449 SUCCESS。下一B2独立行为员工页，其他会员及CE05—08未完成。


CE04-B2本地DONE：425项420PASS/5skip、真实隔离1d02cf3bcffd共267PASS（行为11条浏览器与全部既有员工页回归），当前1440/390截图已查看；36工具/237入口/hygiene无阻断。B1两仓CI36676623565/36676628487 SUCCESS。下一CE04-C周期/权益有限协议、实际Owner与系统履约兼容；其余会员及CE05—08未完成。


CE04-C0本地DONE：七能力有限执行组合、252单元/真实PG+graph8项/SDK Boot4/package/hygiene通过。B2已推送auth e568df0 / commerce 29c4fd7，CI待查。下一C1两个独立接管族、真实Owner与系统履约兼容，V56尚未实施。


CE04-C1本地DONE：431项426PASS/5skip、最终枚举版新增6项/构建/hygiene通过，真实无浏览器8119c3656bbb共284PASS含撤权后系统权益履约；V56不可改历史。36工具/237入口通过，C0 CI36677995958 SUCCESS。下一C2周期/周期权益两页及四独立提示，其余会员与CE05—08未完成。


CE04-C2本地DONE：完整432项427PASS/5skip，真实隔离27a84a8559ec共341PASS含周期14条浏览器与全部既有员工页回归，当前1440/390截图已查看；36工具/243入口/hygiene通过。首轮事件固定推进次数不足及有界修复见CE04_MEMBER；C1两仓CI36679539578/36679555814 SUCCESS。下一CE04-PTS积分技术细化，随后CE04-O积分商品，其他会员及CE05—08未完成。


CE04-PTS0本地DONE：252单元及自有PG502110bc4f62/SpiceDB共9项执行引用集成、SDK Boot4/package/hygiene通过，五积分组合不新增协议格式。C2已推送auth02cfdd7/commerce3c8a689，CI另查。下一PTS1真实积分Owner/审计和V57，PTS2与积分商品及CE05—08未完成。


CE04-PTS1本地DONE：完整438项433PASS/5skip及真实隔离0c2048fafb9f共329PASS，无浏览器；V57已应用不可改。36工具/243入口/最终hygiene和摘要通过，见CE04_MEMBER。PTS0 CI36681665256 SUCCESS；C2 commerce原版CI重跑中。下一PTS2积分员工页，CE04-O/CE05—08仍未完成。

C2 commerce CI36681436387第二次原版运行SUCCESS；未修改并发测试或预算，首次屏障超时证据保留。


CE04-PTS2本地DONE：完整439项434PASS/5skip，真实b5f6eeb0aebf（子网98）397PASS含积分11条浏览器及全部已交付员工页回归，实际截图/SQL审计/源码摘要一致；36工具/247入口/hygiene通过。PTS1两仓CI36682762638/36682764819 SUCCESS。下一CE04-O0积分商品协议，再O1/O2和CE05—08；原生产2HOLD不变。


O0最终本地DONE：全仓install包含252单元PASS、SDK及两个运行Jar打包安装成功；独立Boot4兼容测试PASS，自有PG c7f5dd5f5958/真实SpiceDB共10项执行引用集成PASS。hygiene无阻断、Java formatter/静态分析限制保持，4文件offers-core-source-sha256摘要一致。自有PG已finally停止，证据保留；未新增服务或迁移。下一O1真实积分商品Owner/命令审计和V58，O2/CE05—08仍未完成。


CE04-O1本地DONE：445项440PASS/5skip，最终真实334c444f50a5（子网101）376PASS；37工具/247入口/hygiene与摘要一致。V58/V59不可改，初次审计约束遗漏及运行制品/证据文件修复见CE04_MEMBER。O0 CI36684307399 SUCCESS。下一O2员工积分兑换商品页面，CE05—08和生产2HOLD不变。


CE04-O2本地DONE：446项441PASS/5skip，真实7fdcde67f634/子网103共450PASS含商品10条浏览器与全部既有员工页回归；1440/390截图、恰6条身份审计、客户兑换和源码摘要一致，37工具/250入口/hygiene通过。O1两仓CI SUCCESS。下一CE05-CD券定义技术细化/有限协议/Owner/UI；CE05—08及原生产2HOLD仍未完成，详见CE04_MEMBER。

CE05-CD0本地DONE：两券定义有限集合能力，252单元、真实自有PG8908d6c82bcf+授权图11方法、SDK/Boot4/install/hygiene及四源码摘要通过，见CE05_MARKETING与CONTRACTS_COMMERCE_COUPON_DEFINITIONS。O2已推送authc823750/commercec027daa，CI待查。下一CD1真实业务门禁与审计，CD2及其他CE05—08未完成。

CE05-CD1本地DONE：452项447PASS/5skip、最终真实aa92413e7500/子网105共415PASS，三定义审计/两实际发券/余额100与原键一致；37工具/250入口/hygiene和摘要通过，V60不可改。失败夹具/脚本路径证据见CE05_MARKETING；下一CD2页面，其他CE05—08及生产2HOLD不变。


CE05-CD2本地DONE：固定SSO券定义目录/创建两Tab、独立创建提示；完整453项448PASS/5既有skip，券定义7项全PASS；真实隔离83d4ad742f53（10.254.106.0/24）493PASS，其中券定义10条浏览器行为，含全部既有员工页回归及O2标识64字校准。37工具/252入口/122能力/34角色、build/Prettier/两仓hygiene与auth4/commerce11源码摘要一致。当前1440/390目录/表单、409、未知结果、退出确认、成功/503截图已查看。5条实际定义身份审计、UI两定义各1条，实际公开领取/受控兑换共2次且余额100。Java formatter/静态分析限制保留，无新迁移；V49—V60不可改。Git交付中，下一CE05-E权益定义/实例技术细化；其他CE05—08与生产2HOLD未完成。


CE05-E0本地DONE：权益定义/实例两类型和四独立有限能力，定义只集合、实例绑定真实grantId/version；全仓252单元、真实自有PG b8c52c20066d/SpiceDB的ExecutionAuthorizationIT共12方法、SDK Boot4与最终forceCreation install均PASS，两个运行Jar嵌套依赖和4源码摘要一致。hygiene无阻断，原Java格式/静态分析限制保留。自有PG已finally停止，未新增迁移或基础设施，下一E1商城两个族/真实Owner/事务审计，E2页面与其余CE05—08未实施。
