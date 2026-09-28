# 改造计划与实施进度

## 当前状态

最新指令已授权在已交付计划上继续实施。P0 基线已交付；P1-00 契约/技术装配 DONE；P1-01 已实现/验证 DONE，P1-02 READY；仅隔离测试数据写入。此文件为当前唯一状态摘要，历史准备事实仍保留在 phase-0。

## 已完成

- P0：12 个源文件、63 节点 DAG、三个仓库源码/运行基线；auth 124、OA 149 项单测 PASS，commerce 编译 PASS。
- P1-00：[CONTRACTS_P1.md](CONTRACTS_P1.md) 冻结身份、成员、旧 ID 映射、受控初始化、Token/S2S 边界和错误；TECH_SELECTION 冻结最小 Mapper/Flyway 装配。
- 当前独立分支 feat/oa-auth-p1-identity，隔离工作树 auth-platform-p1-identity，基线 f18e05e；原 OA/commerce 脏文件保留。

- P1-01：[真实验证](../../implementation/oa-auth/phase-1/P1-01_TEST_RESULT.md) PASS；126 单测、11 PG 集成、CLI 初始化/重放/查询、版本 CAS 与审计失败回滚。新增库/迁移和 CI profile，旧服务未接管。

## 未完成和限制

- P1-02 真实 Token 用途、签名/issuer/audience/期限和 P1-03 双身份入口尚未实现。
- P1-02 READY，P1-03 和后续产品片 TODO；不将契约冻结视为实际认证/授权能力已通过。
- Q-DIR：唯一正式员工目录源待确认，仅阻塞正式 P1-04；Q-EXT：外部真实资源/场景待确认，仅阻塞对应外部业务接入。
- commerce 现有定向测试在 P0 上游空测试门禁失败，行为验证 NOT_RUN。
- 审计归档期限、正式管理策略、实际容量和生产部署未决；不自动发明正式 SLA/角色上限。

## 授权

用户“开始执行后续任务”已授权连续实现当前依赖满足的片；AGENTS.md 第 8 条授权验证后正常提交/合并/推送 main。默认关闭新功能，隔离测试使用本任务命名空间。无生产部署、清库、强推、覆盖其他任务或修改公共技能授权。单执行者。

## 下一步

交付 P1-01 的完整逻辑提交；继续 P1-02 安装版 Casdoor Access Token 适配与负例，随后 P1-03 双身份成员上下文。每片由 Claude backend-implementation 实施、implementation-validation 独立判定、update-progress-docs 同步、task-git-delivery 交付。
