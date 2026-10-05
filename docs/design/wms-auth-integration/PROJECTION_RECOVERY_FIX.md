# 投影 READY 轮次的失败预算修复

W07 初轮实施、真实本机验收、精确 CI 与两仓 main 发布已完成。最终观察发现，既有 commerce 的两个投影分区在多次间歇依赖失败后 BLOCKED，WMS 两分区仍 READY。四个远端 marker 与 SQL 一致；真实管理者 PKCE 登录后通过两个有审计的 retry-strict 命令恢复，没有直接修改 SQL 状态或创建角色/Grant。

根因：`ReliableProjection.prepare` 的稳定 READY 分支在成功读取、核对远端 marker 后直接返回，失败次数只在新批次确认时清零。没有新授权变更的分区会累计零星失败，最终误用五次连续失败预算。

修复仅在当前租约有效、远端 marker 与 SQL 一致、栅栏 READY 且 desired/applied 相同时，重置该流的失败次数和退避时间。Mapper SQL 检查 worker、租约代际和到期时间，并检查影响行数；不改栅栏、marker、操作或授权回执。五次连续失败、未知 marker 和协议冲突仍隔离；失效租约不能清计数。没有新增迁移、接口、自动 retry-strict 或放宽调用期限。

## 验证与运行制品

专属 PostgreSQL 测试库与真实现有 Graph、随机隔离 UUID 的 `ReliableProjectionIT` 共 9 项通过，另有当前 reactor 单元测试通过。新增三项覆盖 POLICY/DIRECTORY 六轮“失败→成功”、五次连续失败仍 BLOCKED、失效租约不能清零；既有未知 marker 隔离、旧执行器与回执恢复测试通过。稳定 READY 六轮没有新图 marker 或操作记录。

只更改 Auth 治理 Java/Mapper/回归；WMS 源和固定 SDK 源未改，WMS `2efa151` 的完整 CI 仍作为其门禁。Auth 新精确 CI、不可变源码镜像、实际投影运行与正常 Git 发布终态以私密 `projection-ready-repair/` 和 `delivery-result.json` 为准，不预称新制品已部署或 CI 成功。原失败、初轮镜像和配置保留。

本修复结束成功轮次之前的连续失败预算，不承诺共享本机资源没有超时，不恢复已撤来源，也不将代码回退当作业务补偿。当前连接和期限见 [Runtime](RUNTIME_SPEC.md)。
