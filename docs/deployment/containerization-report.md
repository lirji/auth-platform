# Docker 部署同步报告

- 任务：DOCKER-SYNC，2026-10-01；基线 `9bc2c4f`。
- 协议：`engineering-baseline/v1`、`skill-contract/v1`、`containerization-report/v1`。
- 模式：PATCH_EXISTING；目标：本地；当前状态：READY_WITH_WARNINGS，DOCKER-SYNC 本地验证 DONE。
- 范围：当前仓库 Docker 构建、Compose、治理启动脚本与说明。用户已批准修改及构建验证；保持现有运行容器与数据库。

## 写入前的部署模型

### DeploymentTopology（deployment-topology/v1）

| 服务/模块 | 分类 | 依赖和证据 |
| --- | --- | --- |
| project-portal | MANAGED_BY_DEPLOYMENT，默认应用 | `project-portal/package.json`、`nginx.conf`；80，宿主入口由 `platform-ports.env` 管理 |
| auth-console | MANAGED_BY_DEPLOYMENT，governance 应用 | `auth-console/package.json`；nginx 8202，OIDC 18090；`deploy/governance/nginx.conf` |
| governance-admin | MANAGED_BY_DEPLOYMENT，governance 应用 | `auth-platform-admin/pom.xml`、`application.yml`；Java 21、8201；只读私密配置 |
| governance-projector | MANAGED_BY_DEPLOYMENT，governance 作业 | `ReliableProjectionCli`、`deploy/governance/projector.sh`；复用 admin 镜像，无独立公开端口 |
| authz-postgres | MANAGED_BY_DEPLOYMENT，既有基础设施 | 原 Compose PostgreSQL 16、既有 `authz-pg-data`；不新增数据库实例 |
| spicedb-migrate / spicedb | MANAGED_BY_DEPLOYMENT，迁移作业/基础设施 | 原 Compose；迁移完成后 serve；本机既有镜像版本 v1.56.2 |
| casdoor | MANAGED_BY_DEPLOYMENT，既有身份服务 | 原 Compose 与 `deploy/casdoor/app.conf`；保持既有镜像摘要及数据库 |
| dev_infra PG / 治理 Casdoor / 治理 SpiceDB | EXTERNAL | `deploy/governance/provision-local.py`、`README.md`；45432 / 18090 / 18544；不由本次部署管理 |
| auth-platform-server | EXTERNAL，既有宿主进程 | `application.yml`、`dev.sh`；8200；保持原部署方式 |
| protocol / core / sdk / governance | LIBRARY / EMBEDDED | Maven reactor；参与构建，不创建独立 Compose 服务 |
| P4 workflow / Kafka / PG | OPTIONAL，独立验证环境 | `deploy/governance-p4-compose.yml`；原隔离边界保持 |

### EnvironmentProfile（environment-profile/v1）

`local-default`：默认启动原基础设施与门户；治理通过显式 profile 启用。治理依赖复用 dev_infra 与现有身份/图服务。公共入口由中央端口注册表决定；共享回环网络保留现有 issuer 校验。机密仅来自本地 env / 只读 runtime 配置，不进入源码构建上下文。

### ResolvedDeployment（resolved-deployment/v1）

`docker-sync-local`：保持上述服务、端口、卷和外部依赖；admin 从根 reactor 源码构建，projector 使用同一构建定义/镜像；门户沿用控制台的 Node 22、nginx 1.27 摘要和 pnpm 9.15.9。SpiceDB migrate / serve 使用相同固定摘要，Casdoor 固定既有摘要。新增 image 覆盖变量只用于指定镜像标签，不改变运行拓扑。数据库连接使用容器服务名 `authz-postgres`；治理 PG 使用已有 `host.docker.internal`，身份/图仍经 nginx 回环代理，不替换其 issuer。

自动 Discovery 已以 analyze/read-only 执行；最终分类按源码及现有启动脚本校正，投影 CLI 是应用作业而非中间件。未重新选型或生成其他运行实例。

## ChangeSet / Artifact

| 文件 | 操作 | 原因/证据 |
| --- | --- | --- |
| `.dockerignore` | CREATE | 根源码上下文允许列表；排除历史 JAR、私密配置和其他项目资源 |
| `auth-platform-admin/Dockerfile` | PATCH | 原先复制宿主旧 JAR；改为 Java 21 + Wrapper reactor 构建，强制重打包依赖 |
| `project-portal/Dockerfile`、`.dockerignore` | PATCH | 对齐已验证控制台镜像摘要/锁文件包管理器，排除 env |
| `auth-console/nginx.conf`、`deploy/governance/nginx.conf` | PATCH | SPA HTML 重校验，防止浏览器继续使用旧入口；统一较大身份请求头 |
| `deploy/docker-compose.yml`、`.env.example` | PATCH | 固定原镜像、对齐 Casdoor PG 配置、复用 admin 构建、挂载缺失时失败 |
| `deploy/postgres-init/10-authz-admin-db.sql` | PATCH | 初次启动授权角色跟随 PostgreSQL 当前初始化用户，支持 PG_USER 覆盖 |
| `deploy/governance/run.sh`、`README.md` | PATCH | 源码构建不依赖预备私密 env；显式 update，保留 restart 已构建镜像语义 |
| `README.md` | PATCH | 同步安全启动/停止与更新步骤 |
| `.github/workflows/portal-ci.yml` | PATCH | 相关部署文件变更触发 Compose 和治理镜像构建，验证不依赖私密配置 |

所有 PATCH 可通过普通 Git revert 回退；未变更数据库迁移或业务数据。无 DELETE。

## Validation

| 检查 | 当前结果 |
| --- | --- |
| L1 Compose 默认/governance/P4、shell 语法 | PASS；使用示例 env、私密 env、无私密 env 路径检查；控制台/门户端口与注册表一致 |
| L2 admin / console / portal 镜像构建 | PASS；`docker-sync-check` 独立标签；admin 由干净镜像内 Wrapper 构建 |
| L3/L4 隔离镜像启动/入口检查 | PASS（有界范围）；三种 nginx 配置/health/SPA，admin JRE 启动和 actuator UP；不重建既有环境 |
| PostgreSQL 初始化 + Casdoor 配置覆盖 | PASS；临时 tmpfs PG、自定义用户；真实 Casdoor 连接该库并从 env 读取 OIDC origin |
| 更新脚本与共享网络切换 | PASS；6 项命令编排/失败行为；临时共享网络验证 admin/projector 都引用替换后的 console |
| 源码/JAR依赖与构建上下文检查 | PASS；镜像与本次宿主强制构建的 protocol 100、core 10、governance 214、admin 67 项 class/resource 逐字节一致，共 391 项 |
| 后端与门户回归 | PASS；admin reactor 186 项测试、门户 29 项测试，均无失败/跳过 |
| 完整治理登录/授权/投影闭环 | UNVERIFIED；未连接或重建既有治理环境；不以临时 health 代替业务验收 |
| 远程 CI | 提交前 UNVERIFIED；新增默认/governance config 与两个治理镜像构建检查，提交后按精确 revision 查询 |

所有临时运行容器均已停止并自动移除，使用隔离网络或 none，不发布宿主端口。未删除共享卷、数据库或既有容器。证据保留于忽略的 `.local/docker-deployment-sync/`：镜像构建日志、Maven/门户测试、`jar-verification.json`、`smoke-result.json`、`namespace-check.log`、脚本编排记录。临时构建镜像保留为 `docker-sync-check`，原 `:local` 标签和运行实例未替换。Node/JDK/SpiceDB/Casdoor 固定摘要的 amd64/arm64 manifest 已核对；本机实际构建/启动为 arm64，amd64 完整启动未验证。

## 风险、假设与人工操作

- `IMAGE_VERSION_UNKNOWN`：固定 Casdoor 历史镜像摘要，不宣称它是治理依赖的 v4.11.0。治理仍使用原外部 v4.11.0。
- `EXTERNAL_SERVICE_UNAVAILABLE`：governance 需要原 45432/18090/18544 及私密配置；本次不创建或修复外部依赖。
- 需要显式执行 `run.sh update` 才更新既有治理容器。纯构建不会变更其运行版本。
- 保持原本地开发默认凭据兼容；新 `.env.example` 仅提供 `CHANGE_ME`，既有卷的凭据不能通过改 env 自动轮换。
- L3/L4 对完整认证与数据库链路如未执行，标 UNVERIFIED；不以静态页健康代替授权验收。
- 本任务开始检查时，既有 `auth-governance-projector` 已显示 unhealthy（原容器已运行 34 小时）。本次保留原环境，此历史运行状态未纳入文件更新的修复范围。
- 假设：用户所说“最新”是部署文件与当前仓库源码一致，不是升级全部第三方依赖。已经用户确认。
- 下游 Handoff：无生产部署；需要独立执行授权方可切换实际环境。

## 官方依据

- [Docker 构建上下文](https://docs.docker.com/build/concepts/context/) 与 [多阶段构建](https://docs.docker.com/build/building/multi-stage/)：构建输入来自指定上下文，最终阶段仅复制运行产物。
- [Casdoor Docker 配置](https://casdoor.ai/docs/basic/try-with-docker/)：使用 `driverName` / `dataSourceName` 环境变量覆盖连接配置。
- [PostgreSQL 16 GRANT](https://www.postgresql.org/docs/16/sql-grant.html)：数据库权限的接收方支持 `CURRENT_USER`。
