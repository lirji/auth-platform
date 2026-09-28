# P2 应用RBAC交付结果

P2-01—P2-08（含P2-05a）本地实现与验收完成。当前正在正常合并推送两仓并观察CI；最终Git/CI记录在本报告末尾更新。P3暂停，未执行生产部署。

## 验收矩阵

| Gate | 实际证据 | 结论 |
|---|---|---|
| G2-01 清单预览、新增不自动授予 | CatalogPostgresIT5、AccessPostgresIT7、HTTP管理检查 | PASS |
| G2-02 租户/应用/环境隔离 | 固定分区角色/Grant、伪造环境/代际负例、商城独立租户SQL过滤 | PASS |
| G2-03 图确认后生效 | 真PG/SpiceDB投影回执、失败保留PENDING、页面待生效→图已确认 | PASS |
| G2-04 无权/撤销/到期/停用拒绝 | GrantGraphIT7、HTTP34、商城13 | PASS |
| G2-05 旧角色不扩权 | 固定版本与DB不可变约束、能力上限/变更版本负例 | PASS |
| G2-06 隐藏菜单不能绕过后端 | 真实页面撤权后隐藏，直调中央DENY，商城撤权403 | PASS |
| G2-07 故障与拒绝区分 | SDK协议/超时专项、待投影/中央故障503，明确拒绝403/DENY | PASS |
| G2-08 真实业务链路 | 真实Casdoor→治理SQL/SpiceDB→SDK→Boot4商城→MySQL门店列表 | PASS |

验证：auth单测209，真实PG58，真实图7；Boot4 SDK兼容1项、6次HTTP交互；治理HTTP34项；商城HTTP13项、非HTTP绕过2项；控制台真实浏览器6组（1440与390视口）。商城完整verify结果及精确CI在末尾。各片TEST_RESULT保留当时证据，本表为最终汇总；P1历史身份IT5未冒充本轮新跑。

质量复核见P2_REVIEW；两仓Code Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无既有Java formatter/静态分析器），编译、测试、diff检查通过。页面截图已实际查看。凭据和日志只在忽略目录；所有建表/字段均有注释。

授权范围：单投影执行者、直接成员、TENANT_ALL、首个内部只读门店入口。多实例/严格远端栅栏/细范围留P3；完整门户P5、迁移P6、生产P7均未执行。共享Casdoor升级HOLD保持，不能声明生产就绪。

恢复资料：[P3_HANDOFF](P3_HANDOFF.md)。用户要求本轮到此暂停，不能自动进入P3。
