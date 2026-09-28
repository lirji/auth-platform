# OA、Auth 与业务项目统一权限改造

本计划按用户提供的 `oa-auth-phased-plan` v0.2，落到三个现有仓库。**目标是在原项目中增量改造：auth 管理身份消费模型、应用与授权，OA 保留目录和审批职责，commerce 接入可信身份、页面权限及接口数据权限。**

当前完成的是整体计划和 P0 实施准备；P1—P7 产品能力尚未实施。总体计划完成不代表统一权限已经运行。

| 文档 | 用途 |
|---|---|
| [EXECUTION_PLAN.md](EXECUTION_PLAN.md) | 八阶段范围、里程碑、验收、回退、业务决策与交付方式 |
| [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md) | 模块归属、内部／外部用户、应用角色、数据权威及一致性 |
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
