# P7-02 指标与处置入口

复用auth-server现有Actuator/Micrometer；新增authz.governance.requests Timer，两个标签只有有限operation与outcome。结果包括ALLOW、DENY、AUTHN、CONDITIONAL、MIXED、ERROR、INVALID、SUCCESS。HTTP异常优先于业务body结果；503不能计入DENY。没有复制/记录请求体、Token或身份上下文，没有额外指标服务。

P7本地业务端口18170/18171；管理metrics仅在18270/18271回环开放，生产需受控采集网络。默认产品仍不开放metrics。协议正常只返回ALLOW/DENY；CONDITIONAL为已有协议演进的有限类别，无该响应时不能制造样本。

| 信号 | 分类 | 处置 |
|---|---|---|
| ERROR或AUTHZ_STATE_NOT_READY增多 | 依赖/未收敛，不是权限不足 | 先查图/PG连接，再查policy_partition、directory_fence与projection_stream；保持拒绝 |
| AUTHN增多 | 凭据/受众/发行方状态 | 核对轮换阶段与客户端，不记录Token；不能回退到旧凭据绕过 |
| DENY增多 | 明确业务拒绝 | 用服务端trace_id、decision_id关联受保护审计，核对目录/Grant来源 |
| projection_stream.failures=5或分区BLOCKED | 投影隔离 | 查CLI固定result与不可变operation/receipt，按CAS恢复，不直接标READY |
| 长期UPDATING或未完成grant_projection | 积压 | 聚合数量/最老created_at；阈值需结合实际生效预算接受 |
| Inbox冲突、目录quarantined、未知图marker | 安全完整性 | 停止对应写入来源并核对证据；禁止覆盖冲突后继续 |
| 恢复缺日志、验证误允许 | 关键安全失败 | 不开放恢复端点；补齐撤销/目录事件或保留HOLD |

本地基线不设虚构生产报警阈值、负责人或留存期限。异常必须在运行手册对应步骤处理；生产接收渠道与值班Owner尚待指定，不能把本文件视为已接入外部告警系统。后台投影结构化日志已有worker/kind/result，数据库持久operation、receipt、retry与fence是事实来源。分区与积压观测用只读SQL，保留任务边界，禁止为指标执行无界全量明细导出。

运行时DB与图分段耗时、目录延迟持续采集及生产告警路由尚未全面实现；本轮提供入口端到端指标和演练中依赖故障/持久积压快照，不冒充完整APM。
