# 本地授权管理 Docker Runtime

本配置将已有 auth-console、auth-platform-admin 和 ReliableProjectionCli 接入原 `deploy/docker-compose.yml` 的 `governance` profile。入口为 `http://localhost:5273/governance`，提供组织/应用选择、角色版本、成员授权、范围、投影状态和审计。使用真实数据库和认证，不在页面内写入示例数据。

## 依赖与数据边界

- Docker Desktop（BuildKit + Compose v2）；首次初始化 CLI 另需 Java 21 与项目 Maven Wrapper。日常镜像构建只需 Docker，后端使用镜像内 Java 21 和仓库 Wrapper。
- 既有 dev_infra PostgreSQL 16：`dev-infra-postgres16-1`，宿主端口 45432。新建固定库/角色 `auth_governance`，与历史测试库分离，默认不删除。
- 既有兼容治理验证的 Casdoor 4.11：localhost:18090。8000 的旧 Casdoor 保留给原环境。
- 既有 P3 治理 SpiceDB：localhost:18544；使用全新 tenant UUID 分区，不覆盖已有图数据。读池 min/max=1/4，写池 min/max=1/2，避免默认 30 个常驻连接耗尽共享 PostgreSQL。`governance-graph-isolation.py` 新建本地阶段图时同样采用此上限；已有实例需保留镜像、环境、端口、数据源再重建，不能只重启期待配置改变。
- 私密身份夹具目录必须包含 `p2/identity/casdoor.json`、`casdoor-isolated/management-client.json`、`p3/graph/graph.properties`。这些文件不入 Git，不进入镜像。
- 18090/18544 是此入口的有效依赖，清理测试容器时须排除。本机接管后分别由 `auth-governance-casdoor`、`auth-governance-graph` 提供，均属于 `auth-platform` Compose 项目并设 `unless-stopped`；没有接管的环境仍可沿用原外部实例。

固定库含一个 `local-commerce` 组织、两个已有身份的成员映射、commerce 应用、local 环境以及门店读取/管理、商品读取三项能力。首次管理员由受控 CLI 显式委派；不会按首次登录或 isAdmin 自动提权。示例外部身份仅在本地示例中作为 EMPLOYEE 成员导入。

**此分区用于验证授权管理页面。现有 localhost:8602 电商镜像尚未连接此分区，给示例成员授权不代表旧电商业务已经接管统一鉴权。审批页面可读取配置，但本 profile 没有运行 OA 审批消息分发器；提交审批不等于完成审批闭环。**

## 首次准备与启动

在项目根目录构建后端，然后指定已有私密身份目录及固定输出目录：

```bash
./mvnw -q -pl auth-platform-admin -am package -DskipTests -Dmaven.jar.forceCreation=true
python3 deploy/governance/provision-local.py \
  --identity-directory "$PWD/.local/governance" \
  --output-directory "$PWD/.local/docker-governance"
bash deploy/governance/run.sh build
bash deploy/governance/run.sh up
```

`run.sh up` 先等待管理后端就绪，再通过真实授权码 + PKCE 登录调用幂等的 enable-strict 接口，最后启动投影进程，避免新分区尚未启用时反复失败。

更新到当前仓库源码时执行：

```bash
bash deploy/governance/run.sh config  # 静默校验，不输出私密配置
bash deploy/governance/run.sh update  # 构建当前源码，成功后更新三个治理容器
bash deploy/governance/run.sh status
```

`build` 可以在私密 env 尚未生成时独立执行，不启动服务。admin 镜像从仓库根 reactor 多阶段构建，自动包含当前 protocol/core/governance 依赖；不会复制宿主 `target/` 的历史 JAR。`up` 使用已构建镜像，`restart` 也只重建容器，不重新编译；要更新代码使用 `update`。入口端口从 `deploy/platform-ports.env` 加载；目前初始化客户端与激活流程按固定本地 5273 回调配置，修改该端口还需同步客户端/初始化流程，不能只改 env。私密挂载目录缺失会直接报错，不会自动创建空目录。

初始化入口只创建不存在的固定数据库/角色和专用 OIDC 客户端；重复执行复用固定命令 ID、主体、组织和私密状态，不清空数据、不重置用户密码、不扩大委派。迁移由 `GovernanceCli bootstrap` 显式执行；运行中的 HTTP/投影进程只验证迁移。输出 `ACCESS.md` 提供入口、已有账号和示例成员，文件权限 0600，目录 0700。

隔离工作树部署时，把 `GOVERNANCE_ENV_FILE` 指向原仓库的 `.local/docker-governance/runtime.env`。保留该私密目录，不能以重新生成随机凭据的方式恢复已存在的库。可先用 `pg_dump` 备份 `auth_governance` 再升级；代码回退必须检查数据库迁移兼容性。

```bash
GOVERNANCE_ENV_FILE=/absolute/path/runtime.env bash deploy/governance/run.sh status
GOVERNANCE_ENV_FILE=/absolute/path/runtime.env bash deploy/governance/run.sh restart
GOVERNANCE_ENV_FILE=/absolute/path/runtime.env bash deploy/governance/run.sh stop
```

`restart` 一起重建三个治理应用容器；已接管的认证/图服务只调和运行状态，不强制重建。`stop` 停止三个应用及已接管的两项依赖，均不删除数据卷、不操作 OA 或原 8000/8543 Auth 基建。

## 已有依赖纳入同一 Compose 组

Docker Desktop 按 Compose 项目标签分组。阶段脚本创建的独立容器没有这些标签，不能靠改名归组。`dependencies.compose.yml` 把已有独立实例纳入 `auth-platform`：认证仍用 18090、原只读配置卷和原 PostgreSQL 数据库；授权图仍用 18544、原连接串/密钥和原池上限。共享 PostgreSQL 继续由 dev_infra 管理。

接管前先保存私密容器配置、配置卷内容并备份所连接的数据库。下面的准备命令只读取 Docker 并导出 0600 私密文件，不启动、停止或重建容器，也不生成新账号：

```bash
python3 deploy/governance/prepare-dependencies.py \
  --casdoor-container <现有认证容器> \
  --graph-container <现有授权图容器> \
  --output-directory "$PWD/.local/docker-governance"
bash deploy/governance/run.sh config
```

`run.sh` 检测到与 runtime.env 同目录的 `dependencies.env` 后加载依赖 Compose 文件；可用 `GOVERNANCE_DEPENDENCIES_ENV_FILE` 显式指定路径。未提供此文件时维持外部依赖模式，避免在其它环境自动创建重复实例。私密配置中的镜像为原容器不可变 digest；配置卷和数据库网络声明为 external，缺失即拒绝启动，不创建空卷。

接管配置需要支持 env_file.format: raw 的 Compose；本机验证版本为5.5.0。`up`/`update`/`restart` 在调和依赖后实际读取原登录 issuer 和图 schema，最多等待45秒，再启动应用，避免把冷启动中的 running 当成依赖已可用。

首次切换需在维护窗口停止旧两个实例并保存为停机回退容器，然后只启动 `governance-casdoor governance-graph`。端口互斥，不能让旧、新实例同时运行。验证 Compose 标签、镜像/数据源一致、真实 PKCE 登录、schema 读取、管理接口和两类投影 READY 后完成接管。回退先停止新两个实例，再启动旧实例；不还原或删除数据库。原始检查点、停机回退容器和数据库备份保留，清理另行处理。接管结果见 [分组迁移报告](../../docs/deployment/governance-compose-group.md)。

## 网络、健康与权限生效

三个治理容器共享 console 网络命名空间。宿主仅公开 127.0.0.1:5273；admin 仅监听共享回环 8201。nginx 的回环 18090/18544 转发到 Docker Desktop 宿主依赖，保留严格的 `http://localhost:18090` issuer 和治理代码仅允许回环 HTTP 的边界。没有将身份校验放宽到任意 HTTP 地址。此方式是本地兼容配置，不是生产网络方案。

UI `/healthz` 验证 nginx；admin `/actuator/health` 验证进程，真实登录和管理接口是端到端验证；projector 只有允许列表中 POLICY/DIRECTORY 都 READY 才更新健康时间戳。每批 CLI 自带 30 秒预算，异常由 Docker 重启，每轮间隔 10 秒，不扫描未知组织。授权显示“待生效”期间不能当作授权成功。

镜像使用已有 Node 22、nginx 1.27、Java 21 基线并固定 digest；pnpm 9.15.9 与仓库 CI 一致。admin 构建上下文通过根 `.dockerignore` 只允许 reactor 源码、POM 与 Wrapper，最终镜像只含可执行 JAR 和 JRE；前端排除本机 node_modules、dist 和私密 env。数据库密码、图密钥和 OAuth secret 只通过只读 0600 配置挂载。HTML 使用 `no-cache` 重校验，带摘要的 assets 保留长期缓存。

直接构建 admin 必须以仓库根目录为上下文：`docker build -f auth-platform-admin/Dockerfile -t auth-platform/governance-admin:local .`。`GOVERNANCE_ADMIN_IMAGE` 同时作用于 admin/projector，`GOVERNANCE_CONSOLE_IMAGE` 指定控制台标签；两者默认保持原 `:local`。验证时可指定独立标签，不影响现有镜像和容器。原模块内 `.dockerignore` 属于历史单模块 JAR 构建，根上下文使用根 `.dockerignore`。
