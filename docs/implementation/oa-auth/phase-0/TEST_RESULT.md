# P0 与计划验证结果

验收对象为本轮整体改造计划、P0 基线及 P1 实施准备；不是 P1—P7 产品实现。计划范围以 `docs/design/oa-auth-unification/EXECUTION_PLAN.md` 为准。

| 检查 | 结果 | 可追溯证据 |
|---|---|---|
| 用户输入包清单、逐文件 bytes/SHA-256 | PASS | SOURCE_MANIFEST.json 与原 manifest 的 11 份文档逐项核对，外加原 manifest 摘要 |
| 源任务完整覆盖、稳定 ID、依赖无环 | PASS | EXECUTION_DAG.json，58 个源任务＋5 个补充节点，共 63 个唯一节点 |
| 各片结果、Owner/路径、依赖、验收、pass/runtime | PASS | IMPLEMENTATION_SLICES.md 与 DAG 对齐；P3-04 为不可执行汇总 |
| Markdown 本地相对文件链接和 JSON 解析 | PASS | 本轮范围内文件核对；不把外部 URL 可打开当产品能力证明 |
| 产品文件无修改、证据与源 ref 一致 | PASS | 45 个源文件摘要；三仓进入时原脏改动另存且排除 |
| auth 旧核心测试 | PASS | test-results.json：124 项，无 fail/error/skip |
| OA IAM/安全/目录测试 | PASS | test-results.json：149 项，无 fail/error/skip |
| commerce 应用与依赖编译 | PASS | test-results.json：test-compile exit 0，不运行测试 |
| commerce 定向权限测试 | 未运行到目标 | 初次命令 exit 1，分类 VALIDATION_COMMAND_CONFIGURATION；上游空测试门禁，记录保留 |
| 新体系实际身份、图、SDK、审批及多实例 | NOT_RUN | 产品尚未实现，分别属于 P1—P5 的必需验证 |
| 容量、存量切换、恢复、生产接受 | NOT_RUN | 分别属于 P6/P7；没有生产部署授权 |

计划与准备交付门禁：PASS_WITH_LIMITATIONS。限制为正式目录/外部场景待确认及后续真实集成尚未运行，不宣称运行能力通过。P1-00 可以作为下一次设计入口，具体产品片只有在其契约、依赖及业务/环境门禁满足后才能开始。

两次基线测试均来自固定源码工作树，报告按真实 suite 解析；Mock/H2 单元边界没有冒充实际 PostgreSQL/SpiceDB/OA 链路。Git 交付记录单独观察实际提交与远程 main，不自动产生 CI、上线或部署成功。
