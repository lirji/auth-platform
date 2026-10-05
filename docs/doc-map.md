# 项目文档增量地图

本次只覆盖菜单／角色／权限治理的已批准MG00–MG19，既有其他项目文档未重新全仓盘点。2026-10-03已同步至d283ad7171ff8ff224517b2e7818e86542741576（MG19产品／精确CI37173735107 SUCCESS）及随后纯文档状态／根README；最终Git由治理PROGRESS_STATE和私密交付回执记录。

后续同日获授权的本机部署已同步：源码 fd6bf5911981、三个治理镜像 rev-fd6bf5911981、实际 V36 与5273验收，见[部署结果](deployment/menu-role-governance-docker-20261003.md)。本次只更新对应运行说明和当前进度，未重做其他项目盘点。

| 代码／配置区域 | 权威说明与验证 |
|---|---|
| Auth治理catalog／role／migration／lifecycle／personnel／review、Mapper及V21–V36 | [唯一计划／切片](design/oa-auth-unification/menu-role-governance/IMPLEMENTATION_SLICES.md)、各MG冻结契约与TEST_RESULT；[当前状态](design/oa-auth-unification/menu-role-governance/PROGRESS_STATE.md) |
| auth-console治理页面和真实API；Commerce声明／导航 | [治理索引](design/oa-auth-unification/README.md)、MG01／07／08及MG12–18契约／验收；[页面入口与实际边界](design/oa-auth-unification/menu-role-governance/OPERATIONS_RUNBOOK.md) |
| deploy/governance恢复／发布工具与authz-ci | [机器发布](design/oa-auth-unification/menu-role-governance/PUBLISHER_RUNBOOK.md)、[运行及四类恢复](design/oa-auth-unification/menu-role-governance/OPERATIONS_RUNBOOK.md)、[MG19定向真实验收](design/oa-auth-unification/menu-role-governance/MG19_TEST_RESULT.md) |
| 既有governance Docker profile、私密runtime.env镜像绑定、本机auth_governance迁移 | [本机运行入口](../deploy/governance/README.md)、[本次部署事实与回退范围](deployment/menu-role-governance-docker-20261003.md) |

资料区分设计、实现、隔离环境实测和部署事实。真实OA联调、生产运行核验／部署及容量／RTO／RPO未执行。公开文档只保留连接引用：local/governance-test-db/app（45432／专库）、local/governance-test-idp/management（18094／独立fixture）、local/governance-graph/projection（18544／新UUID）及本机Docker运行说明。实际凭据在既有忽略.local的0600配置中；部署回退配置与备份仅保存到本机私密证据目录，未轮换凭据。

## WMS中央权限增量

本轮只同步独立local-wms→ENT-DEMO和W00–W07，不改Commerce鉴权或Driver/ERP。

| 区域 | 权威说明 |
|---|---|
| WMS能力/资源、SDK、Owner动作、本人导航 | [契约](design/wms-auth-integration/CONTRACTS.md)、[切片](design/wms-auth-integration/IMPLEMENTATION_SLICES.md)及W01–W06 TEST_RESULT |
| Auth server源码镜像、wms-auth Compose、relay/init、机器工具 | [Runtime](design/wms-auth-integration/RUNTIME_SPEC.md)、[W07验收](design/wms-auth-integration/W07_TEST_RESULT.md)、[审查](design/wms-auth-integration/W07_REVIEW.md) |
| 已完成状态、实际Git/CI回执引用 | [进度](design/wms-auth-integration/PROGRESS_STATE.md)；私密.local/wms-auth-integration/delivery-result.json |
| READY成功轮次与连续失败预算、投影恢复补充 | [投影恢复修复](design/wms-auth-integration/PROJECTION_RECOVERY_FIX.md)；专属PG/Graph回归与当前制品回执 |

公开连接标识local/wms/identity、local/wms/authorization、local/wms/machine-identity；实际账号、期限与凭据引用保存在忽略的0600 ACCESS.md，不公开值。
