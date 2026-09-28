# P1-04 实施证据（进行中）

当前完成的是共享协议与 auth 消费事务 pass。P1-04 整体 IN_PROGRESS，
OA 事务生产者、受控拉取/确认 CLI 和双库真实集成尚未完成。
用户已确认可信 OA 自动建立主体/员工成员，登录身份按精确来源标识绑定。
契约：CONTRACTS_P1_DIRECTORY.md；不更改原 63 节点 ID。

## 已实现

- DirectoryEvents 保持 protocol 零生产依赖，规范最小事实与双层摘要；关系有界、排序且不可变。
- V5 增加固定来源、Inbox、所有聚合版本摘要、类型化 JSON 投影、快照证明与冲突隔离。
- DirectoryAuthority/DirectoryGovernance/DirectoryJson、Mapper XML 和显式代码 TypeHandler。
- 精确首次开户、缺标识保留未绑定、同来源补绑定、标识变更隔离、来源退出/重新加入的成员代际。
- 手工成员停用优先；已绑定员工的全局停用不阻断离职事实，但绝不改变全局暂停状态。
- 同一身份在多个企业并发首次接入，通过唯一键与局部保存点回滚候选主体，只保留唯一获胜主体。
- 来源锁内去重/版本/投影/生命周期/审计/连续检查点同事务。真实冲突在业务回滚后独立留证并隔离来源。
- 快照需要 BEGIN/END、数量、序号及内容摘要全部匹配才 COMPLETE；不按缺失删成员。
- 部门父链环与深度溢出拒绝，缺失父事实不当有效授权树。OA FROZEN 状态按来源保留。

V5 已在本任务隔离库执行，不改写。无新增基础设施、外部依赖、生产部署、图写入、角色或应用准入。
源端采用共享协议制品；跨仓构建必须先安装当前 auth protocol，再构建 OA，不能使用旧 main 协议冒充已兼容。

## 验证

当前代码完成整仓 189 单测、44 PostgreSQL 集成测试、5 真实 Casdoor、
30 既有上下文/停用 HTTP 与 30 外部邀请 HTTP/CLI 检查，全部 PASS。
目录新增 15 个 PG 用例，覆盖并发、乱序、旧版本异内容、来源范围、审计故障回滚、
全局/成员停用、快照不完整/错误摘要与多节点组织环。
协议固定向量由独立 Python struct 编码生成；严格 JSON 拒绝强制转换、未知/重复/缺失字段。
证据见 P1-04_CONSUMER_TEST_RESULT.md 与 consumer-evidence-index.json。

Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（没有统一 formatter，静态分析器 N/A）。
事务审查已核对来源行锁、同数据源、独立冲突事务、无事务内网络与候选主体保存点回滚。
日志不打印目录原文或凭据。首次测试编译的 LifecycleCommand 参数顺序错误已修复，失败日志保留。

## 后续边界

按用户“同一任务分批提交、各批独立构建验证”的要求，这个 consumer pass 可单独提交到任务分支验证 CI。
这不把整个 P1-04 标为 DONE，也不声明已接通真实 OA。
后续在 OA 原目录 feat/oa-auth-p1-directory 完成源端事务事件、初始化/出口，再完成真实双库导入与确认。
用户已有 OA 脏文件不提交、不覆盖；不新增 worktree。
