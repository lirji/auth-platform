# P0 项目实施基线

核验日期：2026-09-27（America/Los_Angeles）。范围为用户给定 v0.2 方案和三个现有仓库。P0 为准备阶段，未修改产品源码、身份配置、授权图、数据库或公共技能。数据和运行目标只做只读核对。

## 1. 仓库与工作区

| 仓库 | 核验源码 ref | 进入时工作区 | 验证方式 |
|---|---|---|---|
| auth-platform | `c07741a3398baeef32694ee7273612aaa64dd80d` | main，干净；origin/main 同 ref | 独立任务分支 `feat/oa-auth-unification-plan`，工作树 `auth-platform-oa-unification` |
| oa-platform | `f07c97893238cd97bab38a140b5f6946184d4459` | main；已有进度、部署记录及 tmp 变更 | 固定 ref 的 detached 测试工作树，未复制／覆盖原脏文件 |
| commerce-platform | `919081be0deef6024f39ba2ea4c232d2ed56fdab` | `chore/redeploy-frontend-919081b`；已有根进度和状态改动 | 固定 ref 的 detached 测试工作树，不参与另一个前端部署任务 |

OA 原脏路径：`CODEX_PROGRESS.md`、`docs/design/identity-authz-governance/PROGRESS_STATE.md`、未跟踪 `DEPLOYMENT_RESULT.md`、`tmp/`。commerce 原脏路径：`CODEX_PROGRESS.md`、`docs/PROGRESS_STATE.json`。全部排除在本任务 Git 交付外。

45 个关键源码／配置的 SHA-256 与对应 Git ref 已核对相等，见 [evidence-index.json](evidence-index.json)。旧文档和运行记录作为历史证据，不能替代当前源码与测试；未分析其他所有业务仓库。

## 2. 版本与当前环境

| 项目 | 核验事实 | 限制 |
|---|---|---|
| 构建工具 | Java 21.0.11，Maven 3.9.12 | 当前本机工具；不是生产镜像版本承诺 |
| auth／OA | Boot 3.3.5，Java 21；OA MyBatis-Plus 3.5.5，已有 Flyway | auth 新治理持久化尚未装配；OA 旧 IAM 已实现 |
| commerce | Boot 4.1.1，Jackson 3，MyBatis starter 4.0.1，MySQL | auth SDK 在此版本的消费兼容尚未运行 |
| SpiceDB | 运行容器只读 `spicedb version` 为 v1.56.2；镜像摘要已记录 | 没有在共享图写 Schema、元组或测试 marker CAS |
| Casdoor | 8000 Discovery 可读：issuer `http://localhost:8000`，S256，public subject | 语义版本 UNKNOWN；未用真实用户跑 PKCE／验证 Token 受众、刷新和登出 |
| Docker | desktop-linux，现有 dev_infra 与 auth 实例可见 | `docker ps` 本次未见 OA 容器；历史“OA 已部署”不能作为本轮跨进程验收 |

不把 Discovery 中支持 password／implicit 的声明当成应用可以开启这些模式；新客户端仍采用授权码+PKCE，具体签发行为在 P1 核验。

## 3. 核心链路与能力差异

| 能力 | 已读入口／现状 | 复用判断及缺口 |
|---|---|---|
| auth 检查 | `@CheckAccess→SubjectResolver→RemoteAuthzEngine→server /v1/check→SpiceDbAuthzEngine→SpiceDB` | PARTIAL：现有严格响应错误语义可复用，缺 app/env/member 和有限 ScopePlan |
| auth 管理 | AdminController、WorkspaceRoutingEngine、组/部门同步器 | PARTIAL：已有工作区隔离，未实现新的业务库 RoleVersion/AccessGrant 权威与管理上限 |
| auth 水位 | ZedTokenWatermark 单实例内存 | PARTIAL：保留旧兼容，不能证明多实例撤权；需持久化 receipt／epoch 和远端写栅栏 |
| auth 写前置条件 | AuthzEngine 现有 writeRelationships 只接 updates，core 未传 preconditions | MISSING：P3 新增兼容能力，在真实版本验证；不升级引擎作为默认解决方案 |
| OA 登录与 Human | UserContextFilter；IdentityProjectionListener／IdentityService 从员工投影 | PARTIAL：旧 sub 和员工链可复用；全局主体／企业成员分离及非员工外部 Human 缺失 |
| OA 角色和范围 | RoleAdminService、GrantService、PermissionCatalog、PermissionSnapshotBuilder | PARTIAL：已有 RBAC、范围和审计资产；缺 app/env 与不可变语义版本，不能整模块搬入 SDK |
| OA 审批 | AccessRequestService 本地四眼，审批后直接本地 Grant | PARTIAL：不能当成 auth→OA 流程→auth 投影的既有闭环 |
| OA 数据范围 | OaDataPermissionHandler 基于组织路径，权限快照保存关联范围 | PARTIAL：可复用规则／测试；仅 MyBatis 消费，不能直接套入商城 MySQL或 Jdbc 路径 |
| commerce 身份 | SecurityConfiguration.TokenFilter→持久化本地 Bearer→Actor | PARTIAL：tenant/actor/member 约束已有；外部 IdP 尚未启用，动态页面/接口 RBAC 未接入 |
| commerce 范围 | StoreAccessService + StoreAccessMapper.xml，STORE/MERCHANT + CATALOG | PARTIAL：真实租户、资源、SQL 分页范围已有；缺按动作拆分和中央 scope，首片可复用 |
| 外部供应商业务 | 未读取到与目标场景等价的 supplier 订单归属与权限接口 | UNKNOWN／MISSING：需业务 Owner 提供真实模型；不能拿样例完成 P5-06 |

本表没有将“读到实现”标为真实全链路已验证。P0 运行证据仅覆盖以下既有测试和编译。

## 4. 授权写入方与收敛要求

| 当前写入方 | 涉及事实 | 后续处理 |
|---|---|---|
| auth AdminController 和 server `/v1/relationships/*` | 直接元组变更 | 保留旧消费路径；启用新治理图前阻断旧凭据／接口写其受管类型和 ID |
| WorkspaceRoutingEngine | 多工作区引擎路由 | 明确实际实例及 Schema Owner，不能依据旧注释把所有 Schema 合在 8543 |
| GroupSyncService、DepartmentSyncService、webhook/reconcile | Casdoor 组／部门关系 | 旧 knowledge 图继续兼容；新成员事实进入受控目录/投影链，不成为第二个中央图 writer |
| `spicedb-smoke.sh`、`his-smoke.sh`、`server-smoke.sh`、`dept-authz-fixture.sh`、旧 `rag-authz-fixture.sh` | Schema、测试／部门元组 | 含写入／删除，P0 未运行；纳入 schema 管理与真实隔离试验边界 |
| `recsys-authz-fixture.sh`、`risk-authz-fixture.sh` | 各自专属图的 Schema／关系 | 不改实例归属，不向中央治理图误写 |
| `casdoor-tenant-provision.sh` | 租户配置及可选成员关系 | 新用户/成员体系接管前建立限定映射，不能直接批量同步正式人员 |
| OA GrantService、AccessRequestService、BootstrapAdminInitializer、OrgChangeListener | 本地 grant 的管理、批准、初始化、离职和到期回收 | 每迁移单元切换前完成入口登记/转发/冻结，不只关闭管理页面 |
| OA RoleAdminService、UserGroupService、目录监听、IamInvalidationService | Role/组/组织/epoch 等允许结果影响事实 | 同样纳入版本 fence，不能只管显式 Grant 撤销 |
| commerce StoreAccessService、旧管理员路径、测试/种子工具 | store_operator_grant、身份角色及本地业务权限 | 存量映射、命令增量、中央路由和旧写冻结；不无条件删除旧规则 |

此为三个目标仓库中已核对的入口类别；正式迁移仍要盘点运行调度、人工 SQL 和外部持有的图凭据。不能仅凭源码搜索宣称企业全部外部写入方已停用。

## 5. 最小 ADR

| ADR | 当前决定 | 状态 |
|---|---|---|
| ADR-01 | 两后端保留，一个门户产品；不重建第三套平台 | ADOPTED_FOR_PLAN（用户 v0.2） |
| ADR-02 | auth 只消费一个正式目录权威；OA 为当前适配候选，已有 HR 则保留其来源 | 正式来源待 Q-DIR；隔离准备可继续 |
| ADR-03 | 外部首版为本公司租户受邀成员；跨企业不取消 tenant 过滤 | ADOPTED_FOR_PLAN；真实场景待 Q-EXT |
| ADR-04 | 管理受众、应用服务认证、被代入用户证据独立验证 | ADOPTED_FOR_PLAN；实际 DTO/凭据行为 P1-00 冻结 |
| ADR-05 | auth 业务库为新治理权威，中央图单 writer；旧单元切换前保持原权威 | ADOPTED_FOR_PLAN；P2/P3 实现，P6 接管 |
| ADR-06 | 沿用各仓框架、PG/MySQL、现有进程；新增持久化装配先验证 | ADOPTED_FOR_PLAN；不整体升级 |
| ADR-07 | 内部只读入口 `GET /v1/operations/stores`；外部供应商保持业务前置 | 内部候选已定位；外部未就绪 |
| ADR-08 | 撤权完成后新检查不能用旧路径；在途事务严格语义单独确认 | ADOPTED_FOR_PLAN；不宣称远端检查与业务提交原子 |

没有业务 Owner 签字记录；上述是按用户方案准备的技术决定，不是伪造的审批。

## 6. 实际验证

| 命令／核验 | 结果 | 证据及边界 |
|---|---|---|
| auth `mvn -B test` | PASS，exit 0，124 tests，0 fail/error/skip | 23 suites，严格 JSON／批量／安全过滤／同步边界和审计等；引擎响应通过 Mock，审计 H2 |
| OA `mvn -B -pl oa-iam,oa-security -am test` | PASS，exit 0，149 tests，0 fail/error/skip | 32 suites；IAM/目录范围/安全单元基线，非真实 PG、Redis、审批跨进程证明 |
| commerce 定向 test 命令 | FAIL，exit 1，VALIDATION_COMMAND_CONFIGURATION | shared-kernel `failIfNoTests=true` 阻止无匹配测试；未执行到目标，不能计为权限用例失败或通过 |
| commerce `mvn -B -pl commerce-app -am test-compile -DskipTests` | PASS，exit 0 | 应用和依赖生产/测试源码编译；没有运行权限行为测试 |
| 三仓 45 个源码摘要与 Git ref | PASS | 当前证据对应固定源码，不引用之后的未知修改 |
| Casdoor Discovery 与 SpiceDB version | 只读核验 PASS | 不是实际登录、图 CAS 或 check/grant/revoke 集成证据 |

实际 suite、用例名、报告摘要和退出码见 [test-results.json](test-results.json)。原始日志保存在任务隔离工作树 `.local/p0-evidence/`，不发布凭据；元数据足以追溯本次结果。

未运行：commerce 数据库权限回归、真实用户 PKCE 与 Token 受众、中央 grant/check/revoke、marker CAS、多实例撤权、跨进程 OA 审批、外部供应商业务、容量和恢复。后续所属阶段必须真正执行，不能沿用本轮 PASS 代替。

源码疑点：OA `GrantRecord.jitElevation()` 匹配 `TEMPORARY + APPROVAL`，而 `PermissionSnapshotBuilder` 在该分支仅追加高危提升；普通新角色临时申请是否真正获得基础权限，现有单测不足以证明。本轮没有产品复现或修复，P4 复用前必须区分语义并验证。

## 7. 试点与夹具设计

隔离企业 T-A/T-B、员工 E、外部成员 G、受限管理员 A、服务调用者 S；商城真实门店 store1/store2、商家 merchant1，业务身份有独立 actor 映射。角色和能力名称仅作待冻结设计。

- P2：员工 E 对 T-A 的门店只读；无准入、跨 T-B、服务主体冒充均拒绝。
- P3：同资源族的“商品查询租户范围”与“商品修改 store1”由不同 Grant 提供；不能合成修改 store2，tenant 条件始终保留。
- P1/P4：外部 G 接受邀请后无默认 OA 权限；临时授权批准但未完成投影不可 ACTIVE；过期、停用和重加入旧代际拒绝。
- 外部供应商 S001/S002 是源方案示例，未作为实际商城模型或已灌测试数据。本轮只准备场景，不创建生产账号、授权或种子数据。

## 8. P0 退出与交接

G0-01 真实路径/ref/工作区、G0-04 P1 目标与验证、G0-05 测试失败分类、G0-06 无产品/配置/图修改已满足。G0-02 明确当前与目标所有权，正式目录源决策仅阻塞其接管；G0-03 内部实际接口已定位，外部未选定已明确记录。

P0 为 COMPLETE_WITH_LIMITATIONS，限定于实施准备和内部薄路径设计；不宣布真实登录或外部试点已就绪。下一项是 [P1-00](phase-1-plan.md) 契约/装配冻结。目录正式接入、外部资源和生产数据分别受对应决策与环境门禁约束。
