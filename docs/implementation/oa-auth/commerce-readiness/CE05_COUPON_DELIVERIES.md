# CE05-D 定向发券实际验收

当前D0完整DONE，正式implementation-validation COMPLETED/PASS；产品a766cd083dae95a4637f8bb42c1dde87988f6397已正常任务分支push/ff main/main push，精确[Auth CI36991997907](https://github.com/lirji/auth-platform/actions/runs/36991997907) completed/SUCCESS。D1中央Owner/持久来源与D2实际SSO页面未实现，完整其余CE05—08与Auth实际发布菜单资源选择仍active。

权威[技术契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_COUPON_DELIVERIES.md)细化四项已批准能力，不新增业务动作或基础设施。S2依赖已完整DONE：Auth d35e6d5/CI36989740786、Commerce2946279/CI36989725617均精确SUCCESS；S2状态元数据Auth b3000ad/Commerce33e4d23已正常推main，后者精确CI36990563014也SUCCESS。

## D0实现与独立验证

原目录任务分支 `feat/commerce-coupon-delivery-execution`，基线Auth b3000ad。修改五个直接相关源/测试：ScopeDtos、ScopeResourceBindings及其测试、ExecutionAuthorization及其真实集成测试。不改变JSON、执行表结构、迁移、依赖、Commerce代码或实际角色发布。

| 验收 | 实际结果与证据 |
|---|---|
| 四项独立能力 | create/read/control/pump各用实际单能力manifest/role/Grant及独立测试分区；真实PG/SpiceDB17方法全PASS |
| 集合与对象 | create/pump仅集合，read/control对象正内容事实；伪store/类型/版本0/租户/其他营销能力拒绝，PASS |
| 期限 | create/control签发604859秒引用并拒绝604920秒；read/pump拒绝120秒；当前Grant截止仍短于长引用，PASS |
| 身份和边界 | HUMAN签发；SERVICE/错误应用/调用方/环境/代际拒绝，PASS |
| 原路径/持久读取 | 新GovernanceRuntime读取准确原引用；撤权、重授均不恢复旧Grant路径；测试库显式置为到期后拒绝，PASS |
| 全仓与SDK | 44个当前日志套件/254单元、17专项真实集成、Boot4 SDK1，全部零fail/error/skip，PASS |
| 最终运行归档 | forceCreation install；server320/admin349类及各42资源和三个完整core/governance/protocol依赖归档逐字节一致；五源码摘要稳定，PASS |
| Hygiene | CODE_HYGIENE/REPO_CONVENTION/DIFF_HYGIENE无阻断；既有Java canonical formatter未配置限制保留 |
| 可见UI | D0仅协议及有限执行，N/A；D2真实编译页面/关联弹层实看另行必需验收 |

本次实现、源码审查与单独Validation由同一Agent顺序执行，不宣称多模型独立评审。到期测试明确在专用测试库将原execution_reference置为到期，不冒充已等待七天；D0只验证可信Owner事实协议，实际批次不可变内容版本由D1持久化。

专用PG库 `auth_gov_p1_test_b2d93782a9ac` 位于既有dev_infra PostgreSQL16，角色及数据保留；图使用既有独立P3服务18544及随机测试租户/应用分区，不重置共享图。配置/密钥不入库外公开日志或Git。

## 证据与恢复

Auth私密 `.local/governance/commerce-contracts/` 保存 `coupon-delivery-core-unit-result.json`、`coupon-delivery-core-it-result.json`、`coupon-delivery-core-source-sha256.json`、`coupon-delivery-core-runtime-fence.json`、`coupon-delivery-core-implementation-evidence.json`、`coupon-delivery-core-test-result.json`、构建/SDK/hygiene日志及测试库配置。归档核对首轮误用不存在的graph模块名的拒绝记录单独保留，按实际JAR中的core依赖更正后全字节通过；未将首轮拒绝改写为成功。

D0 Git/精确CI已通过，D1 READY；D1须区分ISSUE和首次REVOKE原来源、保留重复REVOKE方向、实际CAS与不可变内容事实，并证明每位收件人事务/撤权/到期/Auth503。D2需独立真实岗位和当前截图。原8602/OA、所有旧迁移/数据/卷与S2失败证据保留，无生产部署或清理。

D1实施前已按实际Servlet顺序细化：POST创建/控制取得有限上限引用，Owner独立限制原Create.deadline；来源保存准确中央expiresAt，GET资格提示仍60秒且不登记长来源。只是落实既有有限窗口，D0产品协议/测试源码保持不变。
