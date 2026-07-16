# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

内部统一权限平台（IAM）：**Casdoor 负责身份/登录/SSO（OIDC，外部服务），SpiceDB 负责 Zanzibar/ReBAC
细粒度授权**。本仓库本身不含登录代码，也不含任何 LLM 集成——它是纯授权平台，让名下多个项目复用同一套
判权，而非各写一套。Maven 多模块，Spring Boot 3.3.5 / Java 21，groupId `com.lrj.authz`。

git 仓库（含 `.gitignore`）；有 Maven Wrapper（`./mvnw`，only-script 类型、无 jar、锁定 3.9.12）；
无 lint/format 工具；JUnit 测试仅覆盖关键校验路径（sdk/admin/server 三模块 5 个测试类，见下），
端到端验证仍靠 `deploy/` 冒烟脚本。

## 架构（六边形：一个端口 + 两个适配器）

核心接缝是 `auth-platform-protocol` 里的 **`AuthzEngine` 端口**（9 个操作：check / checkBulk /
lookupResources / lookupSubjects / writeRelationships / deleteRelationships / readSchema / expand /
readRelationships）。换引擎（如 OpenFGA、或未来加 gRPC 适配器）只需新增一个实现类，上层零改。同一端口
有两个实现，按调用方选择：

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
**严格响应校验（勿放松）**：sdk `RemoteAuthzEngine`（`requireAllowed` / `parseCheckBulk` 校验 `allowed`
布尔 + checkBulk 基数/资源对齐）与 core `SpiceDbAuthzEngine`（`hasPermission` 校验 permissionship 非空、
per-item error 即抛）对称——判权响应缺字段/错位/后端报错**一律抛异常**（不再折成 deny），上层仍 fail-closed。

**管理链路**：`admin` 的 `AdminController`（`/admin/*`）与 Casdoor 同步直接连自己的 `SpiceDbAuthzEngine`。
两个同步器都是差量算法（期望态 Casdoor vs 当前态 SpiceDB，用 `readRelationships` 读 direct-tuple 比对 →
TOUCH/DELETE，幂等；`CasdoorProperties.deleteThreshold` 删除熔断防误删）：`casdoor/GroupSyncService`
同步用户组成员（RBAC），`casdoor/DepartmentSyncService`（新增，`authz.casdoor.department-sync-enabled`
门控）把 Casdoor 嵌套 group 树同步进 SpiceDB `department`（parent/member/admin）。**组/部门 id 一律
经 `CasdoorGroupIds` 编码为 `<org>_<group>` 租户前缀**，防跨租户同名组串权。Casdoor 相关全部由
`authz.casdoor.enabled` 门控，默认关。

**关键约束（勿破坏）**：全程 **grpc-free**——用 SpiceDB 的 HTTP/JSON API（Spring `RestClient`），刻意
避开 grpc/protobuf 与 langchain4j 根 pom 的钻石依赖冲突。`AuthzEngine` 端口保留正是为将来可另加 gRPC
适配器。见 README `关键风险备忘` 与 `SpiceDbAuthzEngine.java` 顶部注释。

## 数据/授权模型

无应用层 ORM/DB——平台无状态，全部授权态是 **SpiceDB 里的关系元组**（唯一例外：admin 审计可选持久化
到独立 Postgres 库 `authz_admin`，`authz.audit.persistence-enabled` 门控，默认关=内存 500 条）。
领域模型是 SpiceDB `.zed`（非 SQL）：
`auth-platform-core/src/main/resources/schemas/knowledge.zed`（7 个 definition：`user/group/
organization/space/folder/department/document`；另有 `his.zed` 样板 `dept/patient/encounter`，两份
合并加载共 10 个 definition；`recsys.zed`（广告主作用域模型 `platform/advertiser`）**不参与合并**——
按 §B 写入 recsys 专属 SpiceDB 实例），权限用并集 + 层级箭头组合；公开链接用 `user:*` 通配主体；RBAC 作为特例
（把角色的 group 绑到 space 的对应 relation）。对象 id 约定带租户前缀：`<tenantId>_<docId>`；组/部门 id
带 `<org>_<group>` 前缀。

**部门层级知识隔离（当前 `document` 模型，取代旧 D3；详见 `docs/authz-department-model.md`）**：文档归属上传
人部门（`home_dept`），`document.view = owner + viewer + public + home_dept->doc_reader`、`share =
owner + home_dept->member + home_dept->doc_admin`、`edit = owner`（删除/覆盖仅 owner）。`department`
的 `doc_reader = member + parent->doc_reader` **沿部门树向上传播**（祖先部门能看后代文档，方向与 `space/
folder` 的向下继承相反）。旧的 `parent_space/parent_folder/editor/commenter/public_viewer` 仅作兼容/
回滚保留，**已不进 `view/edit`**（勿据旧公式判断可见性）。

**一致性（ZedToken）**：这里的 "token" 是 SpiceDB 的一致性水位，不是会话令牌。三档：`MINIMIZE_LATENCY`
（默认判权）、`AT_LEAST_AS_FRESH`（写后读/撤权立即生效——带上写返回的 ZedToken）、`FULLY_CONSISTENT`
（管理/调试，admin 读一律用它）。**坑**：刚写入就读，必须带 ZedToken 或用 full，否则可能被量化快照漏读。
server 有 **ZedToken 水位缓存**（`ZedTokenWatermark`，`authz.server.zed-token-watermark-enabled` 默认开）：
`at_least_as_fresh` 无 token 的请求自动代入本实例最近写水位，无水位回退 full；**单实例内存态**，多实例
部署跨实例写后读仍须调用方自带 token。

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

# 测试：sdk/admin/server 三模块有 JUnit 测试（RemoteAuthzEngineTest、GroupSyncServiceTest、
# DepartmentSyncServiceTest、CasdoorGroupIdsTest、JdbcAuditStoreTest、AuthzServerSecurityFilterTest、
# AuthzControllerWatermarkTest）。单测：
./mvnw -pl <module> -am test -Dtest=ClassName
./mvnw -pl <module> -am test -Dtest=ClassName#methodName
```

无 lint/format/checkstyle/spotless 配置——不要臆造这类命令。

## 基建与验证（改动务必端到端冒烟）

JUnit 测试只覆盖关键校验路径（严格响应校验/同步差量/id 编码/安全过滤器）；端到端的事实验证方式
仍是 `deploy/` 下的 bash 冒烟脚本（需 `curl` + `jq`）。

```bash
cd deploy
docker compose up -d                    # 起全栈：postgres -> spicedb migrate -> spicedb serve + casdoor
docker compose up -d spicedb            # 只起 SpiceDB（含依赖）
docker compose down --remove-orphans    # 停并清理（防 docker-proxy 残留占端口）

bash deploy/spicedb-smoke.sh            # Phase 0：灌合并 schema + seed + ReBAC 判定断言（含部门模型）
bash deploy/server-smoke.sh             # Phase 1：经 server(:8200) REST 复验全链路（先跑上一条 + 起 server）
bash deploy/his-smoke.sh                # his.zed 数据权限样板断言
bash deploy/sso-smoke.sh                # Casdoor SSO Layer 0-2（见 docs/统一登录平台接入手册.md）
TENANT=demo APPLY=1 bash deploy/dept-authz-fixture.sh   # 部门层级模型 seed + 强一致自校验（取代 rag-authz-fixture.sh）
```

`deploy/` 脚本清单：`spicedb-smoke.sh`、`server-smoke.sh`、`his-smoke.sh`、`sso-smoke.sh`、
`casdoor-seed.sh`（role→scope）、`casdoor-tenant-provision.sh`（一键开租户，方案C Shared Application：
幂等确保 shared app `rag-shared` + 只建 org/user，登录用派生 client_id `<base>-org-<tenant>`，可选写 SpiceDB 成员组）、
`dept-authz-fixture.sh`（部门模型 seed/自校验）、`recsys-authz-fixture.sh`（recsys 广告主模型 seed/自校验，
目标是 recsys 专属 SpiceDB 实例 :8544，勿指到本项目 :8543）、`rag-authz-fixture.sh`（旧 D3 夹具，已被 dept 取代）。
> `spicedb-smoke.sh` 写 schema 用 **knowledge.zed+his.zed 合并**（schema/write 是整体替换语义，只写单份会
> 试图删掉另一份的 definition——有存量元组时被 SpiceDB 拒绝）；断言已覆盖部门模型（向上传播/edit 仅 owner）。

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
  Casdoor 相关开关：`authz.casdoor.enabled`（总开关）、`authz.casdoor.department-sync-enabled`（部门树同步）、
  `authz.casdoor.delete-threshold`（同步删除熔断阈值）。
- 构造器注入；Controller 用 `@RestController` + `@RequestMapping`；开关型特性默认 **关**（引入即安全）。
- Javadoc/注释一律中文。无代码生成（无 Lombok/MapStruct/protobuf）。

## 现状与注意

- `auth-console`（React+Vite+TS 前端管控台）**已落地**（M1-M6 完成，`auth-console/` 含 `src/`/`dist/`/
  Dockerfile；`dev.sh` 一键起前后端+基建）。仍**未落地**的规划项：`server` 的 gRPC facade（刻意，见
  `docs/性能与容量规划.md` B9）。ZedToken 水位缓存、审计持久化、部门同步端点、多 org 同步已于 2026-07-16 落地。
- 部门层级知识隔离模型的权威文档：`docs/authz-department-model.md`；Casdoor SSO 接入：`docs/统一登录平台接入手册.md`；
  平台能力/API 参考：`docs/平台能力总览.md`；新项目接入双侧清单：`docs/新项目接入指南.md`；
  容量/性能：`docs/性能与容量规划.md`。
- 同步触发面：组同步 `POST /admin/casdoor/sync` + webhook；部门同步 `POST /admin/casdoor/sync-departments` +
  webhook 联动 + `ReconcileJob` 定时对账。多 org 用 `authz.casdoor.organizations` 列表（空回退单 org）。
- 完整设计文档在仓库外：`~/.claude/plans/mock-velvet-mist.md`（README 首段引用）。
