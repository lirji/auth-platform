# 唯一实施切片表

保留源方案全部 58 个任务 ID；新增 P1-00 契约冻结、P2-05a SDK兼容验证，并将 P3-04 分为三个有界 pass，原 ID 作为汇总。机器依赖以 EXECUTION_DAG.json 为准；执行范围遵循最新用户指令和 PROGRESS_STATE，生产操作独立授权。

P0 的 DONE 表示基线记录、差异和限制已交付，**不表示全部真实集成测试通过**。P1-00 已冻结当前薄路径契约；继续实施已授权，产品片须有对应冻结契约。P1 全部 DONE；本轮已授权完成 P2 后暂停，状态以 PROGRESS_STATE 和 DAG 为准。

| ID | 可观察结果 | Needs | Owner／主要路径 | 验收 | Pass／Runtime | 状态 |
|---|---|---|---|---|---|---|
| P0-01 | 确认根目录、ref、未提交变更及现有分析 | — | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | 仓库基线记录 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-02 | 跟踪一条登录请求和一条权限检查 | P0-01 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | 请求链与入口映射 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-03 | 盘点数据表、Schema和全部授权写入方 | P0-02 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | 数据所有权／写入方表 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-04 | 复跑仓库已有最小构建与核心安全测试 | P0-03 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | 进入基线 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-05 | 定义内外部试点夹具和资源归属 | P0-04 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | 场景与样例数据设计 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-06 | 固定ADR和P1变更范围 | P0-05 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | P1可执行待办 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P0-07 | 输出一次性阶段记录和交接 | P0-06 | IAM 工程负责人；docs/implementation/oa-auth/phase-0、docs/design/oa-auth-unification | P0结果／P1入口 | baseline+evidence；既有构建工具；不写运行数据 | DONE |
| P1-00 | 冻结可信成员薄路径的具体契约、旧身份映射与持久化装配 | P0-07 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 字段/信任/错误/约束足以实施第一片；只用隔离映射，不发明已发布接口 | contract-design；按本片需要的隔离验证目标 | DONE |
| P1-01 | 将模型映射到已有表和领域对象，增量迁移 | P1-00 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 旧数据可读取、无身份合并 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-02 | 实现Token验证与LoginIdentity适配 | P1-01 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 错issuer／aud、ID Token、过期Token被拒绝 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-03 | 实现Membership上下文解析 | P1-02 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 请求体伪造主体、跨租户选择无效 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-04 | 接入唯一目录源与幂等检查点 | P1-03 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 重复、乱序、全量不完整不误删 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-05 | 实现外部邀请和生命周期 | P1-03 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 并发接受唯一、外部成员无内部默认访问 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-06 | 接入停用状态与审计 | P1-03 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 旧JWT不能绕过当前成员停用 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P1-07 | 输出真实集成测试与P2交接 | P1-04, P1-05, P1-06 | IAM／目录 Owner；auth: governance（候选）, protocol, admin, server、OA: oa-org/api | 不以Mock代替真实身份源验证 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-01 | 应用／能力／菜单清单解析与预览 | P1-03 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 外部应用不能覆盖trade能力 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-02 | RoleVersion与AccessGrant模型 | P2-01, P1-06 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 唯一约束、版本不变、来源保留 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-03 | 受保护的角色与授权管理API | P2-02 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 无委派权限不能授予，高危自提权失败 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-04 | Grant图模型与单执行者投影 | P2-03 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 真写入、真检查、生效水位及异常 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-05a | Boot4消费指定auth源码SDK并启动、检查和拒绝 | P2-04 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 自动装配/Jackson2与3/AOP与显式调用/实际HTTP和错误拒绝，旧SDK回归 | bounded-compatibility-spike；按本片需要的隔离验证目标 | DONE |
| P2-05 | SDK错误与身份契约 | P2-05a | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 错受众、伪造主体、超时和批量缺项 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-06 | 内部业务只读接口接入 | P2-05, P2-03, P2-04 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 授予允许、撤销拒绝、跨租户拒绝 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-07 | 最小菜单与授权状态展示 | P2-06 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 无权直接请求后端仍被拒绝 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P2-08 | 文档与交接 | P2-07 | IAM／应用 Owner；auth: governance（候选）, admin, core, sdk, auth-console、commerce: StoreAccessController/Service | 记录多实例与细范围尚未认证 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P3-01 | ScopeRule、同Grant匹配与业务字段绑定 | P2-08 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 查询全范围＋退款单范围不交叉放大 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | DONE |
| P3-02 | ScopePlan与列表／导出适配 | P3-01 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 行、数量、统计、下载均不越权 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P3-03 | PolicyPartition与DirectoryFence | P2-08 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 主库快照及二次版本校验 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | IN_PROGRESS |
| P3-04a | 真实图marker CAS使旧版本写入失败 | P3-03, P2-04 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 前置条件、原子marker+关系更新、旧payload不能借新marker写入 | implementation+focused-fault-validation；按本片需要的隔离验证目标 | TODO |
| P3-04b | 有界执行器领取并推进当前desired epoch | P3-04a | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | SQL租约+远端栅栏、旧READY失败、批次未完不READY | implementation+focused-fault-validation；按本片需要的隔离验证目标 | TODO |
| P3-04c | 远端未知结果与图成功SQL失败可恢复 | P3-04b | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 真实双执行器暂停恢复、超时查marker、receipt恢复、幂等对账 | implementation+focused-fault-validation；按本片需要的隔离验证目标 | TODO |
| P3-04 | P3-04a/b/c全部通过后汇总CAS与多执行器恢复 | P3-04a, P3-04b, P3-04c | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 旧写入不能覆盖新状态 | aggregate-only；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P3-05 | 水位持久化、逐项批量校验 | P3-04 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 不是单JVM内存保证 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P3-06 | 撤权receipt、期限及组变更 | P3-05 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 完成后新请求不走旧路径 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P3-07 | 故障注入与性能基线 | P3-02, P3-06 | IAM／业务 Mapper Owner；auth: governance（候选）, core, server、commerce: StoreAccessMapper.xml、OA: OaDataPermissionHandler | 保存真实kill、双实例和延迟结果 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-01 | AccessRequest及不可变快照 | P3-07 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 角色修改不改变原申请内容 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-02 | 既有OA流程适配和幂等启动 | P4-01 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 超时重试只生成一个实例 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-03 | 可信回调、Inbox和冲突隔离 | P4-02 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 伪造、重复、同ID改体、旧版本 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-04 | 审批结果→Grant→投影衔接 | P4-03 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 审批成功但图失败不显示ACTIVE | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-05 | 取消／撤销／到期／离职 | P4-04 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 竞态收敛且来源隔离 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-06 | 待办、状态查询与通知 | P4-05 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 外部只能看自己允许的内容 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P4-07 | 真实跨进程E2E和故障恢复 | P4-06 | IAM／OA 流程 Owner；auth: governance（候选）/admin、OA: oa-flow/工作流适配/待办/通知 | 停OA不影响已有日常授权检查 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-01 | 工作台壳层、组织选择、应用卡片 | P1-03, P2-07 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 多组织切换与应用隔离 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-02 | 角色版本、成员授权、状态查询 | P2-03, P3-06 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 权限差异与待生效展示 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-03 | 邀请、我的申请、审批进度 | P1-05, P4-06 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 外部最小可见范围 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-04 | 授权解释、撤权与审计 | P3-06, P4-05 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 多来源撤销解释 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-05 | 内部真实业务接入验收 | P2-06, P3-07, P4-07 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 后端直调不能绕过 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-06 | 外部供应商真实验收 | P1-07, P3-07, P4-07 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 全流程及越权反例 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P5-07 | 可用性与部署演练 | P5-01, P5-02, P5-03, P5-04, P5-05, P5-06 | 门户／试点 Owner；auth-console/project-portal、OA: oa-console、commerce: frontend | 登录回调、CORS、Cookie、错误提示 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-01 | 迁移单元及旧写入方清单 | P5-07 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 无遗漏旧授权入口 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-02 | 身份／角色／范围映射及dry-run | P6-01 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 无未知规则自动放宽 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-03 | 幂等批量导入及增量衔接 | P6-02 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 重跑不重复、撤销不复活 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-04 | 影子比较及差异解释 | P6-03 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 禁止OR放行，覆盖反向场景 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-05 | 服务端权威路由及旧写冻结 | P6-04 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 每单元只有一个有效权威 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-06 | 单批预生产切换与回退演练 | P6-05 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 回退仍保留最新拒绝约束 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P6-07 | 生产候选报告和历史下线计划 | P6-06 | IAM／旧系统 Owner；三仓迁移工具/服务端路由/管理写入口 | 与P7上线门禁关联 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-01 | 部署与权限暴露面清单 | P6-07 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 各凭证与端点最小范围 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-02 | 指标、日志、告警及错误口径 | P7-01 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | DENY和ERROR可区分 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-03 | 固定数据夹具与容量测试 | P7-02 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 真实P95／P99、瓶颈和限制 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-04 | 双实例、旧进程、超时与混部故障验证 | P7-03 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 关键安全不变量通过 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-05 | 密钥与服务账号轮换 | P7-04 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 旧身份不可继续调用 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-06 | 隔离备份恢复与图重建 | P7-05 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 权限不复活，水位重新建立 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-07 | 单批上线评审与回退手册 | P7-03, P7-04, P7-05, P7-06 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 明确上线Owner及操作授权 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |
| P7-08 | 获准上线后有限观察和交接 | P7-07 | 运行／安全 Owner；三仓现有deploy/监控/恢复证据 | 不凭预生产报告声明生产完成 | implementation+focused-validation；按本片实际需要使用隔离 PG/Casdoor/graph/OA/业务 DB | TODO |

## 当前下一片

P1-00—P1-07 全部 DONE，汇总见 phase-1/P1-07_TEST_RESULT.md。两仓正常 Git 交付、main CI 和最终目录审计已完成，P1历史停止点已由本轮P2授权解除；P2全部DONE且两仓CI通过，按用户要求暂停；P3—P7未获本轮执行授权。
P2 交接见 phase-1/P2_HANDOFF.md；共享 Casdoor 升级仍 HOLD，不把 P1 后端验收等同正式登录切换。

## 依赖与外部条件

- Q-DIR 已确认 OA 员工和组织目录；P1-04 仍依赖 P1-03 和来源/租户受控映射，不自动接管正式目录。
- P5-06 依赖真实外部合作场景与业务资源映射；不能用假供应商订单完成验收。
- P6 的导入与切换须有真实输入、单写策略和预生产目标；P7-08须真实生产授权。
- 各片仅在 needs 全部 DONE、所属契约冻结、追加业务／环境门禁满足时 READY。
- 具体 contract／migration／schema ownership 与追加门禁见机器任务；共有 Owner 一次一片。

## 有界验证与并行

当前全部 parallel=false：公开身份和权限契约、同一治理迁移、图写入及在途状态共享，不推断可并行写。已有冻结API后的独立页面可提前排期，但执行前仍按文件Owner核对，无多Agent并行授权。

单片交付实现+本片负例+必要真实组件测试，不反复扩大到全仓测试。P3-04a/b/c分别完成远端协议、执行器、崩溃恢复；汇总任务不能独立执行。UI片需绑定真实API、现有视觉方向、相关视口/状态和实际查看截图。阶段交接/容量/故障/恢复任务按各自固定实验集合做一次有界验证。

## Runtime 首次介入

P1-01 首次需要隔离 auth 治理数据库及迁移装配，由 runtime-and-deploy 按冻结技术维护必要运行说明；P2-04首次需要隔离治理图；P4-02首次需要真实OA跨进程环境。不能为片表完整新增默认MQ、缓存或新BFF服务。部署执行不由本计划或Git授权自动产生。

本表状态由 update-progress-docs 更新，验收语义由契约和设计Owner维护，不为通过而删掉真实组件要求。

P1-06 受控停用本地验收 DONE（170 单测、20 PG、30 HTTP；旧 JAR/V3 读取兼容 PASS），精确 CI 36389719173 SUCCESS，9fd58ca 已合并推送 main。下一片 P1-04 目录接入；未改变原节点依赖。

P1-05 本地验收 DONE：181 单测、29 PG、5 Casdoor、30 邀请与 30 既有 HTTP/CLI 检查；旧 JAR/V4 读取兼容 PASS。远程精确 CI 36392809729 SUCCESS（47ff9d9）。Q-PROVISION 已确认自动建立主体/员工成员并精确来源绑定；下一片 P1-04。
