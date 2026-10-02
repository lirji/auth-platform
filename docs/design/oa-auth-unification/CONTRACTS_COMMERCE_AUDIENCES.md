# CE05-A 人群快照员工权限契约

沿既定CE05扩展，复用MarketingAssets/MarketingAssetService、AssetMapper和MarketingAssetController。已有资源audience和两个HIGH能力audience.read/create均TENANT_ALL，两者独立。公开目录只提供最新版本摘要，没有单个资源操作，首批两能力均仅集合许可；不提前开放成员明细入口。

|ID|结果|前置|验收|状态|
|---|---|---|---|---|
|CE05-A0|auth稳定audience常量和两有限执行能力|R2本地与Git交付|HUMAN/60秒、精确类型/能力、两集合、错误事实拒绝、撤权/代际/原路径、真实PG/图/SDK兼容|DONE（本地）|
|CE05-A1|commerce独立AUDIENCE族、原版本与双表写入/命令/身份审计同事务|A0验证交付|独立read/create、真实主/成员行、旧回执前路由/期限、审计回滚、原键/撤权/STOPPED/503，内部固定版本引用兼容|DONE（本地）|
|CE05-A2|固定人群页与独立创建提示|A1验证交付|实际摘要与有界成员输入、新鲜度窗口/重复纠错、409/未知/401/403/503、1440/390截图与精确SQL审计|DONE（本地）|

## A0 有限执行协议

沿现有Reference/ScopeCheck/Check，不增JSON字段或背景任务权限。创建对象尚不存在，目录为集合，故两能力只取得完整租户集合；Check资源入口拒绝，TENANT_ONLY事实不能借store/department字段制造授权。原身份代际、调用方/应用/环境、期限、目录版本及原Grant路径交集继续是权威；撤权重授不复活旧引用。

## A1 真实业务与事务

GET/POST `/v1/admin/audiences`。目录维持每个tenant+audienceId最新不可变version、稳定游标，返回前再核对范围；AudienceView只包含id/version/name/source/watermark/validUntil/memberCount，不能扩展返回memberIds。

创建沿原校验：id64且dyn-保留给动态任务，name/source128，正版本，memberIds非null/最多500/合法标识且不重复。watermark不能未来，validUntil晚于watermark，窗口最多24小时；旧语义允许已过期快照，员工许可不能擅自加“必须当前有效”的限制。创建快照不创建会员或改会员状态，也不能据此声称每个导入id已是本租户会员。

旧模式command input保持不变，中央只增加稳定身份。路由锁/期限检查先于旧幂等回执；头/成员/command/实际audienceId身份审计在同一事务，任意审计或成员插入失败整体回滚。实际内容version是不可变版本，不是状态修订；仅追加新迁移，实施前核对序号，V49—V62不可改。

sources、members、requireFresh为受信任内部固定版本引用，维持最多100引用/去重、版本缺失拒绝、过期或未来UNKNOWN、命中HIT/不命中MISS、实际时点新鲜度和有界成员游标。员工撤权不撤销已导入快照；动态dyn-生产者及活动/交易读取继续通过原内部入口。新员工能力不能替代客户、系统执行身份或活动审批。

## A2 目录、输入与恢复

固定 `/operations/audiences?tenant_id`，人群目录/创建人群两Tab，GET `/v1/operations/audiences/create-access`独立提示，不发可复用凭据或隐含read。目录展现实际摘要/新鲜度字段与稳定分页，成员ID始终不返回目录。

创建id64/version正安全整数/name和source128、两个时间和多行成员编号0至500；重复明确报错，不静默去重，不编造会员选项或样例清单。时间按浏览器时区输入并转ISO，保留原过去快照语义及最多24小时窗口；结果确认展示实际memberCount，不把空快照解释为全体会员。

未知结果冻结原键/体，切Tab和取消退出保留；409可纠正，401卸载，403独立拒绝，503关闭写表单并支持重新核验。真实PKCE、无read创建、时间/重复/数量边界、原键与SQL精确主/成员/审计校验、撤权和停机、1440/390查看为验收条件。生产目标/映射/Owner保持原HOLD。

## CE05-A0验证与边界

A0本地DONE：四源码有限变更，不增加JSON字段、迁移或依赖。完整及最终forceCreation install各252单元PASS，真实自有PG86a26d467cc2/SpiceDB的ExecutionAuthorizationIT共14方法PASS，独立Boot4 SDK 1方法PASS；两个运行Jar内嵌protocol/core/governance与当前模块及四源码SHA256一致。专用PG已finally停止，私密证据保留。

|验收|实际结果|私密证据（commerce-contracts目录，不入库）|
|---|---|---|
|两能力精确绑定与仅集合|audience.read/create各独立验证；拒绝其他类型/能力、部分门店范围、单个资源与伪门店事实，范围为TENANT_ALL|audience-core-integration.log|
|有效期/身份/撤权兼容|14方法回归包含HUMAN、调用方/环境/代际、60秒上限、过期拒绝及撤权重授不复活原路径|同日志与integration-result.json|
|构建与SDK|252单元、Boot4 1方法、最终强制重打包安装、实际两个运行Jar校验PASS|audience-core-{install,boot4,final-install}.log|
|当前版本/质量|四源码摘要一致，hygiene无阻断|audience-core-{source-sha256,hygiene,test-result}.json|

质量限制沿既有仓库：Java formatter未配置，按周边风格检查；静态分析未配置。无可见页面变化，A0视觉N/A；A1与A2尚未实现，不能把协议PASS当商城业务或页面验收。原生产2HOLD不变，下一CE05-A1需先完成A0正常Git交付。


## CE05-A1实施与当前验证（2026-10-01）

CE05-A2本地DONE：固定人群目录/创建两Tab与独立创建提示；8项真实MySQL和完整478项（473PASS/5既有skip）、最终前端build/forceCreation package通过。最终真实a5fd3867e690（10.254.117.0/24）647检查点PASS，人群11条浏览器检查及全部既有员工页回归通过；恰5条实际身份审计，UI两个快照为1:2和1:0，原键不重复、导入不创建客户。1440/390表单/目录、409/未知/退出确认/成功/401/503共11张截图已实际查看，正文390且表格内部横滚。两仓源码摘要、17嵌套模块/661类/45资源及复制JAR一致；262入口/122能力/34角色、9契约/28工具及两仓hygiene无阻断。首轮594后401夹具覆盖凭据失败已修正并保留。自有进程已停止；无新迁移，V49—V63不可改、SDK固定4747ac49，原8602/OA不切换。Git/CI待交付；下一CE05-CAM活动/审批/预算细化，其余CE05—08及auth资源展示未完成，生产2HOLD不变。

原DTO/URI/不可变版本/500上限/过去快照语义与可信内部固定引用保持。新增V63只扩展能力族/审计类型约束，不自动迁移租户。7专项含真实双表与回执/审计回滚、范围/身份/期限/原键、内部HIT/MISS/UNKNOWN与旧模式兼容；中央协议桩不替代尚待跨进程验收。

首次定向命令被仓库各模块必须有测试门禁拦截；两轮完整回归暴露兼容夹具错误，分别为禁止删除已中央路由和独立租户缺OPERATOR凭据。已用新的未接管租户并完整显式绑定修正，未改保护/断言或业务。失败日志与最终7PASS保留commerce .local/central-audiences。完整结果与Git另行补记，不提前标DONE。
