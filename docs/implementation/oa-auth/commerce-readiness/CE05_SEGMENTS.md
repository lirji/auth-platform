# CE05-S 动态人群权限验证

## 当前状态与完整目标

CE05-S0已完成协议及Git/精确CI交付；CE05-S1后台本地DONE，implementation-validation COMPLETED/PASS，Git/精确CI待完成。最终真实跨进程已exit0，当前产品及演练源码摘要一致。CE05-S2页面尚未实施，实施依赖S1完整Git/精确CI。全部其余CE05—08、完整岗位权限及Auth实际发布菜单/资源选择仍待继续；122候选能力与34角色快照保持未批量发布。

技术与稳定切片：[CONTRACTS_COMMERCE_SEGMENTS](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_SEGMENTS.md)。用户授权原目录串行实施，无子Agent/新工作树，无生产部署或数据清理。

## S1验收映射

| 验收 | 实际方法与证据 | 当前结果 |
|---|---|---|
| 六项独立能力、真实租户/父定义正版本 | CentralSegmentMySqlTest六能力HTTP矩阵、错类型/零负版本/定义并发变化；SEGMENT集合要求完整TENANT_ALL，create/pump不允许伪对象 | PASS（MySQL与协议适配桩；实际Auth另验） |
| 原任务、幂等及审计事务 | 四并发refresh返回同一run，原创建者不替换；创建/schedule/control与实际定义版本身份审计同事务；旧回执Owner guard；实际MySQL CHECK3819故障令任务、序号、回执整体回滚 | PASS（实际MySQL） |
| 手工原源、准确期限与每批门禁 | 原Reference准确期限和身份/应用/环境/调用方/路由保存；原权限拒绝、到期、重启、下一成员批/公告、五秒提交过期均有SQL断言；已公布快照/Outbox不回退 | PASS（实际MySQL；中央原路径仍须跨进程） |
| 固定系统政策与未来调度分离 | 已批准policy固定定义与调度版本；批准人/人工pump撤权后独立执行，停未来调度/新定义不取消原任务；STOPPED及未知空来源拒绝 | PASS（实际MySQL；实际进程恢复仍验） |
| 真实跨进程Auth/PG/图/Commerce | P6 --segments追加六有限Grant、原回执/撤权重授/重启/期限/STOPPED/实际停服及SQL验证；两轮工具失败保留，最终89f465b97213/37270 exit0，784全PASS、129 segment标签及21准确版本审计 | PASS（实际Auth/SQL/进程） |
| 全仓回归 | 完整verify17981 exit0，70报告504项=499PASS/5既有skip，0fail/error；含13新专项及4原Segment回归 | PASS（等价枚举修正前全仓） |
| 最终受影响验证与制品 | 枚举及有限族常量等价修正后17专项9793 exit0，零fail/error/skip；forceCreation install27947 exit0；13源码与最终JAR摘要一致，1342字节核对PASS | PASS（最终源码） |
| 契约与演练工具 | 271真实HTTP/122候选能力/34岗位快照、9契约及45工具测试；UTC夹具修正后45工具复核PASS；证据文件修正后新增2项回归、47工具PASS，Python语法通过 | PASS |
| 卫生与范围 | diff及hygiene11436 exit0无阻断；Java formatter未配置；无新依赖、公开Actor/JSON/API/UI | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS |
| 可见UI | 本片后台来源与任务治理，SSO页面另属S2 | N/A |

本地MySQL的中央协议桩仅证明适配及Owner事务，不是实际Auth授权证据。完整504项与最终17专项的执行范围分别记录，不声称枚举修正后又执行全仓。

最终Commerce JAR：`9570511ed7bc8e5a4a9b7a537dd893e1694fabb45c5934510fc6b36133f715d6`；17整模块、547模块类/45资源、733应用条目共1342字节核对，13当前源码摘要一致。V65已在专用MySQL应用，SHA256=`07346cdb3c116f413870a55fde5c341e7b6360f56a68fe266b1ad5cc36cb2504`，不可修改；V49—V64保持。

## S1实际失败与复核

首轮真实演练session39819/PID84610、`rehearsal-1f0c4175db87`已exit1，658检查点PASS后在“首批恰好100名”失败。SQL显示任务COMPLETED/processed8，105新夹具成员的UTC DATETIME created_at晚于真实任务startedAt：管理连接继承共享MySQL SYSTEM(+08)，与业务JDBC UTC连接不同。仅将动态人群演练SQL会话设为UTC，保留恰100断言，不修改共享实例、业务代码、截止时间或旧数据。原失败JSON/日志/库保留。

UTC复核session69664/PID88079、`rehearsal-5e74bdf65560`已exit1：全部784实际检查PASS，原手工task固定定义7/snapshot2，实际processed113/matched112；撤权保持原快照/100已提交公告；独立政策、STOPPED、Auth真实短引用到期、未知NULL来源、SQL事务回滚及21条准确版本身份审计通过；实际Auth进程停止后员工read/pump503，固定SYSTEM政策继续完成。最终写证据时碰到O_EXCL：预停服与最终结果误用了同一文件名，故整体仍失败，保留PRE_OUTAGE与历史，不手工转换PASS。

修正为两份独立不可覆盖证据，新增保留预停服文件及未推进不能成功两项回归，47工具PASS。最终完整复核session37270/PID91807、`rehearsal-89f465b97213`、子网126已exit0，784检查点全PASS，129 segment标签。预停服与最终证据两文件均保留，最终phase=PASS、central_outage=REAL_AUTH_PROCESS_STOPPED、system_policy=COMPLETED_WITHOUT_EMPLOYEE_GRANT；实际SQL复核21身份审计元组一致。13产品与3演练源码/JAR摘要一致，正式Validation COMPLETED/PASS。Git交付与精确CI待完成，S2尚未实施。

实际到期测试是`AUTH_ISSUED_SHORT_REFERENCE_NEW_TASK`：Auth签发六秒HUMAN引用，新建专用任务及准确元数据，等待自然到期，再重启实际进程检查拒绝；没有替换既有任务来源，也没有等待正常HTTP刷新86460秒。正常HTTP实际签发/持久化长引用另有真实检查。

本地早期失败亦保留：无mvnw、-am指定测试触发无测试模块、SEGMENT_CREATE审计登记遗漏（产品已修正）、缺实际OPERATOR凭据/恢复车道夹具、MySQL3819对应UncategorizedSQLException及STOPPED后认证时序。没有削弱业务断言。

## S1源码审查

由同一Agent串行审查正确性、并发/事务、持久化和恢复四个维度，不声称多Agent独立审查。

- 原来源只在run创建时写入，mapper无来源更新；Commands旧回执调用Owner来源核验，活动任务复用仍检查原源，新的刷新/控制资格不能复活旧任务。
- 当前定义/调度锁/输出快照版本各自独立；控制从实际run读取父定义，身份审计记录原定义正版本，无伪门店；中央稳定identity进入意图hash，执行nonce不进入hash。
- 每批开始实际scope/resource检查，事务末再次核对原引用期限、五秒准入与共享路由锁；根/任务行锁、唯一活跃约束及原检查点/Outbox幂等保持。STOPPED与提交串行，已提交快照不撤销。
- SYSTEM政策来源由营销Owner核对固定版本与原调度命令，运行时只检查已接管分区/路由及五秒截止；不伪造ADMIN、员工Grant或ALLOW缓存。旧NULL源CENTRAL拒绝，显式对账留CE08。
- V65中文表/字段注释与JSON/正版本约束齐全；持久化来源表由runtime拥有，营销Owner消费协议而不越层写入；无Token持久化、依赖升级或第二套调度框架。

当前未发现需要修复的源码问题；跨进程必需Gate已实际PASS，前两工具缺陷修复及回归保留。未验证生产容量、灾备与原环境切换；来源保留治理/历史对账沿CE08，不把本片通过当完整目标完成。

## S0已完成证据

S0六独立HIGH/TENANT_ALL/HUMAN能力；create/pump仅集合，其他对象实际正定义版本。仅refresh最长86460秒，其余五项60秒，不延长原Grant；错资源/范围/版本/非HUMAN/交换/超期限均拒绝。原撤权重授、目录/身份代际、环境及期限保持拒绝，第二个GovernanceRuntime按实际数据库重读长引用通过。

全仓44单元XML共253项、当前ExecutionAuthorizationIT真实PG/SpiceDB16项、Boot4 SDK1项全PASS，零fail/error/skip。verify39408 exit0、install13225 exit0仅重包不变源码、Boot4 45825 exit0。server320/admin349类、各42资源、各3嵌套模块与当前reactor字节一致；5源码摘要一致，hygiene无阻断、formatter未配置。其他旧集成报告不冒充本轮执行。

S0产品`ad5ce112d34758b691c0b379aa2c879f24563c38`已正常提交、任务分支推送、ff合并推main；精确[Auth CI36967401511](https://github.com/lirji/auth-platform/actions/runs/36967401511) completed/SUCCESS。

## 证据与恢复

Commerce私密 `.local/central-audiences/segments-owner-*`保存全仓快照、最终专项、运行字节栅栏及hygiene；Auth `.local/governance/commerce-contracts/segments-owner-*`保存实施、失败与复核工具证据，真实run目录保存逐项检查点/SQL与受控身份配置。S0证据为Auth `segments-core-*`与专用PG库auth_gov_p1_test_526622ff0ef6。全部旧库/数据/8602/OA/dev_infra/工作树/证据保留，不清理、不切换生产。

## S1正式验证与交付门禁

CE05-S1本地DONE，implementation-validation COMPLETED/PASS：六SEGMENT独立能力、实际正父定义、原命令身份审计、原手工来源与固定独立SYSTEM政策通过。最终真实89f465b97213/session37270已exit0，784检查点全PASS（129 segment标签）；SQL再次核对21条准确版本审计，手工定义7/快照2 COMPLETED、processed113/matched112，撤权后保留100已提交公告；policy和实际Auth停服outage任务固定定义7/快照1、113/112、公告完成。实际Auth员工read/pump503，独立政策无员工Grant继续完成。13产品源/最终JAR/3harness源摘要一致；完整504=499PASS/5既有skip与最终17专项、47工具/9契约、271入口/122能力/34角色未发布、CI YAML/新增2证据回归及两仓hygiene无阻断（formatter限制）。V65已应用不可改、原数据保留。前两失败658时区及784证据O_EXCL全部保留，未手工转换失败。正式Git/精确CI待完成，S2与全部其余CE05—08/Auth菜单资源目标保持active。

当前正式TEST_RESULT为Auth私密 `segments-owner-test-result.json`；每条验收对应上方矩阵，Gate=PASS。源码审查由同一Agent串行执行；不把47工具/2证据桩当作真实Auth证明。CI已将本片helper语法及证据回归接入既有流水线，YAML解析及同命令2测试PASS；远程实际CI仍待Git后核验。
