# P4 隔离运行说明

本地验收复用现有OA应用、workflow-platform Flowable引擎、Casdoor和P3图；不新增业务BPM实现，不修改共享workflow/Kafka信任配置。初次共享PG因已有连接接近上限而失败，故P4三库使用同版本独立PostgreSQL容器。共享实例/用户进程未停止或重配。

| 组件 | 回环端口 | 数据/版本 |
|---|---|---|
| P4 PostgreSQL | 15434 | postgres:16-alpine；与dev_infra实测同为16.15，独立命名卷；auth/OA/workflow独立Owner和库 |
| P4 Kafka | 19492 | apache/kafka:3.8.0，与dev_infra一致；独立命名卷/网络；不接共享固定consumer group |
| P4 workflow | 18300 | 已有workflow-server镜像的不可变image ID，私密配置固定 |
| OA任务进程 | 18420 | 当前仓库打包jar；JWT开启，REMOTE引擎模式 |
| auth授权进程 | 18421 | 真实业务服务凭据+Casdoor用户Token；P3资源事实契约 |
| auth管理/申请/回调 | 18422 | 真实用户Bearer及独立服务HMAC命名空间 |
| 丢回调ACK故障代理 | 18423 | 仅测试进程；真实转发后丢首个202，观察同event/body新签名重试 |
| 既有隔离Casdoor/P3图 | 18090/18544 | 沿用P1/P3已固定身份夹具和图，不重置 |

## 启停和复验

```bash
python3 deploy/governance-p4-runtime.py up
# 先按现有方式构建auth admin/server及OA jar；OA所需auth协议安装到其实际Maven仓库。
python3 deploy/governance-p4-e2e.py --oa /absolute/path/oa-platform --infra-env /absolute/path/dev-infra/.env
python3 deploy/governance-p4-runtime.py stop
```

up重复使用`.local/governance/p4/compose.env`和三个`runtime-*-db`，拒绝未知私密配置覆盖。stop仅停止本Compose资源，保留卷和数据。E2E自己持有Popen进程，并在finally退出这些进程和代理；每轮不同业务分区，Jar复制到该轮私密目录避免构建覆盖运行文件。全部配置0600，私密目录保留用于复验，不提交凭据。健康检查分别使用pg_isready、Kafka管理命令、实际/actuator/health。

内部workflow HTTP/Kafka仅在隔离本地网络中按现有开发模式运行；用户侧OA依然JWT，中央启动与回调依然原文HMAC。本结果不证明生产TLS/ACL、容量、备份恢复或邮件渠道。P4工作流定义仍为OA原有oaGenericApproval，由原部署器部署到隔离引擎。

## 持久Worker

`ApprovalStartCli`每轮最多一条，`ApprovalDecisionCli`每轮最多20条，`RequestLifecycleCli`每轮最多100条，`RequestNotificationCli`每轮最多20条。均使用同一个显式0600文件中的jdbc与access.tenant/application/environment。启动器另外需要approval.oa-url与approval.outbound-key。投影继续使用P3的ReliableProjectionCli和projection.kind=POLICY/DIRECTORY。生产/常驻调度由后续获授权运行环境安排，本轮显式启动有限批次，不偷偷开无限后台循环。

总开关requests.enabled=false同时关闭申请及回调HTTP，不等于关闭独立Worker；回退时必须记录未接收回调的恢复安排，不能据此停掉未收敛Grant的投影/到期回收和Inbox消费。SQL实时有效期始终生效。
