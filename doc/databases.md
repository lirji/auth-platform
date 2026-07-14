# 数据库与存储

平台**无应用层 ORM/JPA/DB**——判权服务本身无状态，所有授权态都以**关系元组（relationship tuples）**的形式
存在 SpiceDB 中。真正落到磁盘的只有一个关系库：**PostgreSQL**，它同时充当 SpiceDB 和 Casdoor 的后端存储。

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
> （README 提到的 “ZedToken 水位缓存” 目前**代码里未实现**。）

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
| `folder` | 文件夹（可嵌套） | `edit/view`，向上继承 `parent_folder->` 与 `parent_space->` |
| `document` | 单篇文档 | `edit/comment/view`，含 `parent_folder->` / `parent_space->` 继承 |

特性：
- **公开链接**：`public_viewer: user:*` 通配主体（space 与 document 均支持）。
- **RBAC 作为特例**：把角色对应的 `group` 绑到 `space` 的对应 relation。
- **层级继承**：如 `document.view = viewer + comment + public_viewer + parent_folder->view + parent_space->view`。

### his.zed —— HIS 数据权限模型（演示）

| 定义 | 含义 | 关键权限 |
|---|---|---|
| `dept` | 科室 | `access = member + head`（本科室成员或主任） |
| `patient` | 患者 | `care = attending`（主治医生，可跨科） |
| `encounter` | 就诊 / 病历记录 | `view = author + dept->access + subject->care`；`edit = author + dept->head` |

场景：只能看本科室 + 科主任看全科 + 主治医生跨科看自己患者 + 记录作者可见可改。

## 四、Redis —— 引用但未启用

`deploy/casdoor/app.conf` 中 `redisEndpoint =` 为空，Casdoor **未连接 Redis**。当前部署无 Redis 组件。

## 五、无应用层数据库

`server` 与 `admin` 两个 Spring Boot 服务**均无 JPA / MyBatis / 数据源配置**——它们是无状态的判权/管理
facade，一切授权态读写都转发到 SpiceDB。因此本仓库没有 SQL 建表脚本、没有 ORM 实体。
