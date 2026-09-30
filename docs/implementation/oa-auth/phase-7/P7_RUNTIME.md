# P7 本地加固运行与恢复手册

范围为隔离合成夹具。执行`python3 deploy/governance-p7-rehearsal.py`会创建独立PG、两份图存储、新Casdoor组织/客户端及auth进程；不迁移原商城，不调整共享实例。要求Docker、JDK/Maven、当前打包JAR，以及已验证18090隔离IdP管理配置。私有目录 `.local/governance/p7/<run>/` 为证据权威，配置/备份/日志0600。不能将该脚本当作任意生产库的通用恢复命令。

## 构建与运行

1. 当前版本：`mvn -pl auth-platform-server,auth-platform-admin -am package -DskipTests`；必要单测另见TEST_RESULT。
2. 混部基线来自auth提交`c8df1b19d309580d277021089c0a837143fec7b0`：在`.local/governance/p7-baseline-source`用`git archive`提取该提交的pom.xml、.mvn、mvnw及六个auth-platform模块；运行`mvn -f .local/governance/p7-baseline-source/pom.xml -pl auth-platform-server -am package -DskipTests`。这是只读旧源制品构建目录，不是新工作树，不修改原项目分支。
3. 确认回环45433、18545—18548、18170—18174、18270—18273未被其他服务占用，再运行脚本。每档请求数默认48，CLI只允许16—200；并发固定1/4/8。
4. 结果在`result.json`、逐步checkpoint、load-*.json、operational-snapshot.txt。失败退出不代表以前成功步骤被撤销；日志不在终端展开。脚本停止自己持有的进程及带本次标识容器，保留容器、数据库与所有证据。

负载为1租户/1应用/2员工成员/3直接范围Grant，80%热点允许与20%越界拒绝；每个请求执行真实IdP查询、SQL快照与图检查。混部一实例P6、一实例P7。故障代理是测量链路的一部分，不能外推为无代理生产吞吐。连接池、CPU、堆预算见P7-01清单，生产SLO未定义。

## 指标与故障排查

- `/actuator/metrics/authz.governance.requests`仅在独立回环管理端口；查有限operation/outcome标签、COUNT/TOTAL_TIME/MAX。百分位由本次逐请求样本nearest-rank计算，包含错误请求。
- `deploy/governance-p7-observe.sql`在授权库中只读执行，2秒预算；超时就是观测未完成，不能返回空值冒充健康。没有为指标新增持久表或外部告警服务。
- 系统ERROR保持503，与业务DENY/401区分。先对照PG、图、Casdoor状态和只读积压快照；不要让消费者重试写请求或使用旧ALLOW兜底。
- Casdoor当前实现实时introspection：已有签名密钥缓存并不保证IdP断开时继续授权。专用代理故障负例实际验证了503。
- 管理进程中断不影响当前日常检查；审批/目录来源同步中断的时效义务另见P1/P4记录，不能以停止管理进程替代所有OA故障场景。

## 轮换

服务身份先更新A实例并核验新凭据，明确B仍是旧凭据接受窗口；更新B并排空/停止旧实例后，旧凭据在所有当前实例401。不得在旧实例仍承接流量时宣称撤销已全局完成。

本次使用新建Casdoor业务应用和两张独立RS256证书，应用专属JWKS；更新签名证书及客户端密钥，再重启验证器清除旧公钥缓存。新Token允许，旧Token和旧客户端密钥拒绝。旧证书留档，不删除共享cert-built-in。单客户端密钥更新期间可能出现ERROR，需要排空/维护窗口；本次没有零停机承诺。核对依据为[Casdoor v4.11.0证书与应用绑定实现](https://github.com/casdoor/casdoor/blob/v4.11.0/object/cert.go)，实际行为以本次运行证据为准。

## 隔离恢复

1. 使用`pg_dump --format=custom --no-owner --no-acl --schema=auth_governance`取得一致备份，记录SHA256；创建全新、不同Owner的目标库，`pg_restore --single-transaction`还原，绝不使用`--clean`覆盖现有库。
2. 在任何业务流量开放前，核对备份之后的完整权威撤权/目录/迁移日志。此次实验范围封闭，仅一条明确保存的撤权命令；没有凭此建立生产CDC/WAL日志完整性保证。
3. 所有policy/directory设为UPDATING，移除当前zed_token，推进desired_epoch；旧stream失租，当前marker/游标/确认水位归零，旧PENDING操作标为SUPERSEDED。历史operation/receipt保留，只清除“当前图”的引用。此步仅在离线新恢复库执行。
4. 通过正式管理API幂等重放备份后的撤权。新建空图、加载版本匹配schema；未投影时HTTP必须503。
5. 使用既有ReliableProjectionCli按POLICY、DIRECTORY投影，生成新操作/回执与水位。HTTP readiness有界等待；只有活跃允许、撤权拒绝、过期拒绝均通过才完成本次恢复。
6. 实测时间含备份、目标创建、图初始化、进程启动及验收；这是小夹具恢复耗时，不是生产RTO。RPO仅说明本次已知事件完整重放，生产日志保留/异地备份仍待决定。

旧进程仍持有旧图凭据，不能写新图；恢复库/新图外部访问默认关闭。生产恢复必须同样保证旧写入者隔离、日志完整，以及业务资源Owner的独立恢复来源。本次未证明任意资源模型、组扇出、外部成员和生产审计全集的恢复能力。

## 回退

P7新增观测功能没有schema迁移，P6/P7双版本访问同一V19权威模型。退回P6会失去新增指标，不能回退已撤销的服务凭据、客户端密钥或签名信任。凭据轮换后的旧配置不再可用，代码回退仍需携带新配置。商城已接管单元的CENTRAL/STOPPED约束继续沿用P6，不退回旧授权放行。

后续认证故障分类整改与共享IdP数据库限制见[P7_AUTH_FAILURE_FIX](P7_AUTH_FAILURE_FIX.md)；本文件原始实测作为历史证据保留。

后续独立身份服务与双新版容量基线见[P7_ISOLATED_CAPACITY](P7_ISOLATED_CAPACITY.md)；与原共享身份服务/混部结果分别记录。
