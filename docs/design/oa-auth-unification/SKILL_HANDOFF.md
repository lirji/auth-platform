# Claude 技能交接

用户指定使用 Claude 提供的技能。本轮从 `/Users/liruijun/.claude/skills/` 读取；这些技能链接到现有共享安装，未修改技能文件或启动 Claude 模型代理。

## backend-architecture-design

status=COMPLETED；gate=PASS_WITH_ASSUMPTIONS。

- 输入：用户 v0.2 分阶段方案、三仓当前架构、原候选计划与 OA 历史决策。
- 产物：BACKEND_ARCHITECTURE.md、TECH_SELECTION.md、EXECUTION_PLAN.md。
- 核验：auth/OA 部署边界保留、数据权威与唯一写入入口明确；新增复杂度对应持久化/投影/审批/数据范围的真实要求。
- 未决：目录权威、外部真实资源、正式 ID 映射和运行目标；只阻塞对应正式接管或验证，不假设已经批准。

## implementation-slicing

候选任务与依赖映射=COMPLETED；产品实施交接=PARTIAL，尚待 P1-00 及所属阶段的具体契约冻结。

- 产物：IMPLEMENTATION_SLICES.md、EXECUTION_DAG.json。
- 全部 58 个源 ID 保留；5 个项目补充节点有明确结果和依赖；图 CAS 按实际变化点拆分；依赖无环、并行写入未授权。
- 源共享契约状态为 DESIGN_DRAFT_NOT_EXECUTED；本轮消费其技术方向，不把它改标为已发布线上契约。
- 下游要求：按 P1 首批计划冻结薄路径契约后，用 backend-implementation 一次实施一条 READY 片。准备完成不等于产品片已完成。

## update-progress-docs

status=COMPLETED；gate=PASS_WITH_LIMITATIONS。

- 状态产物：PROGRESS_STATE.md、任务工作树根 CODEX_PROGRESS.md。
- P0 基线与准备已交付，保留 commerce 测试命令失败和所有 NOT_RUN；P1—P7 不改成 DONE。
- 下一设计任务：P1-00；对应业务前置为 Q-DIR、Q-EXT 及正式数据映射。

## task-git-delivery

独立于上面的规划/进度副作用执行。用户 AGENTS.md 第 8 条授权当前任务正常 Git 交付；仅允许本计划与 P0 记录，不包含 OA、commerce 原脏文件、私密 .local、产品源码或部署。

输入：P0 TEST_RESULT、正式状态、当前范围与验证结果。精确提交、push、main 观察及各动作实际结果写入 `.local/p0-evidence/delivery-result.json`；生产部署、tag、release、PR 均不由本轮授权推导。
