# CE05-S 动态人群权限验证

CE05-S0本地DONE：segment六独立HIGH/TENANT_ALL/HUMAN执行能力，create/pump仅集合，其他动作绑定实际正定义版本；仅refresh最长86460秒，其余五项60秒，原Grant/当前路径仍限制实际推进。253全仓单元、16当前真实PG/SpiceDB ExecutionAuthorizationIT、Boot4 SDK1全PASS，零fail/error/skip。最终forceCreation安装只重包不变源码，server320/admin349类、各42资源/3完整模块归档逐字节一致；5源摘要未变，hygiene无阻断、Javaformatter未配置。专用PG库auth_gov_p1_test_526622ff0ef6及数据保留；无Commerce产品/JSON/迁移/依赖变化。Git/精确CI待交付，S1Owner/任务来源和S2页面仍TODO；完整CE05—08/Auth菜单资源目标保持。

## S0验收映射

| 要求 | 当前证据与结果 |
|---|---|
| 六项独立能力/范围/类型 | 每项单能力角色及真实Grant，ScopeCheck完整TENANT_ALL；错类型、指定门店、其他五能力签发或交换拒绝，PASS |
| 原对象事实 | SEGMENT-1/v7，create/pump对象入口拒绝；read/schedule/refresh/control允许可信定义事实，零/负版本及错租户/门店字段拒绝，PASS |
| 持久刷新与同步能力 | 真实86400秒引用、第二个GovernanceRuntime按相同数据库重读成功；86520秒拒绝；其他五能力120秒拒绝，短实时许可不延长原Grant，PASS |
| 原路径与身份 | 原Grant撤销后普通/长刷新引用DENY；重授新Grant原引用无路径；调用环境、代际及到期检查回归；SERVICE不能签发，PASS |
| 全仓/当前执行IT | 全仓44个单元XML总253项0fail/error/skip；本次选择ExecutionAuthorizationIT共16方法、26秒PASS；其他17个目录中的旧Failsafe报告不冒充本次执行 |
| SDK/字节栅栏 | 当前SDK安装后Boot4测试1PASS；最终server/admin、protocol/core/governance三嵌套归档、320/349类及各42资源与当前产物一致；5源码摘要未变，PASS |
| 质量/范围 | diff及hygiene无阻断；Javaformatter未配置，未新增工具/依赖；协议没有可见UI，视觉验收N/A；Commerce Owner/迁移/页面另片验证 |

实际运行：verify session39408 exit0；install13225 exit0仅更新不变代码打包，复用此前253/16测试；Boot4 session45825 exit0。没有把未执行的其他集成测试算入本轮总数。

私密证据：Auth .local/governance/commerce-contracts/segments-core-{source-sha256,implementation-evidence,test-result,runtime-fence,hygiene}.json及verify/install/boot4.log；专用数据库配置0600、不提交凭据。既有PG/权限图、8602/OA、所有旧测试库/数据保持。

implementation-validation COMPLETED/PASS，S0 DONE（本地），Git/精确CI待交付。下一S1仅在S0 GitCI Gate满足后实施原命令/Owner事实与原后台执行来源；S2完整页面和其余CE05—08/Auth资源菜单未完成。契约：[稳定技术切片](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_SEGMENTS.md)。
