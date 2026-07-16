# 数据库与存储

平台**无应用层 ORM/JPA**——判权服务本身无状态，所有授权态都以**关系元组（relationship tuples）**的形式
存在 SpiceDB 中。真正落到磁盘的只有一个关系库：**PostgreSQL**，它同时充当 SpiceDB 和 Casdoor 的后端存储
（外加一个可选的 admin 审计库 `authz_admin`，见下）。

## 一、PostgreSQL 16 —— 唯一的关系型数据库

| 项 | 值 |
|---|---|
| 镜像 | `postgres:16` |
| 容器名 | `authz-postgres` |
| 端口 | 宿主 `15432` → 容器 `5432` |
| 账号 | `authz` / `authz_dev_pw`（`.env` 覆盖，变量 `PG_USER` / `PG_PASSWORD`） |
| 初始库 | `POSTGRES_DB=spicedb` |
| 数据卷 | `authz-pg-data` |
| healthcheck | `pg_isready -U authz -d spicedb` |

一个 Postgres 实例内有**两个逻辑库**，被两个中间件分别使用：

| 逻辑库 | 使用方 | 如何创建 |
|---|---|---|
| `spicedb` | SpiceDB datastore | compose 初始化时由 `POSTGRES_DB` 建好 |
| `casdoor` | Casdoor 数据源 | Casdoor 连 `spicedb` 库引导后**自建并使用** `dbName=casdoor`（见 `deploy/casdoor/app.conf`） |
| `authz_admin` | admin 审计持久化（`authz.audit.persistence-enabled=true` 时；默认关=内存审计） | 新卷由 `deploy/postgres-init/` 初始化脚本建；存量卷手动 `docker exec authz-postgres createdb -U authz authz_admin`。表 `authz_audit` 由 admin 启动时幂等自建 |

**迁移**：SpiceDB 的 schema 迁移由 compose 中一次性服务 `spicedb-migrate` 执行
`datastore migrate head` 完成（`serve` 前必须先跑）。**无 Flyway / 无 JPA / 无 Liquibase**。

## 二、SpiceDB —— 授权“数据库”（Zanzibar / ReBAC）

SpiceDB 不是传统关系库，而是 **Google Zanzibar 风格的关系式授权引擎 + 关系元组存储**，磁盘落在上面的
PostgreSQL（`spicedb` 库）。它是本平台的授权真相源。

| 项 | 值 |
|---|---|
| 镜像 | `authzed/spicedb:latest` |
| 容器名 | `authz-spicedb`（迁移用 `authz-spicedb-migrate`） |
| datastore | `postgres`，连 `authz-postgres:5432/spicedb` |
| gRPC 端口 | 宿主 `50051` → 容器 `50051`（**平台不使用**，见下） |
| HTTP 端口 | 宿主 `8543` → 容器 `8443`（`SPICEDB_HTTP_ENABLED=true`） |
| metrics | 宿主 `9099` → 容器 `9090`（Prometheus） |
| 鉴权 | 预共享 Bearer key `authz_dev_key`（变量 `SPICEDB_KEY`） |
| 接入方式 | Java 侧仅走 **HTTP/JSON API**（Spring `RestClient`），见 `SpiceDbAuthzEngine` |

> **grpc-free 关键约束**：整套平台刻意避开 grpc/protobuf，全走 SpiceDB 的 HTTP/JSON 网关，以规避与
> langchain4j 根 pom 的钻石依赖冲突。`AuthzEngine` 端口保留，是为将来可另加 gRPC 适配器而不动上层。

### 一致性水位（ZedToken）

这里的 “token” 是 SpiceDB 的**一致性水位**，不是会话令牌。三档（见 `protocol/Consistency.java`）：

| 档位 | 用途 |
|---|---|
| `MINIMIZE_LATENCY` | 默认判权，最快 |
| `AT_LEAST_AS_FRESH` | 写后读 / 撤权立即生效——带上写操作返回的 ZedToken |
| `FULLY_CONSISTENT` | 管理/调试；admin 读一律用它 |

> **坑**：刚写入就读，必须带 ZedToken 或用 full，否则可能被量化快照漏读。
> server 有 **ZedToken 水位缓存**（`ZedTokenWatermark`，默认开）：`at_least_as_fresh` 无 token 的请求
> 自动代入本实例最近写水位，无水位回退 full。单实例内存态——多实例部署跨实例写后读仍须调用方自带 token。

## 三、数据/授权模型（`.zed` schema）

领域模型不是 SQL 表，而是 SpiceDB 的 `.zed` schema，位于
`auth-platform-core/src/main/resources/schemas/`。两份 schema **合并为一份加载**（`his.zed` 复用
`knowledge.zed` 里的 `user` 定义，不重复定义）。对象 id 约定带租户前缀 `<tenantId>_<docId>` 消歧。

### knowledge.zed —— 知识库授权模型

| 定义 | 含义 | 关键权限（并集 + 层级箭头） |
|---|---|---|
| `user` | 用户主体 | — |
| `group` | 组（可嵌套：`member: user \| group#member`） | `membership = member` |
| `organization` | 租户 / 公司 | `administrate = admin` |
| `space` | 知识库（集合） | `manage/edit/comment/view`，含 `parent_org->administrate` |
| `folder` | 文件夹（可嵌套） | `edit/view`，**向下继承** `parent_folder->` 与 `parent_space->` |
| `department` | 部门（Casdoor 嵌套 group 同步，一人一部门） | `doc_reader = member + parent->doc_reader`、`doc_admin = admin + parent->doc_admin`（**沿部门树向上传播**：祖先部门能看后代文档） |
| `document` | 单篇文档（**部门层级模型**，取代旧 D3） | `view = owner + viewer + public + home_dept->doc_reader`、`share = owner + home_dept->member + home_dept->doc_admin`、`edit = owner` |

特性：
- **部门层级隔离**：文档归属上传人部门（`home_dept`），本部门 + 所有上级部门自动可读，平级/下级/其他读不到；跨部门经单篇 `viewer` 分享。方向与 `space/folder` 的向下继承**相反**。完整规则见 [`../docs/authz-department-model.md`](../docs/authz-department-model.md)。
- **公开文档 / 公开链接**：`document.public: user:*`（部门模型的公共文档，进 `view`）；`space.public_viewer: user:*`（公开链接）。`document` 上的旧 `public_viewer/commenter/editor/parent_space/parent_folder` 仅作兼容/回滚保留，**已不进 `view/edit`**。
- **RBAC 作为特例**：把角色对应的 `group` 绑到 `space` 的对应 relation。

### his.zed —— HIS 数据权限模型（演示）

| 定义 | 含义 | 关键权限 |
|---|---|---|
| `dept` | 科室 | `access = member + head`（本科室成员或主任） |
| `patient` | 患者 | `care = attending`（主治医生，可跨科） |
| `encounter` | 就诊 / 病历记录 | `view = author + dept->access + subject->care`；`edit = author + dept->head` |

场景：只能看本科室 + 科主任看全科 + 主治医生跨科看自己患者 + 记录作者可见可改。

## 四、Redis —— 引用但未启用

`deploy/casdoor/app.conf` 中 `redisEndpoint =` 为空，Casdoor **未连接 Redis**。当前部署无 Redis 组件。

## 五、应用层数据库（仅一个可选审计库）

`server` 与 `admin` 两个 Spring Boot 服务**均无 JPA / MyBatis / ORM 实体**——它们是无状态的判权/管理
facade，一切授权态读写都转发到 SpiceDB。唯一例外是 **admin 审计持久化**（`authz.audit.persistence-enabled`，
默认关=内存 500 条）：开启后用专用 Hikari 小连接池（4 连接）连 `authz_admin` 库，`authz_audit` 表由
`JdbcAuditStore` 启动时 `CREATE TABLE IF NOT EXISTS` 幂等自建（无 Flyway），DB 不可达则**启动失败**
（fail-fast，审计拒绝静默降级），`retention-max-rows`（默认 10 万）写入后裁剪最旧。admin 主应用排除了
`DataSourceAutoConfiguration`，不开审计时无任何数据源。
