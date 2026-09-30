# 当前商城扩展续做

CE05-R1本地DONE：RULE独立接管族、规则read/create/publish、真实不可变版本锁、原回执前权限复核与同事务审计，V62已应用不可改。完整468项463PASS/5既有skip，规则7项全PASS；最终真实7b1ff652c183（10.254.110.0/24）518PASS，无浏览器。42工具/256入口/122能力/34角色、两仓hygiene及auth2/commerce12源码摘要一致。下一R2规则员工页；其余CE05—08与原生产2HOLD未完成。

CE05-R0本地DONE：marketing_rule稳定类型和read/create/publish三个独立有限能力；创建只集合，读取/发布绑定实际资产版本。完整252单元、真实自有PG5ff5f702ccbc/SpiceDB共13项ExecutionAuthorizationIT、SDK Boot4和最终forceCreation install通过，两个运行Jar嵌套依赖及4源码摘要一致。hygiene无阻断，Java formatter/静态分析限制保持。无新迁移，自有PG已finally停止；下一R1商城RULE族/实际Owner/事务审计，R2与其余CE05—08未完成。

CE05-E2本地DONE：权益定义/实例两个固定SSO页、独立create/resolve提示和安全重试。完整461项456PASS/5既有skip、权益8项全PASS；真实0078780a5d6b（10.254.108.0/24）563PASS，其中两个权益页各10条浏览器检查，含所有既有员工页回归。37工具/256入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce11源码摘要一致。1440/390目录/表单及409/未知/取消退出/成功/503截图已查看。恰8身份审计、UI定义和两个补偿各1条；真实客户兑换/核销和撤权后履约兼容。无新迁移，V49—V61不可改。下一CE05-R营销规则细化；其余CE05—08及原生产2HOLD未完成。

CE05-E1本地DONE：ENTITLEMENT_DEFINITION/ENTITLEMENT两族、四独立权限、真实grantId/version、旧回执前路由/事实/期限复核与同事务身份审计，V61已应用不可改。完整460项455PASS/5既有skip，新增7项全PASS；真实独立58007c181423（10.254.107.0/24）477PASS，无浏览器。37工具/252入口/122能力/34角色、两仓hygiene及auth1/commerce8源码摘要一致。下一E2权益定义/实例页面；其余CE05—08和原生产2HOLD未完成。

2026-09-30：已批准商城员工扩展持续执行中。[执行记录](../../implementation/oa-auth/commerce-readiness/EXECUTION_PLAN.md)保持原稳定编号；业务边界为OA员工权威、会员/营销TENANT_ALL、库存/交易门店范围，高风险独立权限并沿原审批流程。CE02/03与CE04会员、成长、标签、行为、周期、积分、积分商品的API/页面纵向片均完成本地验证；最新O2真实隔离450PASS，commercec027daa/CI36688830234 SUCCESS，authc823750基线已由后续CD0包含。

CE05-CD2本地DONE：固定SSO券定义目录/创建两Tab、独立创建提示；完整453项448PASS/5既有skip，券定义7项全PASS；真实隔离83d4ad742f53（10.254.106.0/24）493PASS，其中券定义10条浏览器行为，含全部既有员工页回归及O2标识64字校准。37工具/252入口/122能力/34角色、build/Prettier/两仓hygiene与auth4/commerce11源码摘要一致。当前1440/390目录/表单、409、未知结果、退出确认、成功/503截图已查看。5条实际定义身份审计、UI两定义各1条，实际公开领取/受控兑换共2次且余额100。Java formatter/静态分析限制保留，无新迁移；V49—V60不可改。Git交付中，下一CE05-E权益定义/实例技术细化；其他CE05—08与生产2HOLD未完成。

CD1已正常合并推送auth700f2c9/commerceb36982e，CI36691487561/36691489137 SUCCESS。详细证据见[CE05_MARKETING](../../implementation/oa-auth/commerce-readiness/CE05_MARKETING.md)。历史阶段记录如下，以本段为当前摘要。

CE05-E0本地DONE：权益定义/实例两类型和四独立有限能力，定义只集合、实例绑定真实grantId/version；全仓252单元、真实自有PG b8c52c20066d/SpiceDB的ExecutionAuthorizationIT共12方法、SDK Boot4与最终forceCreation install均PASS，两个运行Jar嵌套依赖和4源码摘要一致。hygiene无阻断，原Java格式/静态分析限制保留。自有PG已finally停止，未新增迁移或基础设施，下一E1商城两个族/真实Owner/事务审计，E2页面与其余CE05—08未实施。

## 已交付P7基线

当前续做：独立身份服务/数据库与双新版有界容量基线通过：4项工具测试、24项演练、600请求零错误；a882d4f已正常合并推送main，精确CI36658762615成功，见[P7_ISOLATED_CAPACITY](../../implementation/oa-auth/phase-7/P7_ISOLATED_CAPACITY.md)。以下认证修复为已交付基线；本轮仅改变本地演练工具，生产门禁不变。

本轮续做：P7认证错误分类修复本地209单测与22项演练通过。已复现Casdoor共享PG连接不足时HTTP 200错误对象被误报401；修复保持拒绝并改报503。证据见[P7_AUTH_FAILURE_FIX](../../implementation/oa-auth/phase-7/P7_AUTH_FAILURE_FIX.md)。修复提交e16a4dd已正常合并推送main，精确CI36655346503成功；209单测、22项本地演练通过，生产容量/接受仍HOLD。后文为上轮交付基线。

P7_LOCAL_DELIVERED_RELEASE_HOLD。用户“继续”及“先做本地有界基线，生产目标待定”已落实。P7-01—06所选本地范围完成：21项真实跨进程检查、150受影响单测、2项真实进程CAS/崩溃恢复IT通过。P7-07已有评审与手册，但实际生产目标/Owner/接受指标/授权缺失，P7-08未执行；生产HOLD。

- [本地验证与容量实测](../../implementation/oa-auth/phase-7/P7_TEST_RESULT.md)
- [运行/轮换/恢复手册](../../implementation/oa-auth/phase-7/P7_RUNTIME.md)
- [生产评审与未决项](../../implementation/oa-auth/phase-7/P7-07_RELEASE_REVIEW.md)

仅auth仓有产品及工具修改，独立任务分支feat/iam-p7-hardening；commerce和OA无本轮改动，原商城健康。产品提交ac3062f/9f191e4已正常合并推送main，精确产品CI36649436539为SUCCESS；交付记录见[Git结果](../../implementation/oa-auth/phase-7/P7_DELIVERY_RESULT.md)与[CI结果](../../implementation/oa-auth/phase-7/CI_RESULT.md)。最终收尾为纯文档提交，产品/测试/运行树保持已验证版本。私有演练库、凭据、备份和日志保留；未新增worktree。

## P6已交付基线


P6_LOCAL_COMPLETE_GIT_CI_PASS。用户选择2已落实，完整CATALOG中央经营与后台任务链路、幂等导入/墓碑、影子比较、单元路由/冻结和安全回退完成本地隔离演练。31项真实跨进程检查通过，最终本地验证通过，两仓已正常合并并推送main，最终远程CI全部PASS。P6交付当时P7/生产未执行；P7现状以上方当前记录为准。

真实首批：commerce_local / operations-0abaaae0-6f24-42ea-a187-5093c70b96ad。1条旧授权的运营凭据已到期，保留拒绝；1条独立有限时正向夹具证明完整经营，不冒充真实迁移活跃用户。原运行商城8602健康，四类选定来源记录未变化。

- [验证结果](../../implementation/oa-auth/phase-6/P6_TEST_RESULT.md)
- [31项跨进程证据](../../implementation/oa-auth/phase-6/P6_REHEARSAL_RESULT.json)
- [运行与回退](../../implementation/oa-auth/phase-6/P6_RUNTIME.md)
- [生产候选HOLD与历史退出计划](../../implementation/oa-auth/phase-6/P6-07_CANDIDATE_REPORT.md)

P0—P5历史交付保持，P5 Git/CI见phase-5/P5_DELIVERY_RESULT.md。两仓复用原目录feat/iam-p6-migration，OA既有用户改动未触碰；无新worktree。auth233单测+1真实PG/图IT、commerce393项（388通过/5可选跳过）、Python17项和31跨进程检查均完成。auth产品c8df1b1 / CI 36637749220与commerce最终main 31dbdcd / CI 36637949125均SUCCESS。精确记录见[P6交付](../../implementation/oa-auth/phase-6/P6_DELIVERY_RESULT.md)和[CI结果](../../implementation/oa-auth/phase-6/CI_RESULT.md)。私有隔离库、快照、凭据和日志保留，不入库。


CE03当前状态（2026-09-29）：I/U/D0/D1已正常合并推送，两仓远程CI SUCCESS。D1 SDK固定来源遗漏由commerce49274a8修复，CI36667650544 SUCCESS；auth378313c CI36667456414 SUCCESS，失败36667458010保留。D2目录员工页本地DONE：402项397PASS/5skip、99项真实隔离检查（目录11条浏览器、库存/CATALOG各9条），1440/390及错误/创建截图已查看，详见commerce-readiness/CE03_DIRECTORY.md。D2 auth3a8f7e9/commerce5587d53已合并推送，远程CI36668435670/36668436980待查；继续CE04会员纵向切片，CE04—08未实施，生产输入HOLD不变。

CE04-P0本地DONE：251单测、4真实PG+graph IT、Boot4兼容与package PASS，见commerce-readiness/CE04_MEMBER.md。P1基础会员Owner为下一片，P2及其余会员模块未实现。

CE04最新：P0 auth dd07223已推送，CI36668768692 SUCCESS（含D2基线）；D2 commerce CI36668436980 SUCCESS，auth旧run36668435670取消。P1本地DONE：406项401PASS/5skip、实际隔离114PASS（rehearsal-451c622849b4）、36项工具/223入口契约及hygiene无阻断，见CE04_MEMBER.md。下一CE04-P2 READY，后续会员和CE05—08未完成；原63节点DAG与生产HOLD不变。

CE04最新：P1 auth2cb8c11/commerce1bc81f2已合并推送，两仓CI36669783915/36669785165 SUCCESS。P2本地DONE：407项402PASS/5skip、真实隔离137PASS含会员12条浏览器及共享壳回归、当前截图已查看；36项Python/227入口/hygiene无阻断，见CE04_MEMBER。下一CE04-G成长协议/Owner/UI细化；其余会员和CE05—08未完成。

CE04-P2已普通合并推送auth21380a4/commerce359ac02，CI36670574749/36670578849待查。CE04-G0本地DONE（五成长执行能力）：既有251单测+新增Owner1、5真实PG+graph IT、SDK/Boot4/package/hygiene通过，见CE04_MEMBER与CONTRACTS_COMMERCE_GROWTH。下一G1 READY，商城成长业务/API/V53尚未实施。
