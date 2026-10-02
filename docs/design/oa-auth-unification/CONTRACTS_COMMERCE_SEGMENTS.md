# CE05-S 动态人群员工权限技术细化

消费已批准[完整扩展契约](CONTRACTS_COMMERCE_EXPANSION.md)的六项segment能力和后台要求，复用真实SegmentApi/SegmentService/SegmentMapper、MemberGrowthApi、Commands、Outbox与既有公平车道。用户已授权连续实施完整CE00—CE08；本文件细化技术边界，不增加业务动作、审批或基础设施。

## 稳定切片与Gate

| ID | 可观察结果 | Needs / Owner | 验收与状态 |
|---|---|---|---|
| CE05-S0 | 六独立能力的有限执行引用，类型/原路径/期限有界 | CAM2精确CI；Auth protocol/governance/SDK | 真实PG/图独立Grant、scope/object、跨类型/错版本/互换/期限/HUMAN/撤权重授/跨进程；DONE（含Git/CI） |
| CE05-S1 | 中央SEGMENT用例、原命令审计、持久化任务来源与每批判权 | S0验证/Git/CI；Commerce runtime/marketing/app/persistence | 实际MySQL/HTTP、并发刷新返回原任务、原键、SQL故障回滚、分页/批预算、重启/撤权/期限/503及公告恢复；DONE（含产品Git/CI） |
| CE05-S2 | 固定SSO定义/运行记录、创建/调度/刷新/控制/推进完整反馈 | S1验证/Git/CI已完成；Commerce frontend/app | 真实DTO、六独立权限、实际任务/快照/公告结果、unknown原键、401/403/503、1440/390/320关联弹层与键盘；DONE（含产品Git/精确CI） |

三片原目录串行，无子Agent/新工作树。CAM2、S0与S1验证及精确产品CI已SUCCESS；S2依赖门禁已满足，页面和五独立提示已实现，最终编译JAR真实六岗位/撤权/Auth停服与60张当前关联截图已通过正式Validation，产品Git/精确CI已完成。历史验证检查点在下文保留，当前权威状态见[动态人群验证](../../implementation/oa-auth/commerce-readiness/CE05_SEGMENTS.md)。

## Owner事实与既有业务行为

源码权威路径：Commerce marketing-runtime/src/main/java/com/lrj/commerce/campaign/segment/{api/SegmentApi,application/SegmentService,infrastructure/persistence/SegmentMapper}.java、resources/mappers/campaign/SegmentMapper.xml、commerce-app/http/marketing/segment/SegmentController.java以及不可变V20迁移。

- 定义是不可变正version；root.current_version指向最新定义，root.lock_version用于schedule.expectedVersion。run.definition_version固定扫描规则，run.snapshot_version为输出人群快照，三者不得互换。
- 创建仅允许新递增定义，关闭未来周期触发；TTL300—86400秒、refreshSeconds为0或60—TTL、maxMembers100—100000沿原业务校验。可信RuleNode字段不编造会员属性；当前额外禁止orderAmount。
- schedule启停依原锁版本CAS；refreshSeconds=0不允许启用周期。关闭调度只停止未来触发，不取消已开始的任务。
- refresh在已有RUNNING/ISOLATED任务时返回原任务；不能更换其创建者、原引用或规则版本。新任务的成员投影/检查点同事务，完成后快照头使完整结果可见。
- cancel只允许RUNNING/ISOLATED，不删除不可见投影或撤销既有结果；retry只恢复未过期ISOLATED检查点；retry-announcement只恢复原完成结果的隔离入组公告。
- 一租户一轮最多启动一个任务，候选至多3；单批100名会员/100条入组公告，既有3租户候选、30步/500ms轮转保留。正常失败5次隔离，瞬时故障延后不计次数，截止到期继续由原Owner处理。

## 六个独立业务入口

全部commerce.segment.*、HIGH、segment资源、首批TENANT_ALL，不隐含member/audience/rule.read或其他segment能力。

| 方法/路径 | 能力 | Owner授权事实 |
|---|---|---|
| GET /v1/admin/segments | segment.read | 完整租户集合；SQL稳定分页前门禁，返回前再核对 |
| POST /v1/admin/segments | segment.create | 新对象只能集合；原命令/定义/根/身份审计同事务 |
| POST /v1/admin/segments/{id}/schedule | segment.schedule | 实际segmentId及当前正definition version；锁版本仍独立CAS |
| POST /v1/admin/segments/{id}/refresh | segment.refresh | 实际segmentId及当前正definition version；持久化原手工来源与引用 |
| GET /v1/admin/segments/{id}/runs | segment.read | Owner先读取真实父定义，再查询该租户/人群的有界记录 |
| POST /v1/admin/segments/pump | segment.pump | 完整租户人工推进资格；每个任务仍检查它自身的原执行来源 |
| POST /v1/admin/segment-runs/{id}/{cancel\|retry\|retry-announcement} | segment.control | Owner读取真实run.segmentId及run.definitionVersion，不把runId当segment；原源路径与控制资格分别核验 |

未知动作拒绝，不以请求字符串拼能力。旧ADMIN只在未接管且从未CENTRAL的SEGMENT路由保持兼容；CENTRAL/STOPPED不能回退，客户/member和平台接口保持各自身份边界。

## S0有限执行协议

稳定MARKETING_SEGMENT_RESOURCE_TYPE=segment；沿用Issue/Reference/Check/ScopeCheck，没有JSON或表结构变化。仅HUMAN可以签发六项，能力与调用应用精确前缀绑定。create/pump只集合，其余支持真实Owner对象；对象事实正不可变定义版本、无伪门店/部门/会员归属。

仅segment.refresh允许最长86460秒的持久执行引用，对应原最大任务TTL86400加60秒请求余量；其余五项仍最多60秒。该上限只限定引用生命周期，不延长原Grant或当前判权有效期；每次开始/批次/恢复/公告仍取当前授权与签发原路径交集。到期、撤权后重授、代际、应用/环境/调用方改变均不得复活旧引用。不能借catalog.operate或其他能力执行segment。

S0测试须独立证明refresh能持久重读及超过60秒，并拒绝超过86460；其他五项拒绝120秒。测试原Grant须覆盖预期观察期限，不能用过期测试夹具假称撤权。

## S1后台与数据库边界

用户手工refresh持久化受限Actor/中央execution ID与分区、身份代际、截止及明确来源；Token不落库。开始、每批成员扫描/最终快照发布、进程恢复、人工重试和未提交公告均核验原来源；新的pump/control Grant不能覆盖或替换原refresh路径。权限拒绝停未提交批次，已公布快照/已提交Outbox不因此回退。SQL与审计继续在Owner事务，远程判权保持有界。

周期刷新属于已启用且版本化的业务调度政策，独立记录政策来源与固定定义；不会借用某次人工pump的员工权限成为其创建者。新定义/关闭schedule只停止未来启动，原已开始任务的固定版本与截止语义保持。旧空来源在CENTRAL下不能猜作已批准系统政策，须由CE08显式对账；LEGACY路由原任务保持兼容。真实政策有效条件和手工/系统来源必须在S1实现前与现有周期Owner路径逐项核对，不能凭nullable字段存在自动放行。

如需来源持久化，只追加V64之后新迁移、中文表/列注释和必要一致性约束，保留V20/V49—V64。定义/调度/命令/任务/身份审计同事务；后台快照及成员Outbox遵循原检查点与幂等。旧数据不清空，原8602/OA、dev_infra和其他任务数据不切换。

## 验证与完整目标

S0纯协议UI验收N/A；须真实PG/SpiceDB、全仓单元/SDK Boot4及当前运行归档字节验证。S1/S2保留真实SQL和最终编译浏览器验证，不用fixture证明实际权限；每片需正式Validation、进度及精确Git/CI。完整CE05发券/旅程/效果、CE06/07/08及Auth实际发布菜单/资源选择仍待完成；122候选能力与34角色快照不等于已经全部实现或发布。


## S0最终本地验证

CE05-S0本地DONE：segment六独立HIGH/TENANT_ALL/HUMAN执行能力，create/pump仅集合，其他动作绑定实际正定义版本；仅refresh最长86460秒，其余五项60秒，原Grant/当前路径仍限制实际推进。253全仓单元、16当前真实PG/SpiceDB ExecutionAuthorizationIT、Boot4 SDK1全PASS，零fail/error/skip。最终forceCreation安装只重包不变源码，server320/admin349类、各42资源/3完整模块归档逐字节一致；5源摘要未变，hygiene无阻断、Javaformatter未配置。专用PG库auth_gov_p1_test_526622ff0ef6及数据保留；无Commerce产品/JSON/迁移/依赖变化。Git/精确CI待交付，S1Owner/任务来源和S2页面仍TODO；完整CE05—08/Auth菜单资源目标保持。

详细映射见[动态人群验证](../../implementation/oa-auth/commerce-readiness/CE05_SEGMENTS.md)。


## S0最终交付

S0 Git/CI已完整DONE：ad5ce112d34758b691c0b379aa2c879f24563c38正常提交、任务分支推送、ff合并推main；精确Auth CI36967401511 completed/SUCCESS。S1已进入实际Owner实施，S2和全部其余CE05—08/Auth菜单资源仍未完成。


## S1原来源技术实现（2026-10-01，验证中）

只登记六项SEGMENT能力和既有九个HTTP方法路径，不新增业务动作或对外DTO。Commerce的SDK来源固定S0完整提交ad5ce112d34758b691c0b379aa2c879f24563c38；首批仍完整租户范围，对象使用实际正定义版本。

- 中央认证仅对segment.refresh请求最长86460秒引用。实际Reference.expiresAt、身份/成员代际与版本、Auth租户/业务租户、应用/环境/调用方和原路由保存到运行时拥有的employee_segment_execution；无Token，不保存ALLOW。Actor/公开DTO不扩字段。营销Owner在新任务中复制准确来源，恢复仍核对原记录与原引用。
- 来源明确为MANUAL、SYSTEM或LEGACY；marketing_segment_run.execution_source_json仅在创建时写一次。活跃任务返回及旧刷新回执都复核原来源，新的控制/推进/刷新资格不能替换创建者。Commands复用现有事务与回执，追加Owner旧回执核对回调，不建设新的命令框架。
- 成功启用schedule保存Policy（租户、定义版本、调度CAS后版本、批准主体/路由、时间及原命令键）。周期启动先验证真实启用root与Policy版本并复制入任务；之后的成员批次与公告沿固定Policy。停止未来调度/新定义清除root未来政策，已启动来源保持原版本。批准人的短引用/后续Grant撤销不取消已提交独立周期政策；这是既有系统责任，不添加审批。
- 系统政策通过专用SEGMENT路由许可，不伪造ADMIN或员工Grant。CENTRAL必须与批准Auth分区相同、未STOPPED且路由版本不早于批准版本；提交前共享锁及最长五秒窗口与停止串行。空批准路由/旧空来源只允许从未接管的LEGACY/SHADOW，CENTRAL不能猜作系统任务。
- 手工来源每次开始、批次、恢复、控制和公告重查原segment.refresh集合及实际固定父定义；身份/原路由、准确期限保持，原路径由Auth再取交集。单次准入最长五秒，批次/快照/Outbox提交前再次检查截止及路由锁；快照TTL维持原业务期限，不缩短为准入窗口。
- V65追加SEGMENT族、segment正版本审计约束、来源表及两处JSON来源约束，中文注释齐全。V65已在专用真实MySQL应用，SHA256=07346cdb3c116f413870a55fde5c341e7b6360f56a68fe266b1ad5cc36cb2504，今后不可修改；V49—V64保持。旧源不回填为中央政策，显式对账仍属CE08。

本地Owner当前验证通过：完整504项499PASS/5既有skip，最终13专项及4既有Segment回归PASS；编译归档与13源码摘要一致。实际MySQL早期审计登记/夹具失败已修正且证据保留。真实P6首轮658后因管理连接+08写UTC DATETIME夹具而失败，仅本片演练SQL会话改UTC，保持首批100断言；UTC复核69664/5e74bdf65560全部784实际检查通过，整体因最终O_EXCL证据文件冲突exit1；已分开预停服/最终文件并新增2回归，47工具PASS；最终37270/89f465b97213已exit0、784全PASS，SQL21准确版本审计/原来源与实际Auth停服已核；正式Validation PASS，S1本地DONE，Git/精确CI待完成；S2及完整目标保持。


## S2页面与提示技术细化（已批准；实施中，尚未完成验收）

沿既有Craft主题、中央SSO、RequestContext、RuleEditor/RuleSummary和AntD居中Modal，不引入设计系统/新业务动作。固定 `/operations/segments?tenant_id=<UUID>`加入现有营销导航与CentralPageController，匿名仅提供固定GET壳，业务继续鉴权；不得任意路径forward。

- 目录页签：实际View.content定义摘要、audienceId、enabled、lockVersion；定义version/调度锁/输出snapshotVersion分栏。50项稳定segmentId游标；403为拒绝，空数据才为空态。选中定义展开规则及TTL/refresh/maxMembers；运行记录按实际segmentId调用原runs入口，50项runId游标。运行详情显示原定义/快照、processed/matched、状态/attempts/error、截止、入组公告状态；不推断Source.kind或“已提交政策”，公开DTO没有这些字段。
- 创建页签：segmentId、正递增version、name、RuleNode、ttlSeconds(300—86400)、refreshSeconds(0或60—TTL)、maxMembers(100—100000)，真实结构化字段及memberOnly RuleEditor；复用已有封闭规则字段/类型/操作符，不硬编码会员或演示业务数据，不依赖rule.read/member.read。新定义关闭未来调度的既有语义明确展示。
- 操作页签：独立schedule/refresh/control/pump资格。五个字面GET `/v1/operations/segments/{create|schedule|refresh|control|pump}-access`绑定对应现有能力，只返回ActionAccess.allowed，不返回可复用许可，无命令/审计写入。目录read直接由原GET判权；没有合并资格或根据动态请求拼能力。提示只能控制可用界面，实际POST重新检查原范围/对象/状态。
- schedule输入实际segmentId、expectedVersion锁、enabled；可以从已获read的目录带入真实值，也可直接输入而无需read，不能伪造已核验目标。refresh输入segmentId，成功显示实际返回run，不把受理任务当快照完成。control输入实际runId及有限cancel/retry/retry-announcement，不把runId当父segment；结果显示服务端实际原版本，原来源拒绝时不换创建者。pump单次严格有界，当前资格不能代替任务来源。

创建、调度、刷新和控制冻结原key/body/path；busy/unknown保留原意图，重试不换键；确定409允许调整输入，成功仅接受结构匹配的实际回执。切Tab/取消退出保留unknown意图，未保存表单关闭确认，401隐藏全部敏感数据；403隔离对应能力，503无旧ADMIN回退并可重查。

pump原接口没有幂等命令键，不能宣称未知结果可按原键保证仅推进一次。未知响应提示可能已提交部分批次，保留待确认状态，不自动重发；有read可查实际任务记录，无read须显式确认后才能继续下一次有界推进，不能请求额外read或伪造当前检查点。

所有关联表单/任务详情/确认沿居中Modal，标题与底部动作固定、正文内部滚动，桌面1440、手机390/320正文无横向溢出，宽表内部横滚。保持Esc/焦点恢复、同页/切Tab/退出保护；定义和运行游标/目标保持URL上下文，不把服务端来源/结果写入本地账本。

S2验收：五提示各独立200/403/401/503、提示不写审计；真实MySQL定义/调度CAS/运行控制/SQL回滚/原键；最终编译JAR真实PKCE六类能力与独立组合、撤权/原源拒绝/401/真实Auth503，真实SQL核对实际版本/任务/快照/Outbox及无重复审计；1440/390/320目录、创建、调度、刷新回执、运行详情、控制、pump未知确认、409/unknown/关闭保护和键盘截图实际查看。既有中央页面回归、HTTP清单/候选122/角色34、构建/格式/字节栅栏及Git精确CI均必须通过。五GET提示加一SPA入口预计271→277，实施时扫描实际源码核实，不以设计计数冒充实现。

## S1最终产品交付

S1完整产品Git/CI DONE：Auth e7c54e45409510e2bdad619c737c59f5deadd805/CI36973121732、Commerce de93c5264cd82f44afb35fdd050190b7df924bf2/CI36973103953均completed/SUCCESS，head精确核对；两个产品已正常提交、任务分支推送、ff合并推main。最终37270/89f465b97213 exit0/784PASS及21真实正版本审计、13源码/JAR与3演练源码保持。本轮收尾仅交付状态元数据，不把纯文档提交当新产品CI。S2已满足产品依赖门禁，完整S2及其余CE05—08/Auth菜单资源目标active；V65不可改，原数据/失败/恢复证据保留，无新工作树/Agent。
