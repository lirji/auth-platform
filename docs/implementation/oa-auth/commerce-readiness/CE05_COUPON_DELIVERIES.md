# CE05-D 定向发券实际验收

当前D0完整DONE，正式implementation-validation COMPLETED/PASS；产品a766cd083dae95a4637f8bb42c1dde87988f6397已正常任务分支push/ff main/main push，精确[Auth CI36991997907](https://github.com/lirji/auth-platform/actions/runs/36991997907) completed/SUCCESS。D1中央Owner/持久来源本地DONE，正式Validation PASS，产品Git/精确CI待完成；D2实际SSO页面未实现，完整其余CE05—08与Auth实际发布菜单资源选择仍active。

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


## D1实际Owner与验证

原目录Commerce `feat/commerce-coupon-delivery-owner`（基线33e4d23），Auth `feat/commerce-coupon-delivery-owner-rehearsal`（基线688ba4f）。四能力独立，create/pump仅集合；read/control使用实际父批次内容版本1，公开进度CAS仍从0开始。新V66固定批次内容事实、准确有限引用元数据与ISSUE/首次REVOKE双来源，无Token或ALLOW缓存。

原创建/控制回执先核验当前资格及原方向；旧CANCEL在后来REVOKE之后仍核验原ISSUE。首次独立撤回可在原发放失效后发起；之后RETRY/重复REVOKE保留首次撤回来源。单收件人远程准入在事务外，事务内持路由共享锁、批次行锁；提交前保留原引用/短准入窗/发放deadline检查。保留原最多20收件人、500ms及后台200步预算，未放宽测试断言。

| 验收 | 当前实际结果 |
|---|---|
| 实际MySQL/HTTP | 14新增中央专项+7既有发券测试，共21 PASS，零fail/error/skip；四独立能力、旧ADMIN/STOPPED拒绝、实际父版本与代际、原键与旧方向、UNKNOWN兼容分别验证 |
| 原效果事务 | 实际券SQL后等待短期限、发放业务deadline到期；券/频控/收件人/检查点/发行计数和新批次命令/身份记录回滚。远程SDK调用断言业务事务未开启 |
| 全仓与制品 | 71当前XML共520项，515PASS/5既有条件skip，零fail/error；forceCreation最终JAR731b76865ed7、17内部归档、编译类/资源与SDK a766内容一致；15 Commerce/4 Auth工具源码摘要稳定 |
| 真实Auth联调 | 自有隔离`rehearsal-fbefa05c7b47`，runner78608/rehearsal78609实际exit0，878检查点（88发券）全PASS；无浏览器，D2另验 |
| 原方向与撤权 | 第一轮实际9发放、随后重启完成A21；C首轮8发放后原Grant撤销/重授，继续0并隔离；A首轮7撤回后首次撤回Grant撤销/重授，继续0并隔离；新独立C撤回完成原8张 |
| 终态SQL | 恰5原能力/批次身份元组，每项一次，内容版本1且store为空；29收件人、15 REVOKED/14 AVAILABLE，未处理券及ISSUE/首次REVOKE来源保持；停服批次RUNNING/processed0/attempts0/DEPENDENCY_UNAVAILABLE |
| 到期与停服 | 实际Auth签发六秒引用用于明确新建专用任务，背景已执行到期拒绝，零效果/无虚假REVOCATION_DONE；不替换旧POST来源、不假称等待七天。真实Auth停止后read/pump503，后台原来源不变 |
| 迁移/卫生 | V66在本次新隔离库成功应用，checksum579464248，表及6字段中文注释齐全；旧V31/V49–65无改动。两仓hygiene无阻断；事务边界与已命名分页常量advisory已逐条评审，既有formatter/静态分析未配置限制保留 |

源码审查与单独Validation同一Agent顺序执行，无多模型独立审查声明。首轮缺地址密钥/夹具/AOP设置/历史租户轮转及全仓单轮预算失败均保留；通过已持有锁下减少重复SQL解决额外开销，未删除失败、放宽断言或改变20/500ms预算。原8602/OA、所有旧数据/卷/测试库不切换；自有六端口已释放，无生产部署。

正式私密`coupon-delivery-owner-implementation-evidence.json`、`coupon-delivery-owner-test-result.json`（COMPLETED/PASS）、`coupon-delivery-owner-real-result.json`记录15+4源码/JAR、实际SQL与终态；Commerce私密`coupon-delivery-owner-selected-current-result.json`、`coupon-delivery-owner-full-result.json`及报告目录保存21/520当前报告，所有旧FAIL独立保留。产品Git/精确CI待完成，D2及所有剩余CE05—08/Auth选择目标继续active。
