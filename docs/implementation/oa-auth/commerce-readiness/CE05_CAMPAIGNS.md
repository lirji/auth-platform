# CE05-CAM 活动与预算验证

原已批准CE05权限切片的技术细化见[活动契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_CAMPAIGNS.md)。当前CAM0本地DONE，Git已交付b311e4c、CI36955364612 SUCCESS已核验；CAM1本地验证DONE、Git/CI待交付，CAM2页面尚未实现，原CE05—08及auth菜单资源展示目标不缩小。

| 验收 | 实际结果 | 证据 |
|---|---|---|
| 9能力/类型/完整租户范围 | PASS：每个HIGH能力独立角色/Grant，其他8不能签发或交换现有scope；指定门店范围拒绝 | 真实ExecutionAuthorizationIT新增campaign方法 |
| 三集合、六已有版本动作 | PASS：read/create/budget.read对象入口拒绝；preview/submit/approve/reject/publish/pause返回实际CAMPAIGN-1/v7；零/负version及错类型/租户/门店拒绝 | 同方法，实际PG/SpiceDB |
| HUMAN/期限/身份/原路径 | PASS：SERVICE、120秒、代际/环境/其他能力拒绝；撤权后旧Grant、重新授予不复活引用；原15方法回归全部PASS | 专用PG d57b5e71bac5，15方法0FAIL/ERROR/SKIP |
| 全仓与SDK | PASS：252单元0FAIL，Boot4兼容1；最终forceCreation安装PASS、4源码摘要未变 | campaigns-core-install.log / boot4.log / final-install.log / source-sha256.json |
| 当前运行制品 | PASS：server320类/admin349类、各42资源/3嵌套模块与当前源码编译产物一致 | campaigns-core-runtime-fence.json |
| 质量 | PASS_WITH_LIMITATIONS：无阻断；Java formatter/静态分析未配置 | campaigns-core-hygiene.json |
| 可见UI | N/A：仅有限协议，没有页面变更；CAM2另验收 | 不以协议PASS替代商务界面 |

最后安装使用-DskipTests仅更新打包档案，并复用此前不变源码的252/15/Boot4证据，不声称重复测试已运行。专用PG已finally停止，既有共享SpiceDB/业务8602/OA保持。无新DDL/组件/依赖/JSON格式。具体私密证据均在auth `.local/governance/commerce-contracts/campaigns-core-*`；不提交账号或测试Token。

implementation-validation COMPLETED/PASS；CAM0 DONE（本地）。Git已交付b311e4c、CI36955364612 SUCCESS已核验，CAM1只有该门禁完成后开始实施；生产目标/映射/Owner/部署授权沿原HOLD。


## CAM1 实施验证检查点（历史）

commerce源分支feat/central-campaign-operations（51c771b基线），auth演练分支feat/commerce-campaign-owner-rehearsal（a136c20基线）。CAM0精确CI已SUCCESS，SDK固定b311e4c。V64在隔离测试库应用，旧迁移不改；employee审计resource_version记录实际正内容版本，状态lockVersion仍独立。

11真实MySQL专项/完整489（484PASS、5既有skip）PASS；实际SQL约束故障回滚活动、预算、自动暂停旧版本、审批与回执。原键身份、实际版本/期限/上下文/HTTP81权限独立与预算系统履约均覆盖。最终forceCreation with-ui包17嵌套模块/661类/510资源/45前端文件一致；两仓hygiene无阻断，Java formatter/静态分析未配置。auth 9契约/28P6工具及脚本语法PASS。

真实跨进程47a2d78d80c5/子网118仍在运行；不得重启或声称终态。首轮制品栅栏拒绝（无资源副作用）证据保留，重打包后当前真实演练正常推进。CAM1状态VERIFYING，Git/CI未执行；可见UI N/A，本片未改页面，CAM2单独验收。证据在auth .local/governance/commerce-contracts/campaigns-owner-*及commerce .local/central-audiences/campaigns-*。


## CAM1 最终本地验收

CE05-CAM1本地DONE：9独立活动/预算能力、6实际正内容版本动作、独立目录/预算与真实预览已接入；V64版本审计与活动/预算/状态/原回执同事务。11真实MySQL专项（含81权限HTTP边界/实际SQL故障/锁等待期限）、完整489项484PASS/5既有skip、9契约/28工具及两仓hygiene无阻断。最终10源码/SDK摘要与17嵌套模块/661类/510资源/45前端文件/复制JAR一致。真实47a2d78d80c5/子网118已exit0，645检查点PASS（79活动标签），SQL再次核验13身份审计/实际内容版本1:3、7:6、8:4，v7 PAUSED/lock6、v8 PUBLISHED/lock3，客户订单在STOPPED后正常释放v8预算。自有进程/PG已停止，数据证据保留。Git/CI待交付；CAM2和其余CE05—08/auth接入资源展示未完成，原8602/OA及生产2HOLD保持。

| 验收 | 实际证据与结论 |
|---|---|
| 9权限/实际Owner/完整范围 | 11MySQL中81真实HTTP独立边界、类型/零负版本/引用交换拒绝；真实PG/图9独立Grant逐项审批/发布/暂停不含其他能力，PASS |
| 回执前权限/锁/期限/主体 | 原键先检查路由和实际内容版本；锁等待后过期拒绝、撤权/STOPPED/代际变化拒绝，成功原键允许回放且不改递增锁，PASS |
| 原状态/固定引用 | 旧无policy兼容发布，增强活动提交/审核/拒绝、唯一发布、真实规则/人群新鲜度发布门，PASS |
| 同事务审计/版本 | 实际V64 CHECK故障回滚活动/预算/审批/自动暂停/command；真实13审计无重试重复，内容版本分布1:3/7:6/8:4，PASS |
| 独立列表/预览 | 最新内容目录、所有版本预算稳定游标、返回前上下文；真实会员/SKU/固定规则与SQL无报价/预算/库存/审计副作用，PASS |
| 旧订单履约 | MySQL实际reserve/confirm/release；跨进程真实客户报价/订单、员工STOPPED后的实际取消与v8 RELEASED/余额0.00，PASS |
| 回归/当前制品 | 69 XML 489项484PASS/5既有skip；17嵌套模块/661类/510资源/45前端文件与最终/复制JAR字节一致，PASS |
| 契约/工具/质量 | 262入口保持，9契约/28P6工具及语法/两仓diff与hygiene无阻断；Java formatter/静态分析未配置 |
| 可见UI | N/A：没有页面变化；CAM2单独做PKCE、视觉/完整交互验收 |

正式implementation-validation COMPLETED/PASS；CAM1 DONE（本地）。Git/CI下一步正常交付，不把全CE目标改为本片完成。首轮协议归档栅栏拒绝在资源创建前、无产品源变化；commerce wrapper不存在与reactor专项无匹配、pytest不可用分别改项目mvn/module测试及unittest，原失败日志均保留，不放宽断言或修改POM门禁。当前645终态与SQL及源码制品fence为最终证据。
