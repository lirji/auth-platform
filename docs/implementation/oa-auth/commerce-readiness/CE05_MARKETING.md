# CE05 营销与权益扩展执行记录

## 当前状态

CE05-E2本地DONE：权益定义/实例两个固定SSO页、独立create/resolve提示和安全重试。完整461项456PASS/5既有skip、权益8项全PASS；真实0078780a5d6b（10.254.108.0/24）563PASS，其中两个权益页各10条浏览器检查，含所有既有员工页回归。37工具/256入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce11源码摘要一致。1440/390目录/表单及409/未知/取消退出/成功/503截图已查看。恰8身份审计、UI定义和两个补偿各1条；真实客户兑换/核销和撤权后履约兼容。无新迁移，V49—V61不可改。下一CE05-R营销规则细化；其余CE05—08及原生产2HOLD未完成。

CE05-E1本地DONE：ENTITLEMENT_DEFINITION/ENTITLEMENT两族、四独立权限、真实grantId/version、旧回执前路由/事实/期限复核与同事务身份审计，V61已应用不可改。完整460项455PASS/5既有skip，新增7项全PASS；真实独立58007c181423（10.254.107.0/24）477PASS，无浏览器。37工具/252入口/122能力/34角色、两仓hygiene及auth1/commerce8源码摘要一致。下一E2权益定义/实例页面；其余CE05—08和原生产2HOLD未完成。

CE05-E0本地DONE：权益定义/实例两类型和四独立有限能力，定义只集合、实例绑定真实grantId/version；全仓252单元、真实自有PG b8c52c20066d/SpiceDB的ExecutionAuthorizationIT共12方法、SDK Boot4与最终forceCreation install均PASS，两个运行Jar嵌套依赖和4源码摘要一致。hygiene无阻断，原Java格式/静态分析限制保留。自有PG已finally停止，未新增迁移或基础设施，下一E1商城两个族/真实Owner/事务审计，E2页面与其余CE05—08未实施。

CE05-CD2本地DONE：固定SSO券定义目录/创建两Tab、独立创建提示；完整453项448PASS/5既有skip，券定义7项全PASS；真实隔离83d4ad742f53（10.254.106.0/24）493PASS，其中券定义10条浏览器行为，含全部既有员工页回归及O2标识64字校准。37工具/252入口/122能力/34角色、build/Prettier/两仓hygiene与auth4/commerce11源码摘要一致。当前1440/390目录/表单、409、未知结果、退出确认、成功/503截图已查看。5条实际定义身份审计、UI两定义各1条，实际公开领取/受控兑换共2次且余额100。Java formatter/静态分析限制保留，无新迁移；V49—V60不可改。已推送auth6e3bfc9/commerce0e2ff49，CI36693981625/36693990527运行中；下一CE05-E权益定义/实例技术细化；其他CE05—08与生产2HOLD未完成。

## 历史记录

O2已正常合并推送authc823750/commercec027daa，CI36688828439/36688830234运行中。CE04本地纵向片完成；CE05—08及原生产2HOLD仍未完成。

首片[券定义契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_COUPON_DEFINITIONS.md)按已批准两能力/TENANT_ALL细化，CD0在auth feat/commerce-coupon-definition-execution实现。四文件有限变更，不扩大客户身份，不新增逐笔OA审批；CD1/2尚未实施。

CD0相关模块132单元PASS；真实自有PG8908d6c82bcf和SpiceDB执行ExecutionAuthorizationIT共11方法PASS，新增券定义创建/读取集合、拒绝错误类型/能力/范围/代际/环境/期限及撤权重授不复活。自有PG finally停止，证据coupon-definitions-core-integration-result.json；完整install/SDK Boot4/hygiene验证中，四文件源码摘要已记录。

CD0最终本地DONE：全仓install共252单元PASS、SDK及两个运行Jar强制重打包安装通过，独立Boot4兼容测试PASS；真实PG/图11方法PASS。hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（未配置Java formatter/静态分析），四文件源码摘要一致。无SDK格式/新基础设施/数据库迁移变化，商城Owner尚未接入；Git交付后继续CD1。

CD0已正常合并推送auth471cdb8，CI36689152601运行中；O2 auth CI36688828439被后续提交取消，CD0包含该基线，commerce36688830234仍运行中。

CD1在commerce feat/central-coupon-definition-operations实现COUPON_DEFINITION族、两能力、集合许可/旧回执前路由检查、实际definitionId事务身份审计、共享目录非MEMBER门禁和V60（族与审计类型同迁移）。SDK固定471cdb8。最新版本/客户PUBLIC语义不变，源码不改原领取和受控履约；compile通过。新增CentralCouponDefinitionMySqlTest6方法，完整coupon-definitions-verify.log运行中；只读核对发现新夹具商家状态误用INACTIVE，已按真实schema修为FROZEN，当前已编译旧夹具的结果需保留并验证最终版。auth P6 --coupon-definitions接线和37工具/250入口通过，尚未运行真实跨进程。

SDK安装后protocol归档更新，P6制品预检在创建资源前拒绝admin嵌套摘要不一致；已maven.jar.forceCreation=true package并核对两个运行Jar的protocol/core/governance均与当前模块一致。此为构建制品更新，不修改能力或时间预算，coupon-definitions-runtime-package.log保留。

首轮新6项中2项夹具错误：商家INACTIVE不属于实际ACTIVE/FROZEN；另一项误把路由正则/审计列上限100当作业务Identifiers上限，实际为64。按原约束修正测试为FROZEN/64，补正券定义契约的标识说明，业务代码/数据库约束不放宽。旧O2定义UI输入上限100与服务端64不一致已登记后续收口检查，服务端持续拒绝越界，不属授权绕过。

O2 commerce CI36688830234 SUCCESS。CD1首轮执行449项/2新夹具错误/5既有skip，其余通过；因app单测失败未进入后续验证；最终coupon-definitions-verify-fixed.log（session13097）重跑中，V60已应用不可改。源码摘要auth1/commerce8已更新为最终夹具版本。

CD0精确远程CI36689152601 SUCCESS，包含此前取消的O2 auth基线；O2 commerce CI已SUCCESS。

最终版新增CentralCouponDefinitionMySqlTest6方法全部PASS，真实MySQL验证包含64字ID审计、两权限独立、最新版本目录、审计回滚、Owner/范围/身份/撤权/STOPPED/503以及客户与受控来源兼容。完整回归仍在后续模块校验中，尚不标CD1 DONE。

CD1完整最终452项447PASS/5既有skip，新增6项全PASS；两仓hygiene无阻断、Java formatter/静态分析限制保持；37工具/250入口及最终源码摘要auth1/commerce8一致。真实--coupon-definitions无浏览器rehearsal-cf51bc65d092/子网104，session19261运行中，完成前不标DONE。CD2页面技术细节已按真实64字标识与nullable规则补入正式契约，但尚未实施。

首轮真实cf51bc65d092/子网104在378PASS后因新增脚本使用不存在的/access/revocations且预期202停止（实际401）；当前凭据尚未到期，真实Controller为/access/revoke并返回200。已只修正该脚本地址/响应预期，保留权限/预算和所有业务断言，商城452项版本不变。自有进程finally停止、失败证据/数据保留；重新工具/hygiene后子网105完整演练。

O2输入长度校准单独分支交付authad9984d/commercebe8f96e，显式仅提交前端页面及对应文档；build/Prettier/hygiene通过，CD1未提交文件全部保留并已回原任务分支快进包含修复。当前CD1真实aa92413e7500/子网105运行复制的后端制品；输入校准的浏览器回归由后续CD2覆盖。CD1最终package会包含已构建的最新页面，不重跑未变后端单元。


### CD1最终本地DONE

最终真实aa92413e7500/子网105共415PASS，无浏览器；coupon_definitions_checked=true，runtime_switched/production_ready=false。两能力独立、创建无read、原键/不可变版本冲突、真实商家门店Owner、精确金额、最新版本稳定分页及客户不泄露旧PUBLIC版本、跨租户与旧ADMIN两目录旁路拒绝、撤权后旧成功回执403、实际中央停机员工503/客户目录200全部通过。三次券定义恰3条实际definitionId身份审计；撤销创建和单独临时商品定义权限后，客户实际领取公开券并通过原积分兑换获得受控券，原键重试未重复发放，发行总数2、积分余额100。既有模块与后台执行引用/进程恢复检查保持。

完整452项447PASS/5既有skip、新6项全部PASS，37工具/250入口/122能力/34角色、compile/最终package、两仓hygiene及auth1/commerce8源码摘要一致。Java formatter/静态分析限制保留，V60已应用不可改。首次2项测试夹具错误及真实演练错误撤权路径均保留，修复不改业务边界或预算；自有进程finally停止，子网104/105私密证据/数据保留，原8602未切换。后端Git交付中，下一CD2页面及hint，其他CE05—08/生产2HOLD仍未完成。O2长度修复已有独立build/格式/hygiene证据，后续CD2真实浏览器回归。


CD1已正常合并推送auth700f2c9/commerceb36982e，两仓CI36691487561/36691489137 SUCCESS。CD2两仓原目录任务分支继续，固定页面/独立hint/7项MySQL测试与真实浏览器脚本已实施，验证中。37工具/252入口/前端build通过。首轮Java回归混用原runtime.env凭据与专用43308，连接拒绝，保留coupon-definition-ui-verify.log；改用既有.local/central-inventory/owned.env重跑，不改账号/业务实现或原商城环境。

CD2第二轮仅加载owned.env缺少原地址加密配置而失败；最终依次加载runtime.env及owned.env覆盖专用DB后，完整453项448PASS/5既有skip、券定义7项PASS，coupon-definition-ui-verify-final.log保留。前端build/Prettier、两仓hygiene（原Java格式/静态分析限制）通过；真实浏览器子网10.254.106.0/24/session34941运行中，尚未宣称CD2 DONE。


### CD2验收证据

|验收|方法/结果|证据（私密，不入库）|
|---|---|---|
|真实事务与独立创建hint|完整453项448PASS/5既有skip，新增hint及已有券定义共7项PASS|coupon-definition-ui-verify-final.log|
|登录/权限/创建/异常恢复|真实PKCE、无read创建、必填/金额/日期、409保留、成功丢响应原键原体重试、false/nullable/PUBLIC/SOURCE_ONLY、跨组织/撤权/401/实际503全部PASS|p6/rehearsal-83d4ad742f53/coupon-definitions-*-result.json（10条）|
|数据完整性与兼容|493PASS；恰5定义审计、UI各1、实际发券2、积分100，客户目录不泄露受控或旧PUBLIC版本，全部既有员工页回归PASS|p6/rehearsal-83d4ad742f53/result.json与检查点|
|视觉/交互|已实际查看1440/390表单和目录、409/未知/退出确认/成功/503；输入与按钮清晰、窄屏无页面外溢、表格内部横滚，取消退出和切Tab保留原意图|同目录coupon-definitions-*.png；O2 offers-390-define-form.png|
|源码与质量|37工具/252入口、build/Prettier、两仓hygiene通过，auth4/commerce11源码摘要匹配|commerce-contracts/coupon-definition-ui-*|

两次本地测试环境装载错误已保留日志，最终使用source .local/runtime.env后source .local/central-inventory/owned.env覆盖DB；不调整测试预算或数据库账号。运行Jar包含当前前端构建；自有进程finally停止，原8602未切换；本轮无新worktree，私密数据和截图保留。技能状态：implementation-validation COMPLETED/PASS；update-progress-docs CD2→DONE（本地），Git/CI另行记录。


### CE05-E0进行中

[权益契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_ENTITLEMENTS.md)按已批准四能力细化。auth原目录feat/commerce-entitlement-execution限定四源码文件：稳定两类型、定义集合/实例实际grant判权与真实PG/图集成测试。未扩大客户身份，E1业务和E2页面尚未实施。


CE05-E0本地DONE：权益定义/实例两类型和四独立有限能力，定义只集合、实例绑定真实grantId/version；全仓252单元、真实自有PG b8c52c20066d/SpiceDB的ExecutionAuthorizationIT共12方法、SDK Boot4与最终forceCreation install均PASS，两个运行Jar嵌套依赖和4源码摘要一致。hygiene无阻断，原Java格式/静态分析限制保留。自有PG已finally停止，未新增迁移或基础设施，下一E1商城两个族/真实Owner/事务审计，E2页面与其余CE05—08未实施。
证据：私密commerce-contracts/entitlements-core-{install,integration,boot4,final-install}.log、integration-result.json、source-sha256.json与hygiene.json。四组合同时验证错类型/能力/代际/环境/期限及撤权重授，旧能力回归保持。


E0已正常合并推送auth3a4cef3，精确CI36694413067运行中。CD2 commerce0e2ff49/CI36693990527 SUCCESS；auth CD2 36693981625因后续E0提交取消，需以包含同基线的E0 CI核对。

E1两仓原目录分支feat/commerce-entitlement-owner-rehearsal与feat/central-entitlement-operations实施：两族/真实Owner/事务审计/V61，SDK固定3a4cef3，compile与37工具/252入口PASS。新增CentralEntitlementMySqlTest7方法；首轮完整回归新夹具1FAIL/1ERROR：服务层重复定义实际DuplicateKeyException（HTTP统一409），编码标识的待补偿SQL夹具漏expires_at触发V12约束。已只修测试预期及expires_at，追加HTTP409断言；P6同类显式SQL夹具同步有效期。V61已执行不可改。最终entitlements-owner-verify-fixed.log/session89342运行中，尚未标E1 DONE。

E0精确CI36694413067 SUCCESS，包含CD2 auth6e3bfc9基线；CD2 commerce CI36693990527已SUCCESS。


E1修正后的7项权益MySQL测试全部PASS；完整verify-fixed只剩既有MemberGrowthTest.distinctRefundsAfterOldSnapshotsPreserveGrowthAndBehavior的5秒屏障超时（同CE04-C2已记录问题），未修改该测试/预算。两次定向Maven尝试均被父POM显式failIfNoTests阻断，没有执行目标测试；保留retry/retry-fixed日志，不绕过构建规则。最终重新完整运行entitlements-owner-verify-final.log；真实演练仍待该制品通过。37工具/252入口最终再验PASS。

最终entitlements-owner-verify-final.log完整460项455PASS/5既有skip，7项权益、MemberGrowthTest原4方法及3架构测试均PASS，BUILD SUCCESS。未改并发预算/原测试。37工具/252入口、两仓hygiene（既有Java工具限制）和auth1/commerce8源码摘要一致；真实独立子网107演练session92374运行中，E1尚未标DONE。


## CE05-E1验收

CE05-E1本地DONE：ENTITLEMENT_DEFINITION/ENTITLEMENT两族、四独立权限、真实grantId/version、旧回执前路由/事实/期限复核与同事务身份审计，V61已应用不可改。完整460项455PASS/5既有skip，新增7项全PASS；真实独立58007c181423（10.254.107.0/24）477PASS，无浏览器。37工具/252入口/122能力/34角色、两仓hygiene及auth1/commerce8源码摘要一致。下一E2权益定义/实例页面；其余CE05—08和原生产2HOLD未完成。

|验收|结果|私密证据|
|---|---|---|
|本地真实MySQL/全仓|460项455PASS/5既有skip；7权益专项、会员并发原4方法和3架构测试通过|commerce-contracts/entitlements-owner-verify-final.log|
|真实独立授权与业务|477PASS，两读两写独立、实际Owner、编码冒号、原键/新键、跨租户、撤权旧回执拒绝与停机503|p6/rehearsal-58007c181423/result.json|
|账本与客户兼容|定义3+实例2恰5身份审计；RECOVERED/WRITTEN_OFF各一条units2；实际积分兑换事件发放，撤权后客户核销2/余3、重试不重复、超扣拒绝、积分余50|同目录检查点与entitlement-fixture-origin.json|
|源码/质量|37工具/252入口与hygiene通过，auth1/commerce8最终摘要匹配；Java formatter/静态分析未配置限制保留|commerce-contracts/entitlements-owner-{source-sha256,auth-hygiene-final,commerce-hygiene-final}.json|

跨进程待补偿数据为明确的隔离SQL夹具，不宣称HTTP整单退款；MySQL专项从订单域真实预留/确认/事件/核销/冲正产生欠项。客户积分兑换、受理事件及核销为真实HTTP链路。自有进程finally停止，原8602未切换，无新worktree，全部私密证据和隔离库保留。

首次两项新夹具错误已修；次轮既有MemberGrowthTest五秒屏障超时，定向两次被父POM显式failIfNoTests拦截。均保留原日志，最终原样完整重跑通过；未放宽业务/测试预算、数据库约束或构建门禁。技能状态implementation-validation COMPLETED/PASS；update-progress-docs E1 DONE（本地）。Git/CI另记。


E1已正常合并推送auth4f1a05f/commerceb653130，CI36697025032/36697028441运行中。E2在原目录feat/commerce-entitlement-ui-contract与feat/central-entitlement-ui实施两个固定权益页/两个独立hint；原API、读写独立和未知原意图恢复，前端build/Prettier、37工具/256入口通过。新增第8项MySQL独立hint测试，完整entitlements-ui-verify.log/session83126运行中；真实浏览器脚本已接线未运行，待子网108。源码摘要auth4/commerce11已记，V61未修改。

E1两仓精确CI36697025032/36697028441 SUCCESS。E2完整461项456PASS/5既有skip（权益8项全PASS）、前端build/Prettier、37工具/256入口、两仓hygiene通过；脚本闭集Phase/Kind已按枚举修正，原检查报告保留。真实浏览器0078780a5d6b/子网108/session53681运行中；尚不标E2 DONE。


## CE05-E2验收

CE05-E2本地DONE：权益定义/实例两个固定SSO页、独立create/resolve提示和安全重试。完整461项456PASS/5既有skip、权益8项全PASS；真实0078780a5d6b（10.254.108.0/24）563PASS，其中两个权益页各10条浏览器检查，含所有既有员工页回归。37工具/256入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce11源码摘要一致。1440/390目录/表单及409/未知/取消退出/成功/503截图已查看。恰8身份审计、UI定义和两个补偿各1条；真实客户兑换/核销和撤权后履约兼容。无新迁移，V49—V61不可改。下一CE05-R营销规则细化；其余CE05—08及原生产2HOLD未完成。

|验收|结果|私密证据|
|---|---|---|
|事务/提示/全仓|完整461项456PASS/5既有skip，权益8方法PASS；两hint独立于read，旧ADMIN/无效身份/撤权/停机拒绝|commerce-contracts/entitlements-ui-verify.log|
|真实浏览器与兼容|563PASS；两个权益页各10条：实际PKCE、无read写入、字段/日期校验、409输入保留、丢成功响应后原key/body/编码路径重试、切Tab/取消退出、跨租户/撤权/401/实际503|p6/rehearsal-0078780a5d6b/result.json及entitlements-*-result.json|
|SQL与客户链路|恰8条身份审计；UI定义units7/quota15/days30/reserved0/issued0；UI两补偿各1条units2/balance0；真实兑换后核销2/余3，超扣拒绝，积分余50|同目录检查点/entitlement-fixture-origin.json|
|视觉|已查看两页面1440/390目录/表单、409/未知/离开确认/成功/503；布局可用，无页面外溢，表格内部横滚|同目录entitlements-*.png|
|质量|build/Prettier、37工具/256入口、两仓hygiene、auth4/commerce11最终摘要一致|commerce-contracts/entitlements-ui-*|

浏览器脚本闭集Phase/Kind首轮hygiene阻断已按枚举修正；验收前把目录预期加入真实已有周期/积分资产，未削弱业务断言，首次检查证据保留。Java formatter/静态分析未配置限制与5既有skip保持。补偿SQL夹具明确标识，不宣称HTTP整单退款；客户积分兑换与消费为真实业务HTTP。自有进程finally停止、原8602未切换、无新worktree，私密数据/证据保留。

E1 auth4f1a05f/commerceb653130正常推送，CI36697025032/36697028441 SUCCESS。E2 implementation-validation COMPLETED/PASS，update-progress-docs DONE（本地）；Git/CI另记。
