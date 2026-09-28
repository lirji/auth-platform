# P2隔离图运行

按runtime-and-deploy复用已选SpiceDB v1.56.2与dev_infra PG16。`python3 deploy/governance-graph-isolation.py` 幂等准备专属数据库和auth-governance-p2-graph容器；固定镜像digest，回环18543，1CPU/512MiB。私密env和配置仅.local/governance/p2/graph，0600；PG负责持久化，停止容器不删库。

顺序：专库/角色→datastore migration→容器启动→schema/read就绪→仅空专属图初始化schema→发布客户端配置。已有模型不自动整体替换。不映射gRPC端口，不把新图凭据提供给旧admin/server工作区。

ProjectionCli位于admin JAR，以PropertiesLauncher启动；配置含jdbc.*、graph.http/key、access.tenant/application/environment。单轮有界，不默认启动后台任务；调用结果中的pending须为0才完成。P3前仅单执行者，停止/回退保留授权/意图/审计，不能回滚SQL迁移或复活已撤销Grant。

失败达到5次时分区继续非就绪。确认graph目标/凭据/数据库恢复后，由当前管理委派通过POST `/access/retry-projection` 提交固定command_id、完整分区、grant_id和expected_version；操作写审计，保持PENDING/REVOKED，之后运行ProjectionCli。禁止手改ACTIVE或伪造zed_token。已被新撤销版本取代的旧失败意图由下一轮自动完成废弃；远端旧写栅栏仍属于P3，不能启动并行执行者。
