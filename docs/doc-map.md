# 项目文档增量地图

本次只覆盖菜单／角色／权限治理的已批准MG00–MG19，既有其他项目文档未重新全仓盘点。2026-10-03同步基线040ff19eb8cdb2bac37e827cd3b15bf16264566b＋MG19差异；最终Git由治理PROGRESS_STATE和私密交付回执记录。

| 代码／配置区域 | 权威说明与验证 |
|---|---|
| Auth治理catalog／role／migration／lifecycle／personnel／review、Mapper及V21–V36 | [唯一计划／切片](design/oa-auth-unification/menu-role-governance/IMPLEMENTATION_SLICES.md)、各MG冻结契约与TEST_RESULT；[当前状态](design/oa-auth-unification/menu-role-governance/PROGRESS_STATE.md) |
| auth-console治理页面和真实API；Commerce声明／导航 | [治理索引](design/oa-auth-unification/README.md)、MG01／07／08及MG12–18契约／验收；[页面入口与实际边界](design/oa-auth-unification/menu-role-governance/OPERATIONS_RUNBOOK.md) |
| deploy/governance恢复／发布工具与authz-ci | [机器发布](design/oa-auth-unification/menu-role-governance/PUBLISHER_RUNBOOK.md)、[运行及四类恢复](design/oa-auth-unification/menu-role-governance/OPERATIONS_RUNBOOK.md)、[MG19定向真实验收](design/oa-auth-unification/menu-role-governance/MG19_TEST_RESULT.md) |

资料区分设计、实现、隔离环境实测和部署事实。真实OA联调、生产运行核验／部署及容量／RTO／RPO未在本轮执行。公开文档只保留连接引用：local/governance-test-db/app（45432／专库）、local/governance-test-idp/management（18094／独立fixture）、local/governance-graph/projection（18544／新UUID）；实际凭据在既有忽略.local的0600配置中，本轮未复制／轮换。
