# P6-01 迁移单元与旧写入方盘点

状态：DONE（用户指定商城、授权选择既有运营租户的隔离演练范围）。已固定source tenant、只读快照及源码/本地运行入口；见P6_SELECTED_UNIT.json。这里没有执行导入或冻结运行中商城，写冻结/切换须通过P6-05/06。OA应用权限本身不在本批切换范围。

## 基线与范围

| 仓库 | 核验 HEAD | 工作区 |
|---|---|---|
| auth-platform | a70197089d6ff2f3e0fd57523bf1454a5867c4b0 | main 干净；本次使用 feat/iam-p6-migration |
| oa-platform | 6385a869e234b635c44e5b96f75e48133b3a1390 | main；用户既有进度、部署文档、tmp/.local 保留 |
| commerce-platform | 0f7bd6b3aee3c740037e00aef831528e0938403e | main 干净；本次只读 |

证据文件与摘要见 [source-evidence.json](source-evidence.json)。下文路径均相对所属仓库。本轮已只读提取本地commerce_local的最小授权/身份状态/资源快照，不包含凭据或个人资料。未启动服务、执行种子或变更共享 Casdoor。

## 迁移单元与待补映射

沿用已批准计划的 `app/environment/tenant/受控人群` 单元；一条业务链的页面、API、列表、详情、导出、后台任务及重试必须归入同一单元。

| 候选 | 已有实现 | 当前不能自行确定的事实 |
|---|---|---|
| commerce 门店商品经营 | STORE/MERCHANT 范围、本地 CATALOG、中央商品读写和限时导出 | 首批旧 tenant/actor/grant 的真实快照、目标中央标识、批准能力映射、环境 |
| OA 应用权限 | 身份、角色/继承、直接及部门/岗位/组授予、审批、委托、ABAC、数据范围 | 首批 OA tenant/人群/模块；旧规则等价映射与隔离项 |
| 员工与组织目录 | OA 公开目录快照/增量端口及 auth 消费端 | 已确认 OA 为正式权威（P1已有来源确认，本轮再次确认）；首批目录 tenant 和来源注册映射待补 |

首批source tenant与来源快照已固定在P6_SELECTED_UNIT.json；Q-MAP中目标中央租户/精确身份绑定仍待落实。现有 P5 夹具只能用于工具验证，不能冒充真实存量样本。用户已确认演练使用本地环境；P6-06 还需明确具体单元及安全回退版本。

## commerce 写入与消费入口

| 编号 | 入口及源码 | 事实与迁移处理要求 |
|---|---|---|
| C01 | `commerce-app/.../http/store/StoreAccessController.java` | `POST /v1/admin/store-grants` 新增、`POST /v1/admin/store-grants/{id}/status` 撤销/恢复。冻结必须覆盖两种写入，不只是页面隐藏。 |
| C02 | `store/.../access/application/StoreAccessService.java`、`store/src/main/resources/mappers/store/StoreAccessMapper.xml` | 权威表 `store_operator_grant`，租户内 grant 主键、actor/resource/permission 唯一键、active/version。新增和状态变化通过 Commands；不能把一次 HTTP 拒绝当作所有内部调用已冻结。 |
| C03 | `StoreAccessService.stores/requireCatalog` | 本地 ADMIN 可绕过 OPERATOR grant 范围；必须清点凭据角色和 ADMIN 特例，不能把 ADMIN 自动映射为中央全租户权限。 |
| C04 | `catalog/.../product/application/ProductOperationsService.java` | 商品查询/经营调用 requireCatalog；不能以中央商品入口上线替代旧入口迁移验收。 |
| C05 | `catalog/.../pricing/application/ChannelPriceService.java` | 价格操作也消费 CATALOG；首批范围必须明确纳入、保留独立单元或阻断，不能遗漏。 |
| C06 | `catalog/.../job/application/CatalogJobService.java` | 创建、查询、运行等路径调用 requireCatalog，含创建者检查；旧持久任务、重跑及恢复都必须列入切换矩阵。 |
| C07 | `commerce-app/.../http/store/StoreAccessController.java`、`CentralScopeController.java` | 门店列表以 central Bean 是否存在选择分支；中央 scoped 列表/详情/动作/写/导出/推进/下载已有独立入口。当前配置不等于持久化、按单元版本化的 P6 权威状态机。 |
| C08 | `platform-runtime/.../identity/persistence/CredentialMapper.java` 及 XML、`commerce-app/.../configuration/security/SecurityConfiguration.java` | 本地 Bearer/角色与中央身份通道均须登记；旧凭据可达接口必须纳入回退和绕过验证。 |
| C09 | `scripts/seed-local.py`、`seed-operations.py`、`seed-member-suite.py`、`prepare-e2e.py`、`iam-store-smoke.py`、`iam-scope-smoke.py` | 存在直接写 platform_credential 的脚本。只读盘点，未执行。目标环境的 DB 账号、脚本调度和人工 SQL 仍待核实。 |

旧 CATALOG 同时承载多个经营动作，不能直接假定等价于 `product.read`，也不能自动增加 `product.export`。MERCHANT 授权包含后续新增门店，其动态范围不能未经确认改为导出时的一组静态 STORE。

## OA 写入与消费入口

| 编号 | 入口及源码（Java 路径在 oa-iam，另有注明） | 事实与迁移处理要求 |
|---|---|---|
| O01 | `web/GrantController.java`、`application/GrantService.java` | grant/revoke、提权申请及批准、委托及撤销、到期回收；必须保持来源、期限和撤销语义。 |
| O02 | `web/RoleController.java`、`web/RoleAdminController.java`、`application/RoleAdminService.java` | 创建/复制/修改/启停/删除角色、替换权限和继承，均可改变有效权限。角色同名不跨应用合并。 |
| O03 | `application/AccessRequestService.java` | approve 调用 activate 创建本地 Grant；与 P4 的中央审批提供方链路并存，不能只冻结 GrantController。 |
| O04 | `application/BootstrapAdminInitializer.java` | 启动时按配置创建 SUPER_ADMIN/ALL 本地授予；回滚重启和旧配置仍可能触发，必须纳入入口冻结清单。 |
| O05 | `application/OrgChangeListener.java` | 离职遍历并撤销本地 grant；任职/组织树变更推进 epoch。收权变化不能因冻结加权入口而丢失。 |
| O06 | `oa-job-service/.../job/application/OaJobs.java:expireGrants` | 定时任务直接 JDBC 更新 `oa_iam.grant_record`，不经过 GrantService，源码 SQL 未带租户筛选。按单元迁移时需处理其实际运行覆盖范围；本次未运行、未认定现场任务启用。 |
| O07 | `application/UserGroupService.java`、`PermissionDelegationService.java`、`AbacPolicyService.java` | 组成员、委托和 ABAC 分支改变权限结果；未知条件必须隔离，不能转成无条件授权。 |
| O08 | `application/IdentityService.java`、`IdentitySyncService.java`、`IdentityProjectionListener.java`、`CredentialService.java` | 身份状态/同步/员工事件/凭据生命周期；停用与重新启用的语义需进入快照和增量边界。 |
| O09 | `infrastructure/cache/PermissionEngine.java`、`application/PermissionSnapshotBuilder.java`、`aspect/RequiresPermAspect.java`、`aspect/DataScopeAspect.java`、`infrastructure/datascope/OaDataPermissionHandler.java` | 本地权限快照、接口权限、SQL 数据范围分别有消费点。仅换菜单或 /me 响应不足以证明统一判权。 |
| O10 | `application/IamInvalidationService.java`、`oa-app/.../iam/IamRoleEventPublisher.java` | 本地失效与现有角色事件发布需列入运行清单；不能未经验证将角色事件视为全部授权增量日志。 |
| O11 | `oa-org/.../application/directory/IdentityDirectoryInitializer.java`、`IdentityDirectoryPublisher.java`、`IdentityDirectoryExport.java`、`infrastructure/directory/IdentityDirectoryAdminCli.java` | 目录初始化、发布、只读导出及管理 CLI；P1 适配能力存在不代表正式 OA 目录接管已获业务确认。 |
| O12 | `deploy/scripts/phase2-perm-smoke.sh`、`phase9-iam-policy-smoke.sh`、`seed-console-fixture.sh`、`deploy/sql/seed-demo-data.sql` | 仓库存在权限 smoke/种子入口，现场执行账号和运行方式待环境 Owner 核实；不执行这些脚本来生成所谓存量证明。 |

## auth 写入边界

保留 P0 写入方分类并以当前源码重新定位：`auth-platform-admin/.../AdminController.java`、`casdoor/CasdoorSyncController.java`，`auth-platform-server/.../AuthzController.java`，以及 `deploy/` 中图/目录/租户 fixture 工具。它们属于旧直接关系与同步路径，不能成为新治理图的第二个 writer。

新治理授权由 auth 业务库和已有投影链负责；管理 API、内部服务 API、生命周期/目录 CLI、审批回调分别有入口。P6 导入应复用治理命令和投影，不直接把旧表翻译成无版本的图写入。具体凭据可写的图实例、schema/关系类型和 ID 范围仍须在选定环境核实，不能仅凭 Controller 存在断言运行时可越权。

## 首批切换必须补齐的清单

1. 单元 app/environment/tenant/人群、业务模块、责任人、真实来源及目标 ID、快照 hash/version/time、输入记录数量。
2. 所有前端/API/直接调用/任务/重试/脚本/初始化/人工 SQL/外部凭据的实际运行清单；逐条对应迁移、保留或冻结策略及验证证据。
3. 选择已批准方案之一：短管理冻结、可靠已有增量日志或受控命令转发。当前代码中的角色 Outbox 不能自动证明覆盖全部来源。
4. 映射隔离、到期/撤销墓碑及旧版本不得复活的验收；真实存量不足时仅声明工具验证。
5. 切换前覆盖旧拒新允、旧允新拒、INCOMPARABLE/ERROR、多来源、跨租户、目录变化及后台任务。禁止 OR 放行。
6. 预生产单元和可保持最新拒绝约束的回退版本。旧链路不能识别最新约束时，保持中央判权或关闭受保护功能。

## 下一步

已进入P6-02：只读提取工具、契约、15项测试和真实快照dry-run通过；待旧CATALOG到中央能力取舍及精确专用身份映射答复。继续当前片，不再请求首批项目/快照位置，不重做P5。

## 本地环境只读核查

- commerce-platform-app-1 正在运行（healthy），宿主127.0.0.1:8602；这是现有业务环境，未进行切换或重启。
- oa-app、oa-job、oa-postgres 处于停止状态；P4的独有PG/workflow/Kafka容器也停止，未自行启动。
- 三个项目.local下按snapshot/mapping文件名搜索JSON/JSONL/CSV/SQL/YAML/properties，排除历史基线、依赖、构建和工作树归档，未找到候选。搜索结果不证明外部路径或数据库内不存在快照。
- 已有fixture/manifest等P3—P5产物由测试运行器生成；不能把它们自动选为用户的真实存量来源。需要具体文件路径，或明确首批本地库/租户以只读方式提取。

## 本轮已选定范围与写入方边界

- 用户指定commerce-platform，并授权从既有运营租户选一个隔离演练。稳定选择按tenant_id排序的首个含旧grant的租户，具体值、文件及摘要见P6_SELECTED_UNIT.json。
- 运行商城连接dev-infra-mysql84-1的commerce_local，后台workers开启。已有7个运营租户各1条store_operator_grant；最小快照共7授权、21条不含token的身份状态、14店、7商家，见commerce-source-observation.json。
- 选定grant为STORE/CATALOG，旧actor为ops-clerk，其OPERATOR凭据已到期。现有库缺central_store_identity_binding；不会误用P5随机身份或把另一8609环境访问文件当作该库映射。
- 隔离演练的初始来源是固定摘要文件，来源库不参与目标写入；原商城API/后台/种子仍服务原库，均不应获得演练库凭据。后续只允许专用演练加载器及新版本应用写演练库；创建实际目标时须核对连接和账号隔离。
- 本片清单验收限定所选快照/隔离范围，不声称共享商城运行写入方已停用。旧写冻结与并发在途/后台任务验证属于P6-05/06，不把本片源码证据当作后续验收。
