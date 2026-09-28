# P2 → P3 Handoff

## 停止点

本轮用户要求「P2全部做完之后先暂停」。P2所有9个节点（01—08，含05a）已完成实现和本地验收，Git/CI最终观察见P2_DELIVERY_RESULT。P3未启动；本文件仅交接，不授权继续执行。

## 已有闭环

- auth拥有应用登记/清单、固定RoleVersion、直接AccessGrant及审计/意图；P1主体/成员仍是身份权威。
- 新管理API只接受专用管理Token和当前委派；新增能力不修改旧角色版本。
- 独立治理图gov_membership/gov_grant；单执行者先写图，回执后ACTIVE。授权同时检查当前SQL资格与同一Grant图关系；有pending时不ALLOW。
- SDK可信服务+最终用户双身份，app/env来自服务配置；旧AuthzEngine9项/AOP仍保留，Boot4宿主验证完成。
- commerce GET /v1/operations/stores经显式本地OPERATOR身份桥，保留旧Actor/memberId/回执；SQL先按租户过滤再分页，业务入口显式再次授权。
- auth-console /governance最小本人菜单和管理授权状态；所有可见数据源于API，前端隐藏不构成鉴权。

## 契约和运行入口

契约见 docs/design/oa-auth-unification/CONTRACTS_P2_{CATALOG,ACCESS,AUTHORIZATION,PRESENTATION}.md。

admin/server分别显式开启authz.governance.enabled与access.enabled；菜单另需presentation.enabled。所有私密配置0600。角色/Grant由管理API维护；失败耗尽恢复使用retry-projection，随后单执行者ProjectionCli。不得手写ACTIVE/zed_token。

隔离运行：PG16/商城MySQL8.4复用dev_infra，治理图18543独立于旧8543；Casdoor18090独立于共享8000。后台验收启动的admin/server/商城/Vite已退出，隔离数据库和图/IdP容器保留便于复验，不清共享数据。私密记录位于auth .local/governance/p2、commerce .local/oa-auth-p2，不提交凭据；旧夹具和日志保留，Token会过期。

## P3明确未完成

细粒度ScopePlan、SQL范围守卫、组织组继承、严格远端CAS/版本栅栏、多实例投影、撤权完成语义、跨请求缓存认证均未实现。P2 advisory锁仅防误启第二执行器，不是跨故障远端栅栏。P3必须沿原63节点DAG和细分P3-04a/b/c验证，不改业务边界、不跳过真实故障测试。

共享Casdoor升级HOLD仍在：隔离后端Token与会话后的页面通过，不能由此宣称正式SSO迁移或生产就绪。OA正式目录、审批、生产授权接管不在本轮。

## 恢复前读取

先读根CODEX_PROGRESS.md、PROGRESS_STATE.md、EXECUTION_DAG.json及本交接。只有用户新授权继续P3后再执行；复用现有原目录任务分支策略，保护OA已有用户改动。不得重做P1或用旧commerce候选18片计划覆盖auth当前权威63节点DAG。
