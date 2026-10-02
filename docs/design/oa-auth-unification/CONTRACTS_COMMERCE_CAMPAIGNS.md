# CE05-CAM 活动与预算员工权限契约

本技术细化消费已批准的 CONTRACTS_COMMERCE_EXPANSION（D1—D4），沿用 CampaignApi/CampaignService、CampaignFundingApi/CampaignFundingService、CampaignMapper/BudgetMapper 与原 HTTP。资源 campaign；八个活动能力 campaign.read/create/preview/submit/approve/reject/publish/pause 及独立 budget.read 全部 HIGH、TENANT_ALL。没有预算调整新接口，不增加OA逐笔审批、额度阈值或同人审批禁止规则，不给既有ADMIN自动授权。

| ID | 结果 | Needs / Owner | 范围与验收 | 状态 |
|---|---|---|---|---|
| CE05-CAM0 | auth稳定campaign常量与9个有限执行能力 | 已交付CE05-A0/A1；auth protocol/governance | HUMAN/60秒，类型/能力精确；3集合许可、6实际版本动作；拒绝交换/未知/伪门店/部分范围/非正内容版本；原路径、代际、撤权重授与期限；真实PG/图、SDK及旧能力回归 | DONE（本地） |
| CE05-CAM1 | commerce独立CAMPAIGN族与实际Owner/同事务审计 | CAM0验证及Git交付；commerce runtime/marketing/app | 9能力独立、原回执前范围/期限/路由；实际版本/状态锁区分、预算列表、实际预览无预占；创建/状态变更与身份审计同事务、SQL故障回滚、跨租户/原键/撤权/STOPPED/503、旧订单预算履约兼容；真实MySQL及跨进程 | TODO |
| CE05-CAM2 | 固定SSO活动/预算入口及完整动作反馈 | CAM1验证交付；frontend/app | 沿现有Craft/AntD，真实目录与独立预算、创建/预览/审批/发布/暂停权限；结构化字段及实际预览，不编造选项；每个未知命令原键/体恢复、当前内容版本与锁版本展示；1440/390关联表单/预览/反馈/确认实际查看、真实PKCE/401/403/503/SQL | TODO |

三个切片串行；不新建工作树或子Agent，不改变模块/数据权威或基础设施。CAM2提示入口与最终界面配方在CAM1真实契约可用后按既有前端设计细化，不能在CAM0编造JSON或开发UI。

## CAM0 有限执行协议

沿用 Issue/Reference/ScopeCheck/Check 与既有期限/上下文/路径交集，不加JSON字段或迁移。9能力签发仅HUMAN、最多60秒，能力以调用应用ID精确前缀加有限后缀绑定campaign。campaign已是TENANT_ONLY；稳定常量替代协议中的字面值，不制造门店归属范围。

目录 campaign.read、未来对象 campaign.create、预算版本列表 budget.read 仅完整租户集合许可，没有本片已有单对象公开读取入口。preview/submit/approve/reject/publish/pause 对已存在campaignId及不可变正content.version调用真实Owner Check；Facts不允许门店/成员/部门等伪归属字段。ScopeCheck可用于独立资格提示；不返回可替代真实提交判权的许可。每个能力独立，不可将create/read/approve等引用换成另一个动作。

资源事实的resourceVersion是实际不可变content.version，不能用lockVersion替代。lockVersion是每次审批/发布/暂停递增的业务并发条件，继续通过原expectedVersion请求校验。撤权、目录/身份代际改变、调用方/应用/环境改变及期限过期使旧引用拒绝；撤权重授不会复活原Grant路径。活动内容已提交或既有订单履约不因此回退。

## CAM1 真实业务边界

- GET/POST `/v1/admin/campaigns`：列表按活动ID稳定游标取每个tenant+campaignId最新content.version；创建沿原Draft字段/金额精确值/时效/可信规则校验，实际requireActive门店确定merchant，不授予商家或门店目录read。
- POST `/v1/admin/campaigns/{id}/{version}/preview`：仅campaign.preview，读取实际目标版本、真实本租户会员/活动门店/已发布SKU价格及真实成长事实；原1—100项、每项/合并数量上限10000、竞争预览/模拟时间含义保持。不存报价、不预占库存或预算、不发权益；无需隐含member.read/catalog目录read/campaign.read。
- POST `/v1/admin/campaigns/{id}/{version}/{submit|approve|reject}`：三个独立能力沿原受治理状态迁移DRAFT→IN_REVIEW、IN_REVIEW→APPROVED/REJECTED。无policy旧版不允许审批；精确expectedVersion检查与状态冲突不变。
- POST `/v1/admin/campaigns/{id}/{version}/{publish|pause}`：两个独立能力。锁定实际租户活动全部版本、真实目标content.version，发布唯一性/原状态和expectedVersion保持；有policy须APPROVED/PAUSED，激活每次核对有效期及固定规则/人群/权益/券引用；暂停只影响新报价，不改历史快照。
- GET `/v1/admin/campaign-budgets`：仅budget.read，所有内容版本余额按实际budgetId稳定游标，不隐含campaign.read；无新增调整入口。

中央写路径先复核当前接管路由/完整范围/许可期限，再处理原幂等回执；稳定主体进入中央命令input，HTTP执行nonce不进入hash。创建的活动/预算/command/employee_command_identity同事务；状态变更、唯一发布切换、command和实际campaignId/content.version身份审计同事务，审计失败整体回滚。无policy旧版发布及旧模式命令输入保持原兼容，不借权限迁移改业务状态机。

Marketing资产固定引用由原可信内部接口检查，不通过员工目录read形成隐含依赖。CampaignFundingService.commitment/reserve/confirm/release属于原客户/订单事务：校验实际活动版本/门店、资方快照与数据库额度条件，既有系统确认/取消不能因员工撤权失效。仅员工budgets入口接管；candidates报价/客户行为不改员工身份。

V49—V63已应用不可改；CAM1只追加新迁移，序号实施前再核对。不自动切换真实租户或原8602/OA。新协议不扩大旧资源权限，SDK来源在CAM0交付后明确更新。

## 影响与证据来源

- auth：ScopeDtos、ScopeResourceBindings、ExecutionAuthorization、ExecutionAuthorizationIT四文件；CAM0无Runtime/DDL/依赖变更。
- commerce：CampaignService真实Owner和命令、CampaignFundingService员工列表、EmployeeAccess/EmployeeAuthority族与中央Filter；CampaignMapper/BudgetMapper数据由marketing-runtime维护。
- 事实依据：CampaignApi/Draft/View/Preview、CampaignService/create/list/preview/review/change/validatePublication，CampaignMapper.xml锁和唯一发布，CampaignFundingService的MANDATORY订单事务；既有两个Controller路径。以上为现有行为核对，不把设计当已实现。

当前CAM0本地验证DONE、Git/CI待交付，CAM1/CAM2未实现；A2精确CI仍在运行。整个CE05—08及auth菜单资源展示目标保持，生产目标/Owner/映射与实际部署授权沿原HOLD。


## CAM0 最终本地验证（2026-10-01）

四源码SHA256与验证版一致；252单元全PASS，专用PG d57b5e71bac5 与既有P3 SpiceDB执行引用15方法PASS，新增方法逐一验证9独立能力的真实Grant/投影/签发/集合/实际对象/其他8权限交换拒绝、SERVICE和超过60秒拒绝、零/负内容版本及类型/租户/伪门店拒绝。原撤权重授、代际、目录、期限及其他能力回归保持。仅测试fixture调用方使用Mock CallerService配置，真实身份/Grant/SQL与判权均通过实际PG和SpiceDB。

SDK Boot4兼容1方法PASS；最后强制打包安装（-DskipTests，复用已通过且源码不变的252单元和15集成证据）PASS；server/admin分别320/349类、各42资源、各3个嵌套模块与当前reactor逐字节一致。自有PG已finally停止，卷/私密配置/证据保留，不停共享图或业务环境。

hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：无阻断；既有Java formatter与静态分析未配置。无新迁移/依赖/JSON字段，可见UI N/A。独立implementation-validation COMPLETED/PASS，CAM0 DONE（本地）；CAM1/CAM2尚未实施，不能将协议通过当商城业务/页面通过。

私密证据在 `.local/governance/commerce-contracts/campaigns-core-{install,integration,boot4,final-install}.log`、`campaigns-core-{source-sha256,runtime-fence,hygiene,test-result}.json`。整个目标仍active，下一步正常Git/精确CI后CAM1。
