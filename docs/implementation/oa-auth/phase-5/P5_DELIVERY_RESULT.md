# P5 Delivery Result

P5-01..07已完成实现、真实验收和Git/CI交付。三个仓库均在原目录使用`feat/iam-p5-portal-pilots`，分片提交后正常fast-forward合并并推送各自`origin/main`，没有强推、PR、tag/release或生产部署。P6未执行。

| 仓库 | 已推送产品版本 | 本轮提交 |
|---|---|---|
| auth-platform | `e26b256e468578eef4a7dd86d21304b20c242cbe` | 74a39c1 P501；9ef3288 P502；b6461de P503；159eac6 P504；7d8682d P505；a73b0d1 P506；e26b256 P507 |
| commerce-platform | `c7384fe487d875e1f90254be1283ebe2adf0098c` | 1c02a6c商品Owner读写；c57441b独立限时export；ca590be测试退避窗口；c7384fe登录恢复/未保存保护及最终验收；0f7bd6b同步最终进度（当前main，额外完整CI也通过） |
| oa-platform | `6385a869e234b635c44e5b96f75e48133b3a1390` | 56497cb固定申请依据及真实待办；6385a86权限版本局部命名对齐门禁 |

基线分别为auth89a3537、commerceca4f831、OA10c1348。正常推送授权来自用户AGENTS持续授权；三仓branch protection查询均返回未配置，没有绕过保护。最终流水线按上述精确产品SHA记录在[CI_RESULT](CI_RESULT.md)，不能用历史main成功状态替代。

本文件和最终进度属于auth后续纯文档提交，产品/测试/CI配置与e26b256一致；其提交ID由Git日志给出，不制造自引用SHA。文档提交不重新宣称执行了产品回归。

## 交付内容与验证

- 工作台、应用/角色/范围授权、邀请、申请通知、OA审批依据、权限来源/诊断和实际回收回执；内部商品资料受控写；外部门店商品查询和独立限时导出。
- P506真实72 HTTP、7外部/门户和3 OA浏览器、10 MySQL测试；P507真实三客户端交互登录/SSO、跨audience/Cookie/CORS拒绝、停机恢复、同源JAR/深链/过期/开关；内部最终25 HTTP和7浏览器通过。
- auth本地231单测+101 PG；商城388项中383通过、5性能profile条件跳过；OA最终125前端测试及构建通过。各片报告列出失败重跑原因，不把跳过或失败截图当通过。
- Code Hygiene无阻断，既有全仓formatter不可用限制保留；商城本片前端Prettier、各构建/语法/diff检查通过。没有生产性能达标承诺。

## 工作目录与资源核查

- auth、commerce当前无本任务未提交/未跟踪源码。OA原有未提交`CODEX_PROGRESS.md`、`docs/design/identity-authz-governance/PROGRESS_STATE.md`和未跟踪`DEPLOYMENT_RESULT.md`、`tmp/`、`.local/`保留，未暂存/覆盖。
- 本轮未创建worktree。原有commerce/OA的只读P0基线工作树位于auth `.local/p0-baselines/commerce`、`.local/p0-baselines/oa`，保留历史基线用途；任务分支已合并保留，没有删除分支或工作树。
- 所有本任务JVM/Vite已退出；P4独有workflow/kafka/postgres容器已停止并保留卷。其他共享或先前IdP/图服务未动。
- 可再生构建目录`target/`、`dist/`、`__pycache__/`可按需清理；本轮未清理。`.local`含私有凭据、复查证据和历史基线，测试数据库/独有P5客户端保留，不混同为可直接删除的缓存。
- 共享Casdoor8000升级HOLD不变；P5只验证独立18090。运行与回退说明见[P5_RUNTIME](P5_RUNTIME.md)。

下一步：本轮交付结束，在P6前停止；后续只有新授权才进入目录接管/影子比较及单写切换。
