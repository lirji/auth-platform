# W04 真实权限消费验证

2026-10-04，当前验证 PASS（Git 和精确 CI 待核对）。公共消费适配与 UI 位于 WMS 任务工作树，记录见其 `docs/iam/W04_TEST_RESULT.md`。后端固定 local-wms → ENT-DEMO；每个服务独立 SDK 凭据、每次操作重新校验。以下早期 VERIFYING 记录保留为过程证据，最终结果见末尾。

本仓新增 `deploy/governance-wms-runtime-smoke.py` 与 `deploy/governance-wms-failure-smoke.py`，复用已批准身份、目录、管理入口、可靠投影与专属隔离 MySQL。前者支持 W04/W05/W06 累积切片权限，只有当前安全源码打包且全部五个服务嵌套相同安全 JAR 才启动验收；后者严格验证 Auth Java 的父进程归属，SIGSTOP 后 finally SIGCONT，不停止共享服务。

私密 runtime-w04-111c2b8ad6c0：19 真实 PKCE/SQL 检查、5 真实浏览器检查、9 旧令牌故障/恢复/撤权检查 PASS。上一 fixture 有重复运行遗留的六个独立 reader-a 授权来源，实际全部撤销后才验证403，并记录已撤销 ID，避免只撤最新来源产生假验收。当前新 fixture 两个来源也撤销且可靠投影 ready，同一枚 Access Token 被拒绝，其他成员读权不受影响。

两次浏览器均没有 Token 注入、API 替换或 pageerror；桌面、跨仓深链与390px PDA截图已查看。WMS 前端完整82项、最后受影响16项、类型/build通过；10个新增安全测试通过。完整 Java/数据库集成测试仍运行，不预先宣称最终门禁通过。生成/文档结构检查与本仓5个引导边界测试通过。

本机曾反复系统睡眠导致测试墙钟超时，已通过系统睡眠日志确认，用仅本任务的2小时 caffeinate 暂时保活后验证；没有增加产品/测试超时或修改永久电源设置。Auth/WMS Git与精确CI尚待W04逻辑交付，本机Docker/生产部署均未切换。

最新runtime-w04-71a5c4d2296a：19真实PKCE/SQL、5普通浏览器、7企业-only后端、1企业-only浏览器、2企业授权撤销检查通过；截图已查看。追加`deploy/governance-wms-enterprise-smoke.py`仅操作当前fixture的工具成员与记录来源。

W04门禁HOLD：完整Java303项有一项AllocationExecutionProcessesIT失败，独立复验仍在启动探测失败；原报告/现场均保留，test-support补验通过，required gate只剩此失败。Docker引擎_ping曾超时，inspect/stats持续等待后恢复；共享引擎重启已询问用户，未执行。不能把宿主功能验收替代必需回归，不提交/发布失败版本。

恢复后的当前门禁PASS：正常Docker恢复135原容器配置/状态、36原运行服务健康后，同版AllocationExecutionProcessesIT150.2秒PASS，144必需检查PASS；最新UI39文件84项和build PASS。有界Review通过，hygiene原正则误分类逐项人工审查及无格式化器限制见WMS报告，未改变规则或降低业务验收。W04可进入Git/精确CI交付，尚未宣称推送完成。
