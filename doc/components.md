# 组件清单

按三层整理：基础设施/中间件、后端（Java/Spring）、前端（React）。版本号来自各 `pom.xml`、
`package.json`、`docker-compose.yml` 与 `Dockerfile`。

---

## 一、基础设施 / 中间件（`deploy/docker-compose.yml`）

| 组件 | 镜像 | 端口（宿主→容器） | 角色 |
|---|---|---|---|
| **Casdoor** | `casbin/casdoor:latest` | `8000 → 8000` | 身份 / 登录 / SSO，OIDC 发行方；`admin` 通过其 JWKS 校验 JWT |
| **SpiceDB** | `authzed/spicedb:latest` | `50051`/`8543→8443`/`9099→9090` | Zanzibar/ReBAC 判权引擎 + 关系元组存储 |
| **spicedb-migrate** | `authzed/spicedb:latest` | 一次性任务 | `datastore migrate head`，serve 前迁移 |
| **PostgreSQL** | `postgres:16` | `15432 → 5432` | SpiceDB + Casdoor 共用关系库 |
| **nginx**（前端） | `nginx:1.27-alpine` | `8202` | `auth-console` 生产托管 + 同源反代 `/admin`→`:8201`（免 CORS） |

> Casdoor 相关能力在后端由 `authz.casdoor.enabled` 门控，**默认关**。
> Redis 在 Casdoor 配置中留空，未启用（见 [databases.md](./databases.md)）。

---

## 二、后端组件（Maven 多模块，Spring Boot 3.3.5 / Java 21，groupId `com.lrj.authz`）

### 模块划分（六边形：一个端口 + 两个适配器）

| 模块 | 类型 | 端口 | 职责 |
|---|---|---|---|
| `auth-platform-protocol` | 纯 Java 库（无 Spring/grpc） | — | 跨上下文 DTO 契约 + `AuthzEngine` 端口 |
| `auth-platform-core` | 库（spring-web + jackson） | — | `SpiceDbAuthzEngine` 适配器（SpiceDB HTTP/JSON）+ `schemas/*.zed` |
| `auth-platform-sdk` | Spring Boot Starter | — | 消费方 `RemoteAuthzEngine`（HTTP→server）+ `@CheckAccess` AOP 切面 |
| `auth-platform-server` | Spring Boot 服务 | 8200 | 判权服务：`SpiceDbAuthzEngine` 之上的 REST facade |
| `auth-platform-admin` | Spring Boot 服务 | 8201 | 授权管理 API + 判权调试 + Casdoor 组同步/reconcile |

依赖方向：`protocol ← core ← {server, admin}`，`protocol ← sdk`。

### 各模块 Maven 依赖

**auth-platform-protocol**：无外部依赖（纯 Java 契约层，刻意零 Spring/grpc）。

**auth-platform-core**
| 依赖 | 说明 |
|---|---|
| `auth-platform-protocol` | 端口与 DTO |
| `org.springframework:spring-web` | `RestClient` 调 SpiceDB HTTP API |
| `com.fasterxml.jackson.core:jackson-databind` | JSON 序列化 |

**auth-platform-sdk**（Spring Boot Starter，供消费方接入）
| 依赖 | 说明 |
|---|---|
| `auth-platform-protocol` | 端口与 DTO |
| `spring-boot-starter` | Starter 基座 + 自动配置 |
| `spring-web` | HTTP 调 server |
| `jackson-databind` | JSON |
| `spring-boot-starter-aop`（optional） | `@CheckAccess` 切面 |

**auth-platform-server**（:8200）
| 依赖 | 说明 |
|---|---|
| `auth-platform-core` | 内嵌 `SpiceDbAuthzEngine` |
| `spring-boot-starter-web` | REST 接口 |
| `spring-boot-starter-actuator` | health/info 探活 |
| `spring-boot-maven-plugin` | 可执行 jar / `spring-boot:run` |

**auth-platform-admin**（:8201）
| 依赖 | 说明 |
|---|---|
| `auth-platform-core` | 直连自己的 `SpiceDbAuthzEngine` |
| `spring-boot-starter-web` | 管理 REST API |
| `spring-boot-starter-actuator` | health/info |
| `spring-boot-starter-oauth2-resource-server` | 校验 Casdoor 签发的 JWT（JWKS / issuer / aud） |
| `spring-boot-starter-jdbc` + `org.postgresql:postgresql`(runtime) | 审计持久化（`authz.audit.persistence-enabled` 门控；主应用排除 DataSourceAutoConfiguration） |
| `com.h2database:h2`(test) | `JdbcAuditStoreTest` 内存库（PostgreSQL 兼容模式） |
| `spring-boot-maven-plugin` | 可执行 jar / `spring-boot:run` |

### 关键类 / 组件（自定义代码）

| 组件 | 所在模块 | 作用 |
|---|---|---|
| `AuthzEngine` | protocol | 判权端口（9 个操作）：check / checkBulk / lookupResources / lookupSubjects / writeRelationships / deleteRelationships / readSchema / expand / readRelationships |
| `Consistency` / `ZedTokenView` | protocol | 一致性水位三档 + ZedToken 视图 |
| `ResourceRef` / `SubjectRef` / `RelationshipUpdate` / `RelationshipFilter` | protocol | 领域 DTO（record + `of(...)` 工厂） |
| `SpiceDbAuthzEngine` | core | SpiceDB HTTP/JSON 适配器（端口的唯一落地实现） |
| `AuthzController` / `AuthzDtos` | server | 判权 REST facade（`/v1/check` 等） |
| `RemoteAuthzEngine` | sdk | 消费方 HTTP 客户端（→ server）；`requireAllowed`/`parseCheckBulk` 严格校验判权响应 |
| `@CheckAccess` / `CheckAccessAspect` / `SubjectResolver` | sdk | 声明式强制权限（AOP） |
| `AdminController` / `AdminDtos` | admin | 关系/授予管理 + 判权调试 API |
| `AuditStore`(端口) / `InMemoryAuditStore` / `JdbcAuditStore` / `AuditConfig` | admin | 审计：默认内存环形缓冲；`authz.audit.persistence-enabled=true` 落 Postgres `authz_admin` 库（幂等建表 + retention 裁剪 + fail-fast） |
| `ZedTokenWatermark` | server | ZedToken 水位缓存：`at_least_as_fresh` 无 token 自动代入最近写水位（默认开，单实例内存态） |
| `SecurityConfig` / `AdminSecurityProperties` | admin | OAuth2 资源服务器（Casdoor JWT 校验） |
| `casdoor/GroupSyncService` / `DepartmentSyncService` / `ReconcileJob` / `CasdoorClient` | admin | Casdoor 组/部门树 → SpiceDB 差量同步（`readRelationships` 直连元组比对 → TOUCH/DELETE，幂等，`deleteThreshold` 熔断）+ 定时 reconcile |
| `casdoor/CasdoorGroupIds` / `CasdoorProperties` / `CasdoorConfig` | admin | 组/部门 id 租户前缀编码 `<org>_<group>`（防跨租户串权）+ Casdoor 同步开关（`department-sync-enabled` 门控 `DepartmentSyncService`） |

### 构建工具

| 工具 | 版本 | 说明 |
|---|---|---|
| Maven Wrapper | 锁定 Maven 3.9.12 | `./mvnw`，only-script 型，无 jar |
| JDK | 21 | 编译/运行 |
| spring-boot-maven-plugin | 随 Boot 3.3.5 | 打包 / 本地 run |

> 无 lint/format/checkstyle/spotless。JUnit 测试覆盖关键校验路径：protocol/core/sdk/server/admin
> **五模块 20 个测试类、117 个 `@Test`**（`./mvnw test` 全绿）——含 core `SpiceDbAuthzEngineTest`（严格校验/token/stream）、
> sdk `RemoteAuthzEngine{,Http}Test`/`CheckAccessAspectTest`、server `AuthzController{Facade,Watermark}Test`/
> `AuthzServerSecurityFilter{,Boundary}Test`/`ZedTokenWatermarkTest`、admin `AdminControllerTest`（ADM01 两段审计）/
> `{Group,Department}SyncServiceTest`/`SyncServiceBoundaryTest`/`Casdoor*Test`/`{Jdbc,InMemory}AuditStoreTest`；
> 端到端事实验证仍靠 `deploy/*.sh` 冒烟脚本。

---

## 三、前端组件（`auth-console`，React + Vite + TypeScript）

生产托管走 nginx（:8202），dev 走 Vite（:5273，同源反代 `/admin`→:8201）。包管理器 **pnpm**，
构建镜像 `node:22-alpine`。

### 运行时依赖（`dependencies`）

| 库 | 版本 | 作用 |
|---|---|---|
| `react` / `react-dom` | ^18.3.1 | UI 框架 |
| `react-router-dom` | ^6.27.0 | 路由（SPA） |
| `antd` | ^5.21.6 | Ant Design 组件库 |
| `@ant-design/icons` | ^5.5.1 | 图标 |
| `@tanstack/react-query` | ^5.59.16 | 服务端状态 / 数据请求缓存 |
| `axios` | ^1.7.7 | HTTP 客户端（调 admin API） |
| `zustand` | ^5.0.1 | 轻量全局状态（如 auth store） |
| `oidc-client-ts` | ^3.5.0 | OIDC 协议客户端（对接 Casdoor） |
| `react-oidc-context` | ^3.3.1 | OIDC 的 React 绑定 / Provider |

### 开发依赖（`devDependencies`）

| 库 | 版本 | 作用 |
|---|---|---|
| `vite` | ^5.4.10 | 构建 / dev server |
| `@vitejs/plugin-react` | ^4.3.3 | React 插件 |
| `typescript` | ^5.6.3 | 类型系统 |
| `@types/react` / `@types/react-dom` | ^18.3.x | 类型定义 |

### 前端页面 / 模块

| 目录 | 内容 |
|---|---|
| `src/pages/` | Overview / Grants / Playground(判权调试) / SchemaViewer / Spaces / Audit / IdentitySync / Callback |
| `src/auth/` | OIDC 接入：`AppAuthProvider` / `AuthBridge` / `ProtectedRoute` / `oidcConfig` |
| `src/api/` | `client`（axios 实例）+ `authz`（admin API 封装） |
| `src/domain/` | `lexicon` / `zedParser`（解析 `.zed` schema 供前端展示） |
| `src/store/` | `authStore`（zustand） |

> **现状**：`auth-console` 为已初始化的前端工程（`README.md` 模块表曾标其为“规划态”，实际代码已存在）。
> 若做中大型前端改动，按全局规范先走 `/frontend-plan`。

### 前端构建 chunk 拆分（`vite.config.ts`）

`react` / `antd` / `oidc` / `query` 四组 `manualChunks`，便于缓存与体积控制。
