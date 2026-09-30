# 当前商城扩展续做

2026-09-29：用户批准剩余执行计划，并选择本轮扩展其他商城模块、先补能力与权限契约。当前[执行记录](../../implementation/oa-auth/commerce-readiness/EXECUTION_PLAN.md)为续做入口；[扩展契约](CONTRACTS_COMMERCE_EXPANSION.md)业务边界已获批准：员工端、会员营销按租户/库存交易按门店、高风险独立权限且沿用现有审批；继续CE-02。CATALOG页面接入与迁移审核工具本地验证通过（commerce388通过/5跳过、Python22项、隔离34项）；commerce 41c22ae产品/4b12706进度及auth aaf7c18/ece04d0已正常合并推送main，两仓初轮远程CI成功（auth36660687852、commerce36660768385）；补充完整密码/PKCE后本地34项再次通过，最终CI auth36661541739、commerce36661542743均SUCCESS；未执行生产部署或真实迁移，原DAG保持63节点。

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
