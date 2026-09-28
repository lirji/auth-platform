# P1-05 实施证据

按 CONTRACTS_P1_INVITATIONS 实现外部邀请闭环，未扩展到角色、应用准入或业务数据授权。
新增 V4、InvitationModels/Commands/Governance、Mapper XML、受控 InvitationCli；
admin 增加双开关、专用 TokenAuthority 和精确 POST 安全链。
LifecycleCli 增加外部退出，旧 suspend 用例保持兼容。

数据库只保存随机证明摘要；创建和撤销使用受控操作者/企业/负责人范围、幂等命令与版本 CAS。
接受事务锁定邀请、当前负责人/主体与成员，核验精确 issuer/sub，创建或复用 HUMAN 身份。
PARTNER/GUEST 不能变成员工；ACTIVE 不被其他邀请延长，SUSPENDED 不复活。
LEFT 同类型外部成员重新加入保留 ID，增加 generation/version；旧邀请和旧上下文拒绝。
创建/撤销/接受/退出及其审计原子提交；审计失败回滚已通过真实 PostgreSQL 故障注入验证。

触发专题：API、授权范围、审计、配置、数据库事务、既有远程 Token 验证。
无新增缓存、MQ、调度器、搜索或基础设施依赖；没有跨服务数据库访问。
使用 Claude backend-implementation、runtime-and-deploy、implementation-validation、update-progress-docs、task-git-delivery。

窄验证及回归：181 单测、29 PG、5 真实 Casdoor、30 邀请 HTTP/CLI、30 既有上下文/停用 HTTP，全部通过。
旧 P1-03 JAR 能读取 V4 并继续过滤已停用员工。构建/静态脚本语法/diff 检查通过。
源码摘要及完整改动路径见 P1-05-evidence-index.json；独立验收见 P1-05_TEST_RESULT.md。
正式共享 IdP 升级仍 HOLD，此片不替换共享实例、不提供完整升级通过结论。
