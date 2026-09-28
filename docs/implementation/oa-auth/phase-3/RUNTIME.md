# P3 隔离验证Runtime

复用P2锁定的SpiceDB v1.56.2镜像digest与dev_infra PostgreSQL16；`python3 deploy/governance-graph-isolation.py --phase p3`创建或恢复auth-governance-p3-graph，仅127.0.0.1:18544。持久数据在专用PG数据库而不是内存，配置/账号随机生成并保存在.local/governance/p3/graph（不入Git）。P2 --phase p2默认18543，共享8543不受影响。

schema `governance-p3.zed`使用gov_access_grant/gov_group和gov_partition#head。只在空的自有图初始化；已有schema不一致立即失败，不覆盖。read/write readiness通过真实HTTP schema接口确认，不依赖固定等待。

应用不自动启动后台线程；后续worker沿显式CLI执行有界批次。投影HTTP每实例最多16在途，512KiB响应上限、1—100个业务关系/批、总超时最大10秒（测试3秒）；不重定向，远端要求HTTPS。耗尽按依赖故障返回，不扩张队列。

保留隔离数据库、配置与日志以复验；没有删除库/卷命令，没有生产部署。原P2 IdP18090及图18543继续保留。停止图可使用普通docker stop对应自有容器，但本轮验证结束前保留。

P3 worker入口 `com.lrj.authz.admin.governance.ReliableProjectionCli` 使用admin可执行Jar中的PropertiesLauncher。一个0600配置包含既有jdbc.*、graph.http/key、access.tenant/application/environment以及projection.kind=POLICY或DIRECTORY。每个新进程随机worker UUID；一次最多一个50项批次，30秒总预算后退出并保留未知结果供恢复。exit0仅该分区READY，exit2表示需要后续批次/恢复或隔离，exit3表示输入/依赖/总超时故障。受控调度须同时收敛目录与策略，不能只看一个exit0宣称整个应用就绪。
