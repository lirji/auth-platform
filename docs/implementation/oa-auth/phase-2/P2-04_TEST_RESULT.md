# P2-04 单执行者真实图投影验收

PASS。全仓package通过；显式专用PG/graph配置执行 `./mvnw -q -pl auth-platform-governance -am -Pgovernance-graph-it verify` 退出0，GrantGraphIT 5项全部通过。

- 真SpiceDB写入与水位持久化后ACTIVE，SQL与同Grant图资格共同允许；未完成分区不可用。
- 撤销提交后即使旧图仍ALLOW，中央路径仍拒绝继续；删除投影完成后明确DENY。
- 图凭据失败保留PENDING与有界重试；恢复后成功。
- 真图成功但SQL ACTIVE回执被数据库约束拒绝：事务回滚，分区仍不ALLOW；重试幂等恢复。
- 真图写窗口中提交撤销，旧版本回执不能复活Grant。
- SQL有效期、成员停用和应用停用覆盖图里的旧允许。

`.local/governance/p2/p2-04-graph.log`；专属图127.0.0.1:18543，SpiceDB实测v1.56.2镜像摘要aa96009，独立dev_infra PostgreSQL库。共享8543和Casdoor8000未修改。

实现：GrantProjection、ProjectionMapper XML、数据库会话互斥、持久意图/退避/5次上限、AccessAuthorization；admin受控ProjectionCli每轮50条/30秒。完整远端CAS/多实例撤权认证留在P3，没有跨请求ALLOW缓存。CLI耗尽重试仍显示pending，不伪称完成；当前运维恢复策略在阶段运行手册补充。

技术来源：[SpiceDB schema](https://authzed.com/docs/spicedb/concepts/schema)、[relationships](https://authzed.com/docs/spicedb/concepts/relationships)；以本次安装版实际写/查为验收。没有新增框架或公网依赖版本。
