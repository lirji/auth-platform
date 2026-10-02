# CE05-J 旅程及营销效果中央权限技术细化

状态：BUSINESS_APPROVED / 本片 LOCAL_VALIDATED_DONE（远程集成由主流程收尾）。2026-10-02 用户明确授权完整并行执行；本片仅在指定 auth/commerce 隔离任务树实施，主流程负责集成和远程 main 发布。沿用 CONTRACTS_COMMERCE_EXPANSION 与 Phase8/LP08 原业务动作、DTO、分页、UTC和状态机，无新增服务、组件或审批。

## 稳定能力、对象与期限

全部 HIGH/TENANT_ALL。旅程定义 journey.create/validate/preview/read/submit/approve/reject/publish/pause 加 journey.pump，类型 journey；journey_instance.create/read/control 类型 journey_instance；journey_scan.read/retry 类型 journey_scan；marketing_effect.read/rebuild 与 marketing_execution.read 类型 marketing_report。独立能力不隐含read或外域权限。

Auth沿既有 Issue/Reference/Check/ScopeCheck，只有HUMAN签发。journey.create/validate/read/pump、journey_instance.create、journey_scan.read、三report能力只集合；preview和五状态动作绑定真实journeyId + 不可变Definition.version；实例read/control绑定真实instanceId + Instance.journeyVersion，Owner同时核对该固定父定义；scan.retry绑定journeyId + Scan.journeyVersion，URL固定内容版本，body expectedVersion仅scan.version CAS。真实对象内容版本必须正数，禁止lockVersion/instance.version/scan.version初始0冒充内容。

只有实际POST journey_instance.create签发最长2592060秒（原30天业务上限2592000 + 60秒签发余量）；所有其他能力及GET资格最多60秒。引用上限不扩大原Grant或当前授权有效期，不延长业务deadline。后台每节点取原Grant路径与当前路径交集，原directory epoch、身份代际、应用/环境/caller及准确expiry不可换；重新登录、regrant、pump/control不能续期或替代原create源。SDK格式不变、无Token落库。

## 权威族、版本与来源

旅程定义/实例/扫描同一JOURNEY权威族，避免原政策与实例跨独立切换形成半接管；效果/执行记录独立MARKETING_REPORT族。CENTRAL/STOPPED及曾CENTRAL禁止旧ADMIN回退；客户本人接口不登记中央员工链。

定义不可变正version、状态锁lockVersion、实例进度version、扫描进度version、历史transitionVersion分别保留。定义列表最新版本不替换实例/扫描固定历史。publish原五动作审批同时记录固定自动政策；pause/新publish停止未来入组，已有实例保留原内容与政策。

来源为MANUAL、SYSTEM、LEGACY；历史NULL明确未知，在曾CENTRAL路由拒绝，不自动回填。MANUAL实例一次保存原Actor/中央元数据/命令键/创建时间，无Token和ALLOW。SYSTEM来源只能由实际自动定义publish事务记录的固定Policy复制到事件/扫描实例，包含tenant/journeyId/definitionVersion/批准route/主体/命令键/时间。批准人的短引用和后续撤权不撤销已提交政策；每次启动/节点检查同Auth分区且CENTRAL未STOPPED、route版本不早于批准版本，提交前共享路由锁和五秒准入。Auth停服员工返回503，独立政策不借员工Grant继续既有履约。

retry必须重查当前control + 原MANUAL引用或SYSTEM政策 + 原deadline，scan retry重查当前scan.retry + 固定政策 + published/window + scan CAS。cancel是安全停止，仅需当前control真实实例Facts与路由，不依赖原create尚有效；原源已撤权/到期仍可取消未终态实例，但不复活、不改源、不撤销已提交效果。未知旧源也可由当前有权员工取消，不可retry或继续执行。原订单退款cancelForOrder仍领域内部mandatory事务，权益冲正由原Owner负责。

## 事务、查询与原命令

每个中央用例Owner调用scope；已有对象先本租户读真实行，再resource；只读SQL前门禁，返回前requireSame和对象复核。手工入组只create集合，但本地核对真实父定义/会员/window/频控。节点/扫描事务前原源远程判权，事务内锁route、原source、实际内容与当前检查点，提交前核对expiry/准入；动作+cap/effect+step/checkpoint同事务。失败事务只记实际尝试版本，503瞬时延后不计次。原命令键、scope主体代际摘要、回执前门禁和版本审计同事务；幂等响应不能绕过当前权限。

实例/历史中央OPERATOR独立路径，不调用members.current；客户/v1/journey-instances和/notifications仍本人。history一致性快照保留，远程检查在短读事务外。取消保留已提交通知、券、权益、频控、效果与历史，BENEFIT_ACCEPTED不冒称AVAILABLE。

report首批TENANT_ALL，storeId仅真实业务过滤。UTC from/to<=93天、原游标/金额字符串/覆盖说明保留。rebuild仍同步有界原命令，不引入长期job；内部订单投影读取使用Order Owner明确租户内部有界入口，不要求额外order.read。CampaignExecution查询用独立中央能力，不改变订单/支付/权益/退款事件履约。

## UI与固定入口

复用现有中央SSO/Craft/AntD和居中Modal；固定GET壳 /operations/journeys、/operations/journey-instances、/operations/journey-scans、/operations/marketing-effects、/operations/marketing-executions 加既有中央导航/懒加载。匿名仅壳，非GET及业务API仍401。仅有限字面GET动作提示，ActionAccess.allowed，不可复用许可，无长期source/命令审计：/v1/operations/journeys/{create|validate|preview|submit|approve|reject|publish|pause|pump}-access，/v1/operations/journey-instances/{create|control}-access，/v1/operations/journey-scans/retry-access，/v1/operations/marketing-effects/rebuild-access。列表read直接原GET判权，hint对应POST仍重判权。

全部字段沿真实JourneyApi/MarketingEffectsApi/CampaignExecutionApi，不编造后台source公开DTO。write-only直接输入真实id/version/CAS等参数，不要求目录read。create/change/enroll/control/scan-retry/rebuild冻结key/body/path，unknown原键恢复，409才允许修正。validate/preview无键无业务副作用。pump无键unknown不自动重发，提示可能已提交部分效果，仅显式开始新有界调用。401卸载敏感数据；403能力隔离，503不回退。定义内容版本/CAS/固定历史分栏，traceCoverage不能冒充来源已知。1440/390/320正文无横溢、表格内部横滚、关联Modal/键盘/dirty关闭保护和真实岗位SQL/截图与当前最终JAR绑定。

## 有序切片

| ID | 可观察结果 | Needs | Owner与验收 | 状态 |
|---|---|---|---|---|
| CE05-J0 | Auth旅程15有限能力及三report集合能力 | 既有D0/D1稳定协议；本技术细化 | protocol/governance，真实PG/SpiceDB18独立Grant/type/对象/期限/HUMAN/原Grant撤权重授/跨进程；SDK/全仓/当前归档 | VALIDATED |
| CE05-J1-D | 九定义能力与五状态动作、publish固定政策 | J0验证 | Commerce JourneyService/Mapper/runtime/iam/migration，真实MySQL/HTTP原键/身份审计/内容与CAS/纯预览/回滚/锁等待 | VALIDATED |
| CE05-J1-I | 原手工实例、历史/控制/节点执行/安全取消 | J1-D | 原源跨重启/撤权/到期、客户本人兼容、SQL动作后回滚、并发恢复/已提交效果保留 | VALIDATED |
| CE05-J1-S | 事件/生命周期SYSTEM政策及扫描恢复 | J1-I | 实际事件/UTC/偏好/退款/window/单会员原子checkpoint/停服政策/STOPPED/unknown历史源 | VALIDATED |
| CE05-J2 | 三真实SSO旅程页及所有有限操作 | J1-D/I/S验证 | 实际PKCE独立能力/写岗无read、原键/unknown、桌面/390/320关联操作与SQL/当前制品截图 | VALIDATED |
| CE05-EF1 | report三能力Owner及内部有界重建 | J0与J1 | 真实MySQL/query/UTC93天/覆盖/退款/命令回滚/独立授权/事件兼容 | VALIDATED |
| CE05-EF2 | 两真实SSO报表/执行页 | EF1验证 | 独立read/rebuild-only/execution、实际DTO/金额/覆盖与游标、401/403/503/原键和视觉 | VALIDATED |

共享协议/认证与migration均本任务单Owner串行，不并发写；独有库/分区/端口验证，不用原D2 18161/18162/18660—18666、5273、8602或清空共享数据。只本地逻辑提交，主Agent统一main集成/push。完整CE00—08及Auth实际菜单资源目标保持active，候选122/岗位34不等于全量实现或发布。

2026-10-02 配对Commerce J1/EF1 e900616已落完整Owner和26项真实MySQL；J2/EF2最终运行 c4ead219d8dc、Source22检查点、真实原Grant到期、同键坏回执/SQL、52当前图与1真实交互测试PASS。详细验收在配对Commerce `docs/implementation/enterprise-iam-integration/CE05_JOURNEY_UI.md` 与 `.local/journeys/j2-evidence-v1`。Auth共享 J0/ad09213/5e7abbf 原PG/图/单元证据保留，不用最新XML冒充原历史18方法；完整应用发布仍由主流程/IR继续。
