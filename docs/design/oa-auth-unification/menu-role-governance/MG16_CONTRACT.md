# MG16 引用退出与最终退役（FROZEN）

2026-10-03。依赖MG13／MG14／MG15产品和必要验收DONE。实例／DB隔离、原业务事实保护和默认关闭治理路径保持。不新增中间件或部署生产。

## 状态与并发边界

扩展独立生命周期RETIRED，唯一合法正常流转DEPRECATED→RETIRED；最终状态不可恢复、不可删除。紧急disabled仍是独立新审计操作；解除disabled不复活RETIRED。原Manifest、稳定能力编码、历史哈希、角色和来源保持。所有新增使用仍被统一门禁拒绝，判权也明确拒绝RETIRED。

最终提交由当前HUMAN应用Owner，作用于本实例该应用全部分区。固定预期生命周期版本、当前目录版本和两摘要、引用basis_hash、原因、原命令。同应用排他锁串行正常退役与新增引用；所有迁移计划／执行引用新增也取得同应用共享锁并拒绝已退役引用，旧SQL节点不能绕过。已有减少权限和投影重试继续可用。旧成功命令在当前资格下只读原结果，不能重复退役或恢复。读失败／不完整／无权从不解释为零引用。

## 引用矩阵

Owner全应用报告只给类别计数、依据和运行核验状态，不暴露成员、原范围、审批人或其他企业明细。获授权分区管理员同时具备独立诊断资格才可分页定位该分区引用（每页最多100）；查询与允许／拒绝均记诊断审计。固定复合游标，依据改变返回冲突。

| 类别 | 正常退役阻断 |
|---|---|
| 当前菜单 | 当前清单any_of仍引用，包括任意父菜单引用。 |
| 全部角色版本 | 仅审计引用，不删除；其当前或未来来源另计。 |
| ACTIVE／PENDING来源 | valid_to未到期即阻断，包括未来生效、旧代际／暂不可用人员和动态组；不把资格暂不可用当已撤权。 |
| enabled委派／申请策略 | 能力集合或绑定角色仍引用即阻断；纯撤销和缩减继续允许。 |
| 未结束申请 | SUBMITTED／IN_REVIEW，以及APPROVED尚无Grant且未withdrawn的新版OA，valid_to未到期即阻断；终结快照保留。 |
| 迁移计划 | 非终态子项旧／新角色任一引用即阻断，含撤旧已证明但尚未授新；终态只保留审计。 |
| 投影与回执 | 相关Grant未完成旧投影、有该能力来源的相关分区可靠操作非APPLIED或缺少当前代际真实回执均阻断；操作意图不代替图证明。 |
| 执行引用 | 未到期执行引用阻断，不因本人暂不可用而忽略。 |
| 项目API | 独立受信运行核验覆盖完整配置部署目标，当前能力映射零引用；没有核验保持UNPROVEN。 |

聚合计数来自单次SQL快照；依据最多10000引用行，超过时INCOMPLETE并阻止退役，细节仍有界。时间仅改变到期判定，不把每次统计时间纳入basis。历史与活动分开；真实零值保留0。

## 受信项目核验

复用服务受控配置和本地文件，不接受网页上传证明、任意URL、Owner勾选或普通发布人声明。配置每应用完整deployment-targets、Ed25519公钥及绝对proof-file；空配置默认UNPROVEN。独立部署核验器负责观察实际运行制品与完整API映射并形成短期签名证明；Auth只验证和消费，不自动构造“运行已退出”证明。

严格JSON签名信封payload／signature均Base64，签名覆盖原payload字节。payload固定schema_version=1、application_id、capability、manifest_version、content_hash、presentation_hash、lifecycle_version、checked_at、valid_until及deployments。每个部署target_id、commit、artifact_hash、api_mapping_hash、complete及capability_references，目标集合必须等于配置非空集合，complete必须true，引用数必须0，校验当前目录／生命周期，检查时间≤当前、有效期≤检查后300秒、当前未到期。损坏、缺失、签名错误、目标缺漏、非零映射、陈旧依据都为UNPROVEN并给稳定原因；不因此回退目录或自动禁用。

文件只从受控路径读最多128KiB，原证明hash纳入basis；提交前再次验证并要求相同hash，证明字节及引用依据与原命令同事务持久化。配置目标清单的完整性及部署期间失效证明由外部核验器负责，生产接入必须纳入部署门禁；此片不声称当前生产已具备运行核验。没有接入时正常退役会一直被保守阻止，弃用／紧急停用仍可使用。真实测试核验与签名夹具必须分别标注。

## HTTP与页面

沿用Bearer、严格≤16KiB请求、no-store和既有错误。
- GET /api/governance/v1/catalog/owner-view：application_id，仅当前Owner的目录能力／生命周期，不包含人员、委派或角色明细；应用卡片追加catalog_owner且Owner在最后委派退出后仍能进入目录治理。
- GET /api/governance/v1/catalog/capability-retirement：application_id／capability，Owner全应用报告。
- GET /api/governance/v1/access/capability-retirement：tenant_id／application_id／environment／capability，可选cursor，分区诊断明细；不授予Owner人员诊断权。
- POST /api/governance/v1/catalog/capability-retire：application_id／capability／expected_version／basis_hash／reason／command_id。当前Owner重验全部条件，阻断返回RETIREMENT_BLOCKED 409，依据变化VERSION_CONFLICT409，异命令COMMAND_CONFLICT409。

报告含生命周期、目录依据、完整性、每类阻断及历史数、proof_state／proof_reason／proof_hash／proof_valid_until、eligible／basis_hash／checked_at。分区详情含kind／id／blocking／state／role_id／source_type／valid_to，不包含Token。原回执返回当前状态与固定原命令，RETIRED不可恢复。

目录能力详情增加引用检查、真实原因、刷新和分页定位（明细仅诊断授权），Owner仅在DEPRECATED、报告完整且eligible时可确认最终退役。未知结果冻结原依据／版本／原因／命令并原键重试；过期或变更重查。缺元数据／无权／503独立展示。RETIRED显示历史墓碑及不可恢复，无弃用恢复按钮。1440／390／320实际交互与当前图检查。

## 必要验证

追加V32／V33，V32实际执行后发现纯历史角色不应引入无该能力来源的人员投影阻断，并以V33修正；原V32不编辑。独立诊断target_id兼容扩至100字符以记录能力编码。

真实PG验证未来来源、旧代际与组、enabled委派／策略、APPROVED待切换、迁移间隙、投影回执与执行引用，聚合和分页、真并发新引用与退役、失权／版本／同键、终态守卫和旧SQL、原Manifest字节与历史不变。签名验证非法、过期、目标缺漏、非零、目录变更及默认UNPROVEN。真实图证明撤权回执后清除阻断、最终退役判权拒绝。实际HTTP及页面三宽度；原业务Grant写入0。仅本任务PG／新UUID图分区；真实生产核验未接入必须明确记录，不能以假证明标生产PASS。

管理委派／策略退出复用平台运维0600配置边界，新增RetirementReferenceExitCli inspect／exit：固定分区、kind=DELEGATION或POLICY、target UUID、完整原行expected-hash、operator／原command／reason。只做enabled→false，不恢复／扩权、不修改既有Grant／请求，不把初始化接口当更新入口。原行无递增版本，完整摘要作为并发依据；追加V34独立审计保存真实前后完整行摘要、原因和原命令，不伪造行版本，减少效果与记录同事务。HTTP不暴露此平台运维写能力，Owner仅能核对并联系既有负责操作人。
