# CE05-CAM 活动与预算员工权限契约

本技术细化消费已批准的 CONTRACTS_COMMERCE_EXPANSION（D1—D4），沿用 CampaignApi/CampaignService、CampaignFundingApi/CampaignFundingService、CampaignMapper/BudgetMapper 与原 HTTP。资源 campaign；八个活动能力 campaign.read/create/preview/submit/approve/reject/publish/pause 及独立 budget.read 全部 HIGH、TENANT_ALL。没有预算调整新接口，不增加OA逐笔审批、额度阈值或同人审批禁止规则，不给既有ADMIN自动授权。

| ID | 结果 | Needs / Owner | 范围与验收 | 状态 |
|---|---|---|---|---|
| CE05-CAM0 | auth稳定campaign常量与9个有限执行能力 | 已交付CE05-A0/A1；auth protocol/governance | HUMAN/60秒，类型/能力精确；3集合许可、6实际版本动作；拒绝交换/未知/伪门店/部分范围/非正内容版本；原路径、代际、撤权重授与期限；真实PG/图、SDK及旧能力回归 | DONE（含Git/CI） |
| CE05-CAM1 | commerce独立CAMPAIGN族与实际Owner/同事务审计 | CAM0验证及Git交付；commerce runtime/marketing/app | 9能力独立、原回执前范围/期限/路由；实际版本/状态锁区分、预算列表、实际预览无预占；创建/状态变更与身份审计同事务、SQL故障回滚、跨租户/原键/撤权/STOPPED/503、旧订单预算履约兼容；真实MySQL及跨进程 | DONE（含Git/CI） |
| CE05-CAM2 | 固定SSO活动/预算入口及完整动作反馈 | CAM1验证交付；frontend/app | 沿现有Craft/AntD，真实目录与独立预算、创建/预览/审批/发布/暂停权限；结构化字段及实际预览，不编造选项；每个未知命令原键/体恢复、当前内容版本与锁版本展示；1440/390关联表单/预览/反馈/确认实际查看、真实PKCE/401/403/503/SQL | DONE（本地；Git/CI待交付） |

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

当前CAM0本地验证DONE、Git已交付b311e4c、CI36955364612 SUCCESS已核验，CAM1本地验证DONE，Git/CI待交付，CAM2未实现；A2两仓精确CI已SUCCESS。整个CE05—08及auth菜单资源展示目标保持，生产目标/Owner/映射与实际部署授权沿原HOLD。


## CAM0 最终本地验证（2026-10-01）

四源码SHA256与验证版一致；252单元全PASS，专用PG d57b5e71bac5 与既有P3 SpiceDB执行引用15方法PASS，新增方法逐一验证9独立能力的真实Grant/投影/签发/集合/实际对象/其他8权限交换拒绝、SERVICE和超过60秒拒绝、零/负内容版本及类型/租户/伪门店拒绝。原撤权重授、代际、目录、期限及其他能力回归保持。仅测试fixture调用方使用Mock CallerService配置，真实身份/Grant/SQL与判权均通过实际PG和SpiceDB。

SDK Boot4兼容1方法PASS；最后强制打包安装（-DskipTests，复用已通过且源码不变的252单元和15集成证据）PASS；server/admin分别320/349类、各42资源、各3个嵌套模块与当前reactor逐字节一致。自有PG已finally停止，卷/私密配置/证据保留，不停共享图或业务环境。

hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：无阻断；既有Java formatter与静态分析未配置。无新迁移/依赖/JSON字段，可见UI N/A。独立implementation-validation COMPLETED/PASS，CAM0 DONE（本地）；CAM1/CAM2尚未实施，不能将协议通过当商城业务/页面通过。

私密证据在 `.local/governance/commerce-contracts/campaigns-core-{install,integration,boot4,final-install}.log`、`campaigns-core-{source-sha256,runtime-fence,hygiene,test-result}.json`。整个目标仍active，下一步正常Git/精确CI后CAM1。


## CAM1 实施检查点（历史）

2026-10-01：CAM0精确CI36955364612 SUCCESS后，commerce原目录feat/central-campaign-operations按本契约实施。新增9有限CAMPAIGN能力，6已有动作绑定实际正content.version；目录/预算独立返回前复核，预览真实目标及结果返回前再次判权；原状态机/expectedVersion/不可变资产及客户预算履约保持。V64新增可空resource_version兼容旧审计，campaign审计必须实际正内容版本且无store_id；没有自动切换真实租户。SDK固定完整b311e4c5b3a89a41bf7cb939229b2a5400b6a2b9。V49—V64现已在专用测试库应用，不得修改已执行迁移。

11真实MySQL专项PASS（含81独立HTTP边界、内容版本与锁版本、实际SQL审计失败回滚、原键、读取上下文、锁等待后期限、固定引用新鲜度与订单预算确认/释放）；完整69 XML共489测试，484PASS/5既有skip/0FAIL/ERROR。最终forceCreation with-ui包17嵌套模块/661类/510资源/45前端文件与当前编译制品字节一致；10源码/测试/SDK引用摘要保存。auth --campaigns串行真实演练47a2d78d80c5/子网118进行中，尚未终态，不把上述局部证据当CAM1 DONE。28P6工具/9契约及两仓hygiene无阻断；既有Java formatter/静态分析限制保留。

第一次跨进程命令在任何隔离资源创建前被protocol归档字节栅栏拒绝，原因是SDK安装更新了模块归档；只强制重打包auth运行归档，4验证版源码不变，拒绝记录保留。当前真实演练仍需验证中央身份/Grant/审批/撤权/STOPPED/503与精确SQL，Git/CI未交付。


## CAM1 最终本地验证

CE05-CAM1本地DONE：9独立活动/预算能力、6实际正内容版本动作、独立目录/预算与真实预览已接入；V64版本审计与活动/预算/状态/原回执同事务。11真实MySQL专项（含81权限HTTP边界/实际SQL故障/锁等待期限）、完整489项484PASS/5既有skip、9契约/28工具及两仓hygiene无阻断。最终10源码/SDK摘要与17嵌套模块/661类/510资源/45前端文件/复制JAR一致。真实47a2d78d80c5/子网118已exit0，645检查点PASS（79活动标签），SQL再次核验13身份审计/实际内容版本1:3、7:6、8:4，v7 PAUSED/lock6、v8 PUBLISHED/lock3，客户订单在STOPPED后正常释放v8预算。自有进程/PG已停止，数据证据保留。Git/CI待交付；CAM2和其余CE05—08/auth接入资源展示未完成，原8602/OA及生产2HOLD保持。

implementation-validation COMPLETED/PASS，必需后端验收全部通过；源码/测试/SDK引用10摘要未变。演练复制JAR为889b6fd5c6aec4c89171963636ba21f36b7a6b2c3c9efa1119c178e5b8b41512，实际运行档案与最终构建一致。旧所有者接口/订单预算确认和释放仍按可信事务执行，员工撤权不取消历史承诺；CAM2可见界面尚未实施，本片UI N/A。保留首轮制品字节拒绝及专项启动方式错误，未把失败命令算作PASS；真正通过的是11MySQL/489全仓及645实际跨进程。限制：Java formatter/静态分析未配置、5既有skip、未生产部署。

私密结果campaigns-owner-test-result.json、sql-evidence.json、runtime-fence.json、auth-runtime-fence.json、10源码/演练源码摘要与两仓hygiene；所有数据/测试容器卷/私密身份证据保留。原V49—V64已在专用测试库应用不可改。


## CAM2 页面与提示技术细化（门禁满足，实施中）

CAM1本地必需验证已PASS、commerce604023206d18feb1c23bec91069e259333160a93/auth356d8b909485c774ac1d80bf45e7bb9b3dc5ad0a正常合并推main，精确CI36957875268/36957876071均SUCCESS，依赖门禁满足。以下细化绑定上述实际接口/DTO，CAM2已完成本地实现与真实编译页面验收，Git/CI待交付。

### 固定入口与信息结构

- `/operations/campaigns?tenant_id=...`：沿现有Craft中央壳，活动目录、创建草稿、版本操作三Tab。目录只请求campaign.read，按实际campaignId稳定游标列最新不可变content.version；显示实际名称/门店/状态/内容版本/状态锁版本及有效期。不得把旧内容版本从最新列表猜出来。
- `/operations/campaign-budgets?tenant_id=...`：独立budget.read，显示全部实际内容版本预算的budgetId、campaignId、version、cap、held、spent，按budgetId分页；不请求campaign.read，也没有预算调整动作。
- 版本操作支持从真实目录行填充，也支持只有写/预览权限时手填实际campaignId、正content.version和非负expectedVersion。内容版本用于定位事实，expectedVersion明确标注为状态锁版本；修改目标不延用其他对象的未知命令。
- 无读取资格时该目录显示明确权限反馈；动作/创建Tab按各自独立资格可用，不以是否能读取目录作为门槛。预算人员能够独立进入预算页。两固定导航只表示入口，服务端仍执行业务授权。

### 七个独立资格提示

沿现有AudienceActionsController的最小DTO，分别新增GET `/v1/operations/campaigns/{create|preview|submit|approve|reject|publish|pause}-access` **七个字面方法路径**，各自只返回`{allowed:true}`，不返回许可/Token/Grant/业务数据。每个入口只对应同名campaign能力，OPERATOR/executionId要求及scope/返回前requireSame与现有提示一致；403为明确无资格、401失效、503依赖故障。没有hint就不能猜测有权，真实业务提交/预览仍追加实际Owner判权与原业务校验。

这些提示是集合资格，不证明某个对象状态适合操作，不返回跨动作聚合许可；不需要campaign.read、member.read、store目录read或其他写能力。CentralEmployeeConfiguration登记GET字面白名单，POST继续六实际动作有限映射。实施后HTTP_INVENTORY与bindings从真实源生成/核对：262→271，122能力/34角色及published:false保持。设计阶段不改清单计数或模拟已存在路由。

### 真实DTO与编辑配方

创建沿CampaignApi.Draft原字段：手填实际storeId（不依赖门店read）、campaignId/正版本/名称/本地时区有效期/精确金额字符串，复用纯RuleEditor生成可信技术节点；它的14字段/有限类型与操作符已存在源码，不编造会员等级/规则资产/人群/门店选项。可填固定已发布规则、固定人群的实际ID/正版本；Terms沿原百分比万分比、资方比例、正预算及互斥权益/券引用、精细价格策略，券滚动开关继续由服务端拒绝，不能由页面绕过。较长创建表单按基础信息、资格与固定引用、优惠与预算、权益/商品价格配置组织；结果展示真实Draft/View及状态，不把创建成功当作已审批/发布。

预览沿原Preview：实际memberId、1—100项SKU与每项/合并数量1—10000、可选模拟时间和includePublishedCompetition。返回实际PreviewResult的金额、行明细、trace、sources、新鲜度/竞争notice和selected；不将预览显示为正式报价或预占成功，不存造价结果。模拟时间不回溯会员当前事实，服务端仍用真实会员/门店/已发布SKU与固定规则。

状态操作只允许submit/approve/reject/publish/pause；请求体原`{expectedVersion}`，实际URL保留content.version。表单不增加OA逐笔审批、自审批禁止、预算阈值或强制原因字段；说明原状态冲突及预计版本由服务端裁决。成功展示返回的实际状态/lockVersion，409保留可修正输入并重新读取自己的目录资格；未知结果继续用原意图重试。

### 命令、关闭与反馈

每个创建/状态命令的key、body和目标URL冻结；unknown/busy禁改目标和载荷，不新key，跨Tab/取消退出仍保留原意图。独立hint在提交前重新核验，但不将hint复用为授权。401卸载全部敏感视图；403隐藏对应写操作，503不降级成旧ADMIN或ALLOW，并支持重新核验；unknown结果即便遇到后续403/503也不能悄悄换新意图。确定409可以编辑，确定成功只显示服务端实际回执；不能把网络/解析异常当未提交或自动重复新命令。

编辑/预览/动作确认沿现有居中弹层规范，标题与底部操作固定、长正文内部滚动；主动作每组一个，手机390/320保留边距，表格内部横滚。关闭、切Tab、退出和同页导航遵循当前中央脏表单/unknown保护，取消退出后原键体不变。目录/预算刷新与稳定游标、详情目标保持清晰；按钮显示只作提示，不能替代后端权限和状态判断。

### CAM2 必需验证

七提示分别200/403/401/503且无身份审计写入，读/其他动作不隐含提示。真实MySQL保持CAM1的状态/版本/原键/审计语义，完整后端及前端构建/格式通过；9契约/28工具及271真实入口核对。新client必须限制方法、精确路径/参数和目标类型，不复用旧ADMIN request边界。

基于最终制品的真实PKCE浏览器覆盖create-only、preview-only、reviewer、publisher、campaign-read-only、budget-only：结构化创建、实际预览及原state动作、409与丢已提交响应后原键体重试、切Tab/取消退出、跨租户/撤权/401卸载/真实中央停服503。实际SQL核验成功次数/内容版本和无重复审计，预览无报价/预占/发权益。1440/390实际查看目录/预算/创建/版本操作/预览trace与来源/成功/409/unknown/未保存确认/拒绝状态，320补布局；视觉与行为分别记录，不能以构建或截图代替交互。

CAM1精确CI36957875268/36957876071已完成，CAM2现已实现并进入VERIFYING；使用原目录任务分支，无新子Agent/工作树。所有剩余CE05—08及auth接入项目菜单资源展示目标保持。


## CAM1最终交付

CAM1完整Git/CI DONE：auth356d8b909485c774ac1d80bf45e7bb9b3dc5ad0a精确CI36957876071、commerce604023206d18feb1c23bec91069e259333160a93精确CI36957875268均completed/SUCCESS。源码与本地验证版本一致，CAM2依赖门禁满足；其余CE05—08及接入菜单资源目标未完成。


CAM2源码影响核对更正：除七个新GET提示，两个固定SPA入口此前未登记在CentralPageController，故实际HTTP总数为262+7+2=271。此前269只计提示而漏计两个静态入口；本次修正计数，不扩大业务能力或角色范围。


## CAM2当前实施证据

CE05-CAM2实施中，尚未完整验收：七个字面独立资格GET与两个固定SPA入口、严格中央客户端、活动目录/创建/版本操作/实际DTO预览及独立预算页已实现。12真实MySQL专项、271源码入口/122能力/34角色未发布及9契约单测、前端类型构建/Prettier和三项契约夹具浏览器恢复检查通过。未知结果保留原键/体/目标，允许保留意图返回页签及取消退出，成功使用服务端实际lockVersion。真实PKCE六类角色/撤权/中央503/SQL和最终制品/截图尚未验收，无CAM2 Git交付，完整目标active。

首次完整回归在既有CouponDeliveryTest第一轮推进断言20、实际16处失败，源码车道最多20且500毫秒预算；未修改测试断言或生产发券逻辑。独立7项重跑PASS，完整verify复核session45693已exit0，69报告490项485PASS/5既有skip。私密campaigns-ui-implementation-evidence.json记录指纹与缺口，正式TEST_RESULT/Git Gate仍待真实角色/SQL验证。


## CAM2 当前验证检查点

2026-10-01：当前状态VERIFYING，未Git交付。页面将独立资格与命令错误分开，避免重复错误及误报资格未确认；未知意图/实际写 guard不变。3项当前fixture测试、1440/390反馈截图、类型构建/格式及45部署工具单测通过；四项Java源摘要未变，490完整测试和12MySQL证据可复用。

真实第三轮f40c3cdeda5f已exit1：活动七角色、PKCE/409/原键体恢复/审批发布暂停/401、八新增效果和21总审计通过，整轮末尾库存读取因fixture600秒已过期（授权至末尾731秒）而403。保留全部三轮失败证据，不放宽授权断言；有限隔离测试授权延长3000秒，低于委派3600最大值，无生产变更。此前页面由Vite提供，不冒充最终编译制品证明。

最终83583faeb3c8/session16358正在运行`--packaged-browser`，18665直接提供SSO编译JAR，46前端文件与276后端条目/88依赖内容栅栏通过。仍需终态PASS、实际503及最终真实1440/390/320关联图片复核、正式Validation/GitCI；完整CE05—08/Auth菜单资源目标未完成。


### 编译制品静态入口修复与再验证

首个编译页面演练83583faeb3c8已exit1，583检查点后匿名GET /operations/audiences返回401 JSON；实际截图已查看。SecurityConfiguration固定GET白名单漏了该既有页面及两个新活动页面，Vite绕过此链；Controller路径存在不能证明匿名壳可用。仅添加/operations/audiences、/operations/campaigns、/operations/campaign-budgets三个GET；API、其他方法和未知页面继续拒绝，没有放行/v1或任意operations通配符。

13实际MySQL测试通过，新增方法验证有UI资源时三个匿名入口200 HTML、无UI构建时静态404（不被认证挑战）、POST/API/未知路径401、无审计效果。正式基于最终JAR演练将提前要求三个HTML200和对应拒绝通过，不接受404。新完整verify session20067已exit0，491项486PASS/5既有skip；7992eb6f最终包与17模块/663class/112资源/46前端文件一致。真实复验session63199/子网123启动中。旧490/旧后端字节一致记录仅为修复前历史，不覆盖当前安全源码改动。CAM2仍VERIFYING，终态/视觉/GitCI待完成。


## CAM2最终本地验收

CE05-CAM2本地DONE：七独立资格提示、活动目录/结构化创建/版本动作/实际预览与独立预算页完成。13真实MySQL专项、完整491项（486PASS/5既有skip）、45工具/9契约、3当前fixture及类型构建/格式通过；最终真实编译JAR演练01b3a0f89ea4（session63199/子网123）exit0，870检查点PASS。真实SQL恰21总活动身份审计/8新增单次效果、内容版本1且无store伪归属，UI-a PAUSED/锁4、UI-b REJECTED/锁2，预算各20.00/0/0；预览不产生报价或预占。28张当前真实1440/390/320关联截图已实际查看，Esc关闭与焦点恢复通过。7992eb6f基线包与17模块/663class/112资源/46前端文件一致；SSO编译包ab064ca1的276后端条目/88依赖与基线相同，源码13+5摘要未变。Git/精确CI待交付；其余CE05—08及Auth菜单资源目标继续，122能力/34角色未批量发布。四轮真实失败与修复前证据、5既有skip/Javaformatter限制保留；原8602/OA和共享数据未切换。

正式implementation-validation COMPLETED/PASS；验收映射见[活动验证](../../implementation/oa-auth/commerce-readiness/CE05_CAMPAIGNS.md)，私密TEST_RESULT、SQL、源码与截图证据存于.local/governance/commerce-contracts/campaigns-ui-{test-result,final-real-sql,final-visual,current-source-fence}.json。上文运行中状态是历史检查点。
