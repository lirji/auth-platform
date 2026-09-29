# OA、Auth 与业务项目统一权限改造

本计划按用户提供的 `oa-auth-phased-plan` v0.2，落到三个现有仓库。**目标是在原项目中增量改造：auth 管理身份消费模型、应用与授权，OA 保留目录和审批职责，commerce 接入可信身份、页面权限及接口数据权限。**

P0—P4已交付；P5统一入口、内部商品经营及外部门店协作已进入最终验收交付。当前Git/CI状态见[当前进度](PROGRESS_STATE.md)；[P5运行说明](../../implementation/oa-auth/phase-5/P5_RUNTIME.md)包含独立客户端、同源打包和回退边界。本轮在P6前停止，没有生产部署。

| 文档 | 用途 |
|---|---|
| [EXECUTION_PLAN.md](EXECUTION_PLAN.md) | 八阶段范围、里程碑、验收、回退、业务决策与交付方式 |
| [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md) | 模块归属、内部／外部用户、应用角色、数据权威及一致性 |
| [CONTRACTS_P3_SCOPE.md](CONTRACTS_P3_SCOPE.md) | 固定范围、ScopePlan、资源Owner与业务查询契约 |
| [CONTRACTS_P3_CONSISTENCY.md](CONTRACTS_P3_CONSISTENCY.md) | 持久栅栏、CAS投影、组/期限及撤权回执 |
| [P3 Handoff](../../implementation/oa-auth/phase-3/P3_HANDOFF.md) | 完成边界、运行恢复与P4前停止点 |
| [CONTRACTS_P1.md](CONTRACTS_P1.md) | P1 身份/成员/持久化及信任边界 |
| [TECH_SELECTION.md](TECH_SELECTION.md) | 保留的技术、必要新增能力与兼容验证 |
| [IMPLEMENTATION_SLICES.md](IMPLEMENTATION_SLICES.md) | 唯一实施切片表；沿用源方案 P0—P7 任务 ID |
| [EXECUTION_DAG.json](EXECUTION_DAG.json) | 依赖关系、责任路径及候选任务；不是执行成功记录 |
| [SOURCE_MANIFEST.json](SOURCE_MANIFEST.json) | 用户方案逐文件摘要、版本与来源 |
| [PROGRESS_STATE.md](PROGRESS_STATE.md) | 当前状态、授权边界、限制与下一步 |
| [P0 基线](../../implementation/oa-auth/phase-0/baseline.md) | 三仓源码、版本、写入口和实际测试事实 |
| [P1 首批计划](../../implementation/oa-auth/phase-0/phase-1-plan.md) | 首批变更顺序、契约冻结事项和明确验证命令 |

计划协调文档由 auth 仓库维护。OA、commerce 按各自切片建立分支并提交实现；不把三个仓库拼成一个仓库，也不创建第三套平台。

## 方案版本关系

用户 v0.2 是本轮目标方向。commerce 的 `docs/design/enterprise-iam-integration/` 是此前的候选计划，仍保留作为历史分析；其中 **OA 主持授权治理** 的建议由本轮 **auth 业务库主持授权治理** 的目标替代。旧 `IAM-00..17` 没有被伪装为完成或重新编号；对应关系在整体计划中。

OA 的 D-GOV-002／004／005 是现有实现的历史决策。它们继续约束尚未切换的旧链路；新目标通过扩展、映射、影子验证和分批接管生效，不直接修改这些历史记录，也不一次性替换旧 subject。

P4契约见[CONTRACTS_P4_REQUEST](CONTRACTS_P4_REQUEST.md)，后续入口见[P4交接](../../implementation/oa-auth/phase-4/P4_HANDOFF.md)。
