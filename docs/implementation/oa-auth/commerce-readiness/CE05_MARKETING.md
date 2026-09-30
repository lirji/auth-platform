# CE05 营销与权益扩展执行记录

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
