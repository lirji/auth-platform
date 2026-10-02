# CE05-S 动态人群员工权限技术细化

消费已批准[完整扩展契约](CONTRACTS_COMMERCE_EXPANSION.md)的六项segment能力和后台要求，复用真实SegmentApi/SegmentService/SegmentMapper、MemberGrowthApi、Commands、Outbox与既有公平车道。用户已授权连续实施完整CE00—CE08；本文件细化技术边界，不增加业务动作、审批或基础设施。

## 稳定切片与Gate

| ID | 可观察结果 | Needs / Owner | 验收与状态 |
|---|---|---|---|
| CE05-S0 | 六独立能力的有限执行引用，类型/原路径/期限有界 | CAM2精确CI；Auth protocol/governance/SDK | 真实PG/图独立Grant、scope/object、跨类型/错版本/互换/期限/HUMAN/撤权重授/跨进程；DONE（本地；Git/CI待交付） |
| CE05-S1 | 中央SEGMENT用例、原命令审计、持久化任务来源与每批判权 | S0验证/Git/CI；Commerce runtime/marketing/app/persistence | 实际MySQL/HTTP、并发刷新返回原任务、原键、SQL故障回滚、分页/批预算、重启/撤权/期限/503及公告恢复；READY_AFTER_S0_CI |
| CE05-S2 | 固定SSO定义/运行记录、创建/调度/刷新/控制/推进完整反馈 | S1验证/Git/CI；Commerce frontend/app | 真实DTO、六独立权限、实际任务/快照/公告结果、unknown原键、401/403/503、1440/390/320关联弹层与键盘；TODO |

三片原目录串行，无子Agent/新工作树。CAM2精确CI已SUCCESS（Auth36966260251/Commerce36966235609），S0本地验证DONE，Git/CI待交付，S1尚未实施，S1/S2不能借未发布协议编造执行成功。

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
