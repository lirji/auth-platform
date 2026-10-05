# W07 本机运行与恢复

范围为已批准的本机 `auth-platform` / `wms-local`。中央组织 `local-wms` 固定绑定业务企业 `ENT-DEMO`，租户 UUID 保存在私密配置。WMS 继续拥有业务数据；Auth 拥有成员、角色、授权与投影。生产不在范围。

## 进程与连接

| 连接 ID / 组件 | 地址与依赖 | 持久化与配置 |
|---|---|---|
| local/auth/management | 浏览器 5273；既有 console、admin、projector | 复用既有 governance Compose、治理库和管理配置；projector 扫描原两配置加 WMS 两配置 |
| local/wms/identity | 签名 issuer `http://localhost:18090`，客户端 `wms-central` | 既有 Casdoor 4.11；独立组织及公开 PKCE 客户端 |
| local/wms/authorization | SDK `https://host.docker.internal:18545`；宿主仅绑定 127.0.0.1 | 新 `wms-auth` profile 的 relay/server/init，复用既有 auth_governance 与 18544 授权图 |
| local/wms/applications | 18181 入库、18182 出库、18183 库存、18184 序列登记、18185 履约 | 唯一 WMS 根 compose.yaml，中央 overlay 只增加固定身份和五独立凭据卷 |
| local/wms/console | 18180，真实业务反代和 18090 PKCE | 构建时绑定中央 issuer/client；不在页面放演示数据 |
| local/wms/machine-identity | 旧 issuer 8000 / audience wms-platform，仅内部链 | 三个明确机器主体；不拥有中央人类 Grant，不修改原人类角色边 |

源码镜像使用既有 Java 21、Node 22、nginx 1.27 系列；实测 relay 为 nginx 1.27.5。SDK 固定源与校验和以 [契约](CONTRACTS.md) 和 WMS Dockerfile 为准。新授权入口不引入新数据库或消息系统。

relay 与 server 共享网络命名空间，18090/18544 反代只监听回环。relay 加入既有外部 `dev-infra` 网络，直接连接 `auth-governance-casdoor:8000`；Graph通过受控extra_hosts绑定同一现有容器的8443内部端口；各Java客户端仍使用回环18544。Docker DNS 使用 IPv4 和共享 upstream 后台更新；日志仅记录路径、状态和耗时，不记录 Authorization、请求正文或查询参数。`resolve` 在当前实测版本可用，见 [nginx 官方说明](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server)。

## 秘密与 TLS

准备工具 `deploy/governance-wms-docker-prepare.py` 从已核对的 W03 私密状态生成 `.local/wms-auth-integration/docker/`；已有目标拒绝覆盖。目录 0700、文件 0600，不进入 Git 或镜像。CA 有效 365 天，服务证书 90 天，SAN 为 localhost / host.docker.internal / 127.0.0.1。不能使用跳过验证的 curl 或 Java 参数。

初始化进程仅负责将宿主私密文件复制到本任务命名卷，设为 UID10001、目录 0700、文件 0600。五个 WMS 应用各自只挂载自己的卷，包含独立 `central.properties` 与仅含 CA 公钥的 truststore；Auth server 的 PKCS12、口令和服务注册配置位于另一个卷。服务均非 root 运行。CA 私钥保存在宿主私密目录，不交给消费者。

证书到期前在新的受控检查点准备证书并核对 SAN/信任链，再更新 server 和消费者信任；没有实现自动续期。机器发行工具可重新发行专属机器令牌；本轮有效期1小时。对账机器令牌按企业 SHA256 文件名置于 inventory 独立卷的 `recon-tokens`，不改原宿主凭据文件。到期前显式续发再运行本任务初始化服务复制到卷，不扩大 scope 或延长既有协议期限：

```bash
python3 deploy/governance-wms-machine-provision.py \
  --state-directory "$WMS_MACHINE_STATE" --bootstrap-directory "$WMS_AUTH_BOOTSTRAP_DIR"
docker compose --env-file "$AUTH_RUNTIME_ENV" -p auth-platform \
  -f deploy/docker-compose.yml --profile wms-auth run --no-deps governance-wms-init
```

变量分别指向私密 `docker/machines` 和 `docker/bootstrap`；真实令牌和期限只保存在私密连接记录。未实现自动续发；过期时内部请求拒绝或对账不可用，不能以旧文件存在作为有效证明。

## 启停与健康

从 Auth 仓库运行；`AUTH_RUNTIME_ENV` 指向准备工具生成并绑定实际镜像摘要的私密文件：

```bash
docker compose --env-file "$AUTH_RUNTIME_ENV" -p auth-platform \
  -f deploy/docker-compose.yml --profile wms-auth config --quiet
docker compose --env-file "$AUTH_RUNTIME_ENV" -p auth-platform \
  -f deploy/docker-compose.yml --profile wms-auth up -d --no-build --pull never \
  --wait --wait-timeout 180 governance-wms-relay governance-wms-init governance-wms-server
```

WMS管理者的诊断/审计读取另按固定分区、成员 UUID 和当前代际加入 `portal.diagnostic.*`；原 commerce 配置保留，管理委派不自动赋予该资格。

管理面继续使用已有 `deploy/governance/run.sh` 与原 runtime.env/dependencies.env；更新 WMS resource 类型所需 admin/projector 镜像时必须先核对精确 CI、备份和不可变镜像。追加 WMS 两投影配置后正常重启同一个 projector，不创建第二套权威来源。

从 WMS 已核对版本运行；`WMS_RUNTIME_ENV` 指向保留原环境、端口和挂载来源的私密配置：

```bash
docker compose --env-file "$WMS_RUNTIME_ENV" -p wms-local \
  -f compose.yaml -f deploy/compose.console-release.yml \
  -f deploy/compose.central-auth.yml config --quiet
docker compose --env-file "$WMS_RUNTIME_ENV" -p wms-local \
  -f compose.yaml -f deploy/compose.console-release.yml \
  -f deploy/compose.central-auth.yml up -d --no-build --pull never \
  --wait --wait-timeout 240 inbound outbound inventory fulfillment serial-registry console
```

原有消息/缓存开关已启用时，先正常启动原 `kafka`、`redis`，等待健康，再启动应用；本轮未重建 Topic 或启用额外 worker。不能只依据默认关闭开关的依赖关系启动。

WMS readiness 为各服务 `/actuator/health/readiness`，Auth server 为可信 HTTPS `/actuator/health`，relay 为本任务 healthz，projector 使用真实批次回执及既有健康时间戳。命令返回成功、容器健康与实际权限/页面验收分别记录。正常 stop 不删除卷。

## 开关、机器与回退

中央开关默认关闭；开启后缺配置启动失败，401/403/503 清空旧界面提示，公开入口不回退旧 JWT。内部链保留旧发行方，但必须有完整四项配置及明确主体白名单，Controller 继续校验专用 scope、企业、仓和事务标识。

机器兼容验收使用实际旧 IdP 发行的 inventory-worker、recon-worker、wms-fulfillment。scope 分别为 serial.registry.read/write、recon.evidence、inventory.tcc.try。序列认领/激活/重放与来源窗口读写使用隔离真实 MySQL。TCC 只证明发行/内部过滤链，不宣称完成 TCC 业务；原本机 RM 和执行 worker 开关保持既有关闭状态。

更新前保留原容器 inspect、私密 env、原 WMS 两 MySQL dump 和逐表摘要、旧身份库/治理库 dump、旧镜像。失败回退引用这些已核对的旧制品和原环境，停用中央 overlay 并使用旧 issuer/client；控制台必须同时回到旧构建绑定。新旧序列 Owner 已在同一个新状态测试库验证读取和原幂等键重放，不恢复数据库快照或撤销已提交业务效果。

中央授权状态、数据库数据恢复与镜像回退分别处理。不得清空授权图、卷或原业务表来模拟回退成功。原库中的异步 worker 会沿原协议继续工作，不能将正常后台推进误称为逐表完全不变。

## 验收与当前状态

四个当前源码镜像已构建；隔离 Docker 的可信 TLS、未知 CA/错误主机名拒绝、五服务独立凭据、非 root、越仓拒绝、同旧 Token 撤权与 Auth 故障恢复共 18 检查通过。实际旧 IdP、Owner、SQL 和旧镜像共存 23 检查通过。原失败日志和数据保留。

现有 admin/projector 已更新到 Auth W06 精确 CI 通过的源版本，console为回环Graph转发配置正常重建，镜像/静态制品不变；IdP/graph实例保留。原wms-local六个应用已切换上述源码镜像并healthy。两MySQL镜像及业务数据卷相同，142表行数/摘要相同；两初始化目录挂载指向同字节任务源码。实际管理页角色/范围授予/严格撤权和既有持续projector已验收。当前浏览器、Git/CI的终态见 [W07验证](W07_TEST_RESULT.md) 和 [进度](PROGRESS_STATE.md)。

本机共享VM出现内存回收停顿，当前五WMS进程由私密WMS_RUNTIME_ENV设置 `WMS_APP_JAVA_OPTS=-Xms64m -Xmx256m`。原较高预算保存在私密回退env；改变预算或承载数据量后需重新验证，不推导容量承诺。

## 受控Graph连接绑定

准备工具从既有 `auth-governance-graph` 核对运行状态、127.0.0.1:18544→8443和实际IPv4，保存 `graph-container-binding.json`。WMS relay通过 `WMS_AUTH_GRAPH_IPV4` 的extra_hosts转发到本体。原治理console通过私密 `governance-graph-peer.conf` 覆盖同一个Graph上游；`GOVERNANCE_GRAPH_RELAY_FILE` 记录绝对路径，缺省空覆盖保持旧行为。Admin/projector的 `graph.http` 继续使用127.0.0.1:18544，不放宽远端明文HTTP边界。

Graph重建或网络地址改变后，必须重新核对该容器ID/镜像/端口并更新这两份连接绑定，再正常更新relay/server和console/admin/projector；不能使用历史IP作为新权威来源。原Graph、IdP及数据未重建。验证日志证明原宿主转发3秒超时、直接本体成功；保留失败和旧配置，当前管理/连续投影已healthy。

中央overlay的console依赖使用[Compose restart语义](https://docs.docker.com/reference/compose-file/services/#depends_on)，显式更新后端时重新解析服务IP。已更换IP但控制台未更新时，需要正常restartconsole并复核实际反代；仅healthz成功不能证明上游连通。

初轮 W07 发布后的持续观察补充发现稳定 READY 轮次未清连续失败预算，已有分区可能累计间歇故障后阻塞。局部 Auth Java/Mapper 修复、真实 PG/Graph 回归及治理制品更新另见 [投影恢复修复](PROJECTION_RECOVERY_FIX.md)；WMS 源/镜像和固定 SDK 不变。新源码镜像与当前运行终态以私密交付回执为准；现有 BLOCKED 只能在核对远端事实后由真实管理者执行有审计的 retry-strict，不能直接改状态或自动绕过保护。
