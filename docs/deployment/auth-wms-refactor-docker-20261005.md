# Auth 与 WMS 本机 Docker 部署结果（2026-10-05）

本次将已通过 CI 的模块重构版本部署到既有 Docker Desktop 的 `auth-platform` 与 `wms-local` 项目。最终 **11 个应用容器 healthy、重启计数为 0**；12 项后端权限检查、5 项 Auth 浏览器检查和 6 项 WMS 浏览器检查全部通过。业务数据、授权、凭据与持久卷保留。未部署生产。

## 源码、制品与门禁

实际制品源码为 Auth `e4d14eb1b764f53419bbda9ee42920d9b0d3920b`、WMS `562f90f933edd7e5144aed058179294cab95d892`。从各自 `git archive` 创建构建上下文，在容器内构建；不带入宿主历史 JAR 或私密文件。OCI revision、运行镜像 ID、JAR 中的源码类及 SQL/XML 资源均已核对。

| 精确源码门禁 | 结果 |
|---|---|
| [Auth CI 37276514965](https://github.com/lirji/auth-platform/actions/runs/37276514965) | completed / success，源码 e4d14eb |
| [Portal CI 37276514926](https://github.com/lirji/auth-platform/actions/runs/37276514926) | completed / success，源码 e4d14eb |
| [WMS verify 37278890345](https://github.com/lirji/wms-platform/actions/runs/37278890345) | completed / success，源码 562f90f |

本次后续 Git 提交只交付部署文档，不是运行镜像的源码 revision。部署门禁引用上述精确制品源码 CI；文档提交触发的 CI 状态另存交付回执，不混称为镜像验收。

以下为本机镜像 ID，不是远程 registry 的 manifest digest：

| 镜像标签 | 不可变镜像 ID |
|---|---|
| auth-platform/auth-admin:rev-e4d14eb1b764 | `sha256:33aa51568075b96a4d084322c335010643f335a74eb5c12db38c61500a8deec0` |
| auth-platform/auth-server:rev-e4d14eb1b764 | `sha256:a34c5828b0866e47000288ad1a0acf8af819778f5319a6190d9af8445a942e6e` |
| auth-platform/auth-console:rev-e4d14eb1b764 | `sha256:6d085f699d8b3c3343bf6595f3bbae209ea0c2cbbf90d30a11cb7a5220385d80` |
| auth-platform/auth-portal:rev-e4d14eb1b764 | `sha256:231cd7c5b0578cf32697f0a0d14d1f3c2480b3a823a485b3093b227483846df3` |
| wms-platform/wms-java:rev-562f90f933ed | `sha256:a8d0cffbabbf282ca2ab010cd4a901edbc6c054f3454fb0d9e8bce6a68394188` |
| wms-platform/wms-console:rev-562f90f933ed | `sha256:5a2b724ac644895d55f0bb7ef3ed19c3c12008e6217d6a67b074e7a5dbd0981e` |

WMS 继续使用源码 Dockerfile 锁定并校验的 Auth SDK revision `7712d2606805afb3b9a7de7a6d88fe94a28104fb`；没有借部署升级 SDK 或修改公开契约。

## 实际入口与服务

| 入口 / 连接 ID | 当前地址与服务 |
|---|---|
| local/auth/management | [Auth 管理台](http://localhost:5273/governance)；auth-governance-console、auth-governance-admin、auth-governance-projector |
| local/auth/portal | [项目门户](http://localhost:5274)；auth-project-portal |
| local/wms/console | [WMS 控制台](http://127.0.0.1:18180)；wms-local-console-1 |
| local/wms/applications | wms-local-inbound-1 / outbound-1 / inventory-1 / serial-registry-1 / fulfillment-1；18181–18185 |
| local/wms/authorization | auth-governance-wms-server；SDK 使用可信 HTTPS 18545，原 relay 保留 |
| local/wms/identity | 原 Casdoor 4.11，issuer http://localhost:18090，公开 PKCE 客户端 wms-central |
| local/wms/machine-identity | 原内部机器链 issuer 8000 / audience wms-platform；与人类中央登录分开 |

Auth 的五个应用和 WMS 的六个应用均已切换到表中镜像。WMS 中央组织仍为 `local-wms → ENT-DEMO`。PostgreSQL 16、两套既有 Casdoor、治理 SpiceDB 1.56.2、WMS MySQL 8.4.11、Kafka 3.8、Redis 7 与 Seata 2.6 沿用原实例、镜像和卷；120 个非目标容器未被本次替换。未启用额外 Cell、XXL-JOB 或自动执行 worker。

## 本机资源预算与维护

共享 VM 初期可用内存不足 600 MiB，2 GiB swap 几乎耗尽；实际发生登录、投影及简单 SQL 等依赖停顿。构建时仅暂时停止本任务的八个后端，使用 512 MiB Maven 堆顺序构建，完成后全部恢复。未停止数据库、授权引擎或其他项目。

最终八个后端统一使用 `-Xms32m -Xmx128m -XX:ActiveProcessorCount=2` 和 `MALLOC_ARENA_MAX=2`，不设置额外 CPU quota。后者约束 glibc 原生内存分配 arena 数量，见 [glibc 官方说明](https://www.gnu.org/software/libc/manual/2.35/html_node/The-GNU-Allocator.html)。实际最终权限和浏览器验收通过；此预算不是生产容量证明。

仅私密运行配置改变，源码 Compose、投影脚本和业务代码未改。必须保留：

- Auth `.local/wms-auth-integration/projection-ready-repair/compose.runtime-budget.yml`：管理、投影与判权 JVM 和原生分配预算。projector 在容器内生成临时脚本替换 JVM 参数，原只读挂载脚本保持原字节。
- Auth `.local/wms-auth-integration/projection-ready-repair/compose.wms-native-budget.yml`：五个 WMS 服务的原生分配预算。
- 治理 `.local/docker-governance/runtime.env`、判权 `.local/wms-auth-integration/docker/auth-runtime.env`、WMS `.local/wms-auth-integration/docker/wms-runtime.env` 与门户 `.local/docker-governance/portal-runtime.env` 中的不可变镜像绑定；WMS env 同时保存最终 JVM 参数。

私密目录、证据及账号手册不进入 Git；公开文档只记录路径引用。以下用于本机已初始化实例的维护，变量必须指向**实际部署 checkout**。WMS 使用既有 central-authorization 工作树，不切换原有 dirty main 目录。先确认 Docker context 为目标 desktop-linux；不要把这些命令用于生产。

```bash
AUTH_PROJECT_DIR=/absolute/path/auth-platform
WMS_PROJECT_DIR=/absolute/path/existing/wms-central-authorization
AUTH_BUDGET="$AUTH_PROJECT_DIR/.local/wms-auth-integration/projection-ready-repair/compose.runtime-budget.yml"
WMS_BUDGET="$AUTH_PROJECT_DIR/.local/wms-auth-integration/projection-ready-repair/compose.wms-native-budget.yml"

# 三个治理应用共享 console 网络命名空间，重建 console 时一起调和 admin/projector。
docker compose \
  --env-file "$AUTH_PROJECT_DIR/.local/docker-governance/runtime.env" \
  --env-file "$AUTH_PROJECT_DIR/deploy/platform-ports.env" \
  --env-file "$AUTH_PROJECT_DIR/.local/docker-governance/dependencies.env" \
  -p auth-platform -f "$AUTH_PROJECT_DIR/deploy/docker-compose.yml" \
  -f "$AUTH_PROJECT_DIR/deploy/governance/dependencies.compose.yml" \
  -f "$AUTH_BUDGET" --profile governance \
  up -d --no-deps --no-build --pull never --wait --wait-timeout 240 \
  auth-console governance-admin governance-projector

docker compose \
  --env-file "$AUTH_PROJECT_DIR/.local/wms-auth-integration/docker/auth-runtime.env" \
  -p auth-platform -f "$AUTH_PROJECT_DIR/deploy/docker-compose.yml" \
  -f "$AUTH_BUDGET" --profile wms-auth \
  up -d --no-deps --no-build --pull never --wait --wait-timeout 240 governance-wms-server

docker compose \
  --env-file "$AUTH_PROJECT_DIR/.local/wms-auth-integration/docker/wms-runtime.env" \
  -p wms-local -f "$WMS_PROJECT_DIR/compose.yaml" \
  -f "$WMS_PROJECT_DIR/deploy/compose.console-release.yml" \
  -f "$WMS_PROJECT_DIR/deploy/compose.central-auth.yml" -f "$WMS_BUDGET" \
  up -d --no-deps --no-build --pull never --wait --wait-timeout 240 \
  inbound outbound inventory serial-registry fulfillment console

# 如果上一步替换了后端容器，刷新 console 的 nginx DNS。
docker compose \
  --env-file "$AUTH_PROJECT_DIR/.local/wms-auth-integration/docker/wms-runtime.env" \
  -p wms-local -f "$WMS_PROJECT_DIR/compose.yaml" \
  -f "$WMS_PROJECT_DIR/deploy/compose.console-release.yml" \
  -f "$WMS_PROJECT_DIR/deploy/compose.central-auth.yml" -f "$WMS_BUDGET" \
  up -d --no-deps --no-build --pull never --force-recreate --wait --wait-timeout 240 console

docker compose \
  --env-file "$AUTH_PROJECT_DIR/.local/docker-governance/portal-runtime.env" \
  --env-file "$AUTH_PROJECT_DIR/deploy/platform-ports.env" \
  -p auth-platform -f "$AUTH_PROJECT_DIR/deploy/docker-compose.yml" \
  up -d --no-deps --no-build --pull never --wait --wait-timeout 240 project-portal
```

这些命令依赖原 relay、数据库和挂载已存在且健康，不执行初始化、provision、续发 Token 或更改 Grant。通用 `run.sh update/restart` 与只带三份 WMS Compose 的历史命令不会保留上述全部私密 overlay；本实例维护以这里的配置组合为准。下一次更新源码先核对新的精确 CI 和制品，不能覆盖当前已验收的 rev 标签。

## 验收与数据保护

| 核验 | 最终结果 |
|---|---|
| 运行时 | 11 容器健康、精确镜像/OCI/JAR 资源吻合、挂载不变；五个 WMS 后端非 root、各自独立 0600 凭据；可信 TLS 通过 |
| 后端权限 12 项 | 五个服务均中央模式、ENT-DEMO 与原能力集；四项真实业务读取成功；匿名 401、跨仓 403、无 Grant 403 |
| Auth 浏览器 5 项 | 真实 PKCE，管理/个人导航、成员与角色/授权读取；44 个电商菜单含 42 个中文标题及实际成员路由 |
| WMS 浏览器 6 项 | A 仓运营、B 仓读取及企业 SKU 写入独立边界、B 仓只读、跨仓深链拒绝、390px PDA 禁用提交、无授权无菜单 |
| 页面真实性 | WMS 0 个 5xx、0 页面错误；无 Mock API、无 Token 注入；管理与业务写入均为 0 |
| WMS 数据 | 142 表、191 行逐表摘要相同；数据库镜像、容器及持久卷保持 |
| Auth 数据 | 65 表中 64 表字节摘要相同；projection_stream 四行仅租约四字段与 next_attempt_at 运行调度时间变化 |
| 授权投影 | 四分区仍 READY、失败 0；desired/applied epoch、marker 及远端图 marker 不变；成员/角色/Grant/目录/企业绑定未改变 |
| 用户工作 | 原 WMS main 2efa151 与 55 个已有文件保持；11 工作树全部保留，旧 Driver/试点与忽略资料未清理 |

初期备份超时、投影重启、浏览器依赖超时及 0.5 CPU / 4 秒身份 deadline 两次无效实验均保留证据。CPU 限流和身份 deadline 实验已完全撤回，原身份连接/读取 2 秒、SDK 总预算与凭据恢复原值。此前“CLI 入口改名导致旧 projector 重启”的推断已排除，公共 CLI 和挂载脚本没有发生该变更；重启的单一根因未独立证明。最终通过来自实际资源预算调整后重新执行的验收，没有修改权限规则或放宽失败断言。

## 恢复、期限与证据

更新前的旧镜像、容器配置、私密 env、数据库备份和逐表摘要均保留。私密证据目录 `.local/docker-auth-wms-refactor-20261005` 权限 0700，包含 `DEPLOYMENT_RESULT.json`、`source-ci-gate.json`、`candidate-images.json`、`runtime-result.json`、`data-result.json`、`current-sdk-result.json`、两套浏览器结果/截图及部署命令回执。Git 发布的实际 SHA、main 包含关系和文档 CI 观察另见 `DELIVERY_RESULT.json`。

`rollback.py` 已准备原镜像/私密配置恢复路径，本次 **NOT_NEEDED，未执行完整回滚演练**。镜像回退与业务数据恢复、已提交效果补偿分别处理；不要清卷、恢复旧 SQL 或清空图来模拟代码回退。旧源码与新库兼容性仍需按具体回退目标核对。

原机器 JWT 已于 2026-10-05 04:35:46 UTC 到期，本次没有续发；现有演示 Grant 的到期时间为 2026-10-06 01:17:59 UTC，没有延长。最终人类验收使用正常真实 PKCE 登录产生的新会话。机器内部写入、对账、TCC 业务效果、现场设备、正式容量及生产没有在本次重新验收；不能由 healthy 推断过期机器权限可用。共享 VM 仍资源紧张，后续数据量或并发增长需要重新验证。
