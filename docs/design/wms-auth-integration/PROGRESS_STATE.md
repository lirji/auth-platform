# WMS中央权限当前进度

W00–W07实施及本机验收全部DONE，独立local-wms绑定ENT-DEMO。WMS继续拥有业务数据，Auth管理成员/角色/授权/投影；生产不在范围。

初轮精确 CI 与正常 main 发布已成功（Auth cd46c21、WMS 2efa151）。最终持续观察补充修复 Auth 稳定 READY 投影的连续失败预算，真实 PG/Graph 9 项回归通过；该补充的精确 CI、源码镜像、实际运行和 Git 终态以私密 delivery-result.json 为准。详情 [投影恢复修复](PROJECTION_RECOVERY_FIX.md)，不重做 WMS 实施或更改固定 SDK。

W06 Auth dd04404/CI37249142702、WMS 0f62d41/CI37249068115均全CI SUCCESS并正常发布main，原W04失败保留。W07没有Java/UI产品源码变更：四当前源码镜像、可信HTTPS18545、5独立600凭据和旧机器Owner23项完成；原wms-local六应用及既有管理三服务已实际接入，连续projector/WMS两分区READY。

原环境6项真实PKCE、5张当前图实看、实际管理范围授予/严格撤权/同旧Token、最终11故障/恢复、142表一致、原dirty WMS55文件保护均PASS。详情 [W07验收](W07_TEST_RESULT.md)、[Runtime/续期/回退](RUNTIME_SPEC.md)、[审查](W07_REVIEW.md)。仅本任务私密堆预算64–256MiB，同一Graph本体转发并保留Java回环；原IdP/Graph实例不变，原控制台制品不变。失败证据不删。

实施验收已完成，受控Git/CI交付按最新精确提交独立执行。每个仓库当前commit、远端main与CI终态由私密 `.local/wms-auth-integration/delivery-result.json` 记录；恢复先核对该回执，不重复部署/授予。Git授权包含正常合并/推送，不包含生产。

复用两个任务分支和既有WMS任务树，不修改原dirty树/Driver/ERP/local main。自有测试应用、数据库正常停止，旧制品/备份/凭据/证据/网络/工作树保留。机器一小时与演示Grant24小时遵循原协议，显式续发/重新授予；不声称永久授权、全目标实际回滚、TCC业务、容量或灾备。
