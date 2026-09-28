# P2隔离图运行

按runtime-and-deploy复用已选SpiceDB v1.56.2与dev_infra PG16。`python3 deploy/governance-graph-isolation.py` 幂等准备专属数据库和auth-governance-p2-graph容器；固定镜像digest，回环18543，1CPU/512MiB。私密env和配置仅.local/governance/p2/graph，0600；PG负责持久化，停止容器不删库。

顺序：专库/角色→datastore migration→容器启动→schema/read就绪→仅空专属图初始化schema→发布客户端配置。已有模型不自动整体替换。不映射gRPC端口，不把新图凭据提供给旧admin/server工作区。

ProjectionCli位于admin JAR，以PropertiesLauncher启动；配置含jdbc.*、graph.http/key、access.tenant/application/environment。单轮有界，不默认启动后台任务；调用结果中的pending须为0才完成。P3前仅单执行者，停止/回退保留授权/意图/审计，不能回滚SQL迁移或复活已撤销Grant。
