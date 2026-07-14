# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

内部统一权限平台（IAM）：**Casdoor 负责身份/登录/SSO（OIDC，外部服务），SpiceDB 负责 Zanzibar/ReBAC
细粒度授权**。本仓库本身不含登录代码，也不含任何 LLM 集成——它是纯授权平台，让名下多个项目复用同一套
判权，而非各写一套。Maven 多模块，Spring Boot 3.3.5 / Java 21，groupId `com.lrj.authz`。

git 仓库（含 `.gitignore`）；有 Maven Wrapper（`./mvnw`，only-script 类型、无 jar、锁定 3.9.12）；
无 lint/format 工具、无 in-repo JUnit 测试（见下）。

## 架构（六边形：一个端口 + 两个适配器）

核心接缝是 `auth-platform-protocol` 里的 **`AuthzEngine` 端口**（6 个操作：check / checkBulk /
lookupResources / lookupSubjects / writeRelationships / deleteRelationships）。换引擎（如 OpenFGA、
或未来加 gRPC 适配器）只需新增一个实现类，上层零改。同一端口有两个实现，按调用方选择：

| 模块 | 类型 | 端口 | 职责 |
|---|---|---|---|
| `auth-platform-protocol` | 纯 Java 库（无 Spring/grpc） | — | 跨上下文 DTO 契约 + `AuthzEngine` 端口 |
| `auth-platform-core` | 库（spring-web + jackson） | — | `SpiceDbAuthzEngine` 适配器（SpiceDB HTTP/JSON）+ `schemas/*.zed` |
| `auth-platform-sdk` | Spring Boot Starter | — | 消费方 `RemoteAuthzEngine`（HTTP→server）+ `@CheckAccess` AOP |
| `auth-platform-server` | Spring Boot 服务 | 8200 | 判权服务：`SpiceDbAuthzEngine` 之上的 REST facade |
| `auth-platform-admin` | Spring Boot 服务 | 8201 | 授权管理 API + 判权调试器 + Casdoor 组同步/reconcile |

依赖方向全部指向 `protocol`：`protocol ← core ← {server, admin}`，`protocol ← sdk`。

**判权主链路（消费方强制权限）**：业务方法标 `@CheckAccess` →（sdk）`CheckAccessAspect` 用消费方提供的
`SubjectResolver` 取当前主体、从方法参数取资源 id、调 `engine.check()` → `RemoteAuthzEngine` HTTP
`POST /v1/check` 到 server(:8200) → `AuthzController` 委派其 `SpiceDbAuthzEngine` bean →
`POST /v1/permissions/check` 到 SpiceDB HTTP(:8543)。判权不通过抛 `AccessDeniedException`。

**管理链路**：`admin` 的 `AdminController`（`/admin/*`）与 Casdoor 同步（`casdoor/GroupSyncService`
差量算法：期望态 Casdoor vs 当前态 SpiceDB → TOUCH/DELETE，幂等）直接连自己的 `SpiceDbAuthzEngine`。
Casdoor 相关全部由 `authz.casdoor.enabled` 门控，默认关。

**关键约束（勿破坏）**：全程 **grpc-free**——用 SpiceDB 的 HTTP/JSON API（Spring `RestClient`），刻意
避开 grpc/protobuf 与 langchain4j 根 pom 的钻石依赖冲突。`AuthzEngine` 端口保留正是为将来可另加 gRPC
适配器。见 README `关键风险备忘` 与 `SpiceDbAuthzEngine.java` 顶部注释。

## 数据/授权模型

无应用层 ORM/DB——平台无状态，全部授权态是 **SpiceDB 里的关系元组**。领域模型是 SpiceDB `.zed`（非 SQL）：
`auth-platform-core/src/main/resources/schemas/knowledge.zed`，定义 `user/group/organization/space/
folder/document`，权限用并集 + 层级箭头组合（如 `document.view = viewer + comment + public_viewer +
parent_folder->view + parent_space->view`）；公开链接用 `user:*` 通配主体；RBAC 作为特例（把角色的
group 绑到 space 的对应 relation）。对象 id 约定带租户前缀：`<tenantId>_<docId>`。

**一致性（ZedToken）**：这里的 "token" 是 SpiceDB 的一致性水位，不是会话令牌。三档：`MINIMIZE_LATENCY`
（默认判权）、`AT_LEAST_AS_FRESH`（写后读/撤权立即生效——带上写返回的 ZedToken）、`FULLY_CONSISTENT`
（管理/调试，admin 读一律用它）。**坑**：刚写入就读，必须带 ZedToken 或用 full，否则可能被量化快照漏读。
（README 提到的 "ZedToken 水位缓存" 目前代码里未实现。）

## 常用命令

有 Maven Wrapper：只需 JDK 21 在 PATH，用 `./mvnw` 即可（首次会自动下载锁定的 Maven 3.9.12
到 `~/.m2/wrapper/dists/`，无需预装 Maven）。也可直接用系统 `mvn`（3.9.x）。下方一律用 `./mvnw`。

```bash
# 构建全部模块（仓库根）
./mvnw clean install
./mvnw clean install -DskipTests
# 只构建某模块 + 其依赖
./mvnw -pl auth-platform-server -am install
# "typecheck"（无专用工具，只有编译）
./mvnw -q compile

# 运行服务（Spring Boot 插件；无应用 Dockerfile）
./mvnw -pl auth-platform-server -am spring-boot:run   # :8200
./mvnw -pl auth-platform-admin  -am spring-boot:run   # :8201

# 测试：仓库当前无 src/test，`./mvnw test` 跑 0 个测试。加测试后单测：
./mvnw -pl <module> -am test -Dtest=ClassName
./mvnw -pl <module> -am test -Dtest=ClassName#methodName
```

无 lint/format/checkstyle/spotless 配置——不要臆造这类命令。

## 基建与验证（改动务必端到端冒烟）

本仓库**没有 JUnit 测试**；事实上的验证方式是 `deploy/` 下的 bash 冒烟脚本（需 `curl` + `jq`）。

```bash
cd deploy
docker compose up -d                    # 起全栈：postgres -> spicedb migrate -> spicedb serve + casdoor
docker compose up -d spicedb            # 只起 SpiceDB（含依赖）
docker compose down --remove-orphans    # 停并清理（防 docker-proxy 残留占端口）

bash deploy/spicedb-smoke.sh            # Phase 0：灌 knowledge.zed + seed + ReBAC 判定断言
bash deploy/server-smoke.sh             # Phase 1：经 server(:8200) REST 复验全链路（先跑上一条 + 起 server）
```

端口（避开现有项目）：Casdoor 8000；SpiceDB gRPC/HTTP/metrics 50051/8543/9099；SpiceDB Postgres
15432；server 8200；admin 8201。Postgres 16 被 SpiceDB 与 Casdoor 共用。SpiceDB 用预共享
Bearer key（`authz_dev_key`）。配置：各服务 `application.yml`（读 `${SPICEDB_HTTP}`/`${SPICEDB_KEY}`）+
`deploy/.env.example`（复制为 `.env`）。SpiceDB 迁移由 compose 的一次性 `datastore migrate head` 完成，
无 Flyway/JPA。

## 代码约定（观察所得，非工具强制）

- 包名 `com.lrj.authz.<module>`（如 `com.lrj.authz.admin.casdoor`）。
- DTO 用 Java `record`，集中放进一个 `final` 容器类（私有构造 + 嵌套 record），命名 `*Dtos`
  （如 `AuthzDtos`、`AdminDtos`）；领域/协议类型是带 `of(...)` 静态工厂的 record（如 `ResourceRef`）。
- 配置类 `*Config`（`@Configuration`）+ `*Properties`（`@ConfigurationProperties`），用
  `@ConditionalOnProperty` 门控；属性前缀统一 `authz.*`（`authz.spicedb`/`authz.casdoor`/`authz.client`）。
- 构造器注入；Controller 用 `@RestController` + `@RequestMapping`；开关型特性默认 **关**（引入即安全）。
- Javadoc/注释一律中文。无代码生成（无 Lombok/MapStruct/protobuf）。

## 现状与注意

- README 的模块表部分是**规划态**：`auth-console`（React 前端）与 "ZedToken 水位缓存" 尚未落地代码。
  前端 `auth-console` 实现前请走 `/frontend-plan`。
- 完整设计文档在仓库外：`~/.claude/plans/mock-velvet-mist.md`（README 首段引用）。
