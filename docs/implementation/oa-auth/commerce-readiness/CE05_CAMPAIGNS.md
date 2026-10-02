# CE05-CAM 活动与预算验证

原已批准CE05权限切片的技术细化见[活动契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_CAMPAIGNS.md)。当前CAM0本地DONE，Git/CI待交付；CAM1业务和CAM2页面尚未实现，原CE05—08及auth菜单资源展示目标不缩小。

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

implementation-validation COMPLETED/PASS；CAM0 DONE（本地）。Git/CI待交付，CAM1只有该门禁完成后开始实施；生产目标/映射/Owner/部署授权沿原HOLD。
