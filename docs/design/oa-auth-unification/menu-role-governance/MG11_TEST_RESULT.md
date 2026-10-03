# MG11：固定CI目录发布客户端验收

结论：PASS（既有格式化工具限制），MG11 DONE。MG00–MG19整体尚未完成；MG12需要角色迁移连续性业务决定。客户端默认预览，发布开关默认关闭，普通Git推送不触发真实目录发布。

## 实现与验证范围

标准库客户端提供preview／publish／recover，复用[MG11_CONTRACT](MG11_CONTRACT.md)及MG10独立机器入口。目标来自本用户0600文件，不跟随重定向；同一构建的目标、客户端、委派、原声明字节、预期提交、准确预览和原命令固定在有锁、原子替换并fsync的检查点中。Token仅内存；不持久化或输出凭据，不写角色、Grant、人员或图投影。

目录、投影与运行事实分开输出。只有原命令、来源、双摘要、基础版本／双摘要和实际SERVICE actor均核验通过，目录才标PUBLISHED；没有可信独立检查的projection_status／runtime_status保持UNKNOWN。

| 验收 | 实际方法与证据 | 结果 |
|---|---|---|
| 语法与严格协议 | 四个Python文件py_compile；16项本机TCP协议fixture单测 | PASS |
| 默认预览／Owner边界 | 默认false不发送publish；Owner审查报告无ticket且禁止机器发布；合法报告的两版能力联合上限正确处理 | PASS |
| 固定来源／目标 | 原始候选、声明制品SHA、完整声明及预期commit核对；候选或制品替换、客户端／委派改绑和错环境拒绝 | PASS |
| 协议失败与历史状态 | 503、截断chunked响应、损坏actor、查询404保留未知；明确拒绝的后续重试不能推翻之前未知或已核验历史回执 | PASS |
| 私密状态和并发 | 0700目录／0600检查点、非符号链接／大小边界、非阻塞互斥；检查点不含fixture Token、secret或认证字段 | PASS |
| 实际IdP、API和PG | 最终mg10-http-b05285bb0133：复用Casdoor v4.11.0、两个新专用PG16.15目标／新客户端，41项支持HTTP／CLI检查 | PASS |
| 实际Commerce客户端 | 同轮ci-client/result.json：16项实际CLI检查，固定Commerce提交57804ef2fe8692ef65d0d49a3c8ffe6803e5d12f及真实导出器；首次显式发布、原命令重试、错提交／环境、基础变旧和版本回退拒绝 | PASS |
| 真提交后响应丢失 | 本机代理先取得实际API成功响应再断开；PG查询原command的release恰好1条，客户端保留PUBLISHING_UNKNOWN；新进程只凭原检查点恢复实际回执，再重试原命令 | PASS |
| 卫生和工作流 | Code Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无BLOCKING；diff检查通过；CI增加工具路径过滤、协议回归和语法检查，不获取真实发布凭据 | PASS（工具限制） |
| 页面与托管生产发布 | 无可见前端变更，视觉N/A；未创建GitHub Secret或运行托管生产发布，不是本片授权验收 | N/A |

16项fixture测试不是真实IdP或事务证明；真实HTTP／PG与实际客户端结果单独保存，不将不同层级的检查数合并成独立请求数。真实原声明SHA为d065dcc2d8cf4227d78e6121f14dc28aed78196ca49704a76937f52b9959f6c2，是catalog.json字节而非业务镜像／JAR。

私密证据位于`.local/menu-role-governance/`：mg11-client-unit-final.log、mg11-http-delivery.log、mg11-hygiene-final.json、mg11-evidence.json，以及最终轮次的result.json／ci-client/result.json。逐文件与当前Admin制品SHA保留；本片未改Java或迁移，MG10精确CI37112631055已SUCCESS，不将旧CI当作新增工具验收。

## 审查与限制

- 首轮协议12项和真实链路通过后，审查补出“重试拒绝不能推翻之前提交事实”和截断响应异常边界，增加行为测试并复验当前版本。截断fixture第一次使用固定Content-Length时实际被识别为PROTOCOL_INVALID；改用真实损坏chunked传输验证HTTP异常分支，两者均不把未知结果写成成功。旧失败日志保留。
- 首次卫生检查指出阶段／检查点状态的闭集字符串和错误正文解析pass分支；改为稳定枚举并显式进入未知错误处理，最终检查通过。数值建议均对应契约大小、声明两版联合上限或隔离演练超时，不引入新框架。仓库无统一Python formatter／静态分析器，未安装依赖或虚称执行。
- 后端仍执行完整目录合法性、实时机器资格及原命令事务检查；客户端的声明校验不是独立构建签名，也不能证明当前业务运行制品。固定commit由受控构建输入提供，制品须从该提交materialize。
- 两新test／staging目标是隔离模拟；没有向原Commerce v2、原业务Grant或实际生产写入。全部自有HTTP进程及代理正常关闭，专用库／客户端、失败记录和恢复资料保留，未清理共享组件或历史。
- 现有GitHub CI自动执行协议测试与构建检查；真实客户端发布演练在获授权本机隔离目标执行。真实远程job需明确环境、受控目标文件和凭据，不声称未执行的托管发布通过。

操作、停止与原命令恢复见[PUBLISHER_RUNBOOK](PUBLISHER_RUNBOOK.md)。停止job／关闭入口不会撤销已经发布的目录；目录修正用更高版本，业务授权补偿走独立来源。

验证Handoff：implementation-validation / MG11 / COMPLETED / PASS；下游update-progress-docs后正常Git交付与精确CI，下一必要工作MG12，D-MIG待业务答复。
