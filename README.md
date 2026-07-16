# auth-platform — 内部统一权限平台

内部统一 IAM:**Casdoor(身份/登录/SSO)+ SpiceDB(Zanzibar/ReBAC 细粒度授权)**,单一核心系统,接入层引擎可插拔。让名下多个项目(his-platform/langchain4j-platform/recsys/blog/…)不再各写一套登录+权限。

完整设计见 `~/.claude/plans/mock-velvet-mist.md`。

## 架构一览

```
前端 SPA ──OIDC──▶ Casdoor(身份/SSO)
业务服务 ──SDK──▶ auth-platform-server(判权) ──HTTP/JSON──▶ SpiceDB(ReBAC)
                 auth-platform-admin(授权管理/Casdoor 同步) + auth-console(管控台前端)
```

按 DDD 限界上下文划分:判权决策→`server`;授权管理+身份同步→`admin`;`protocol`(AuthzEngine 端口)/`core`(SpiceDb HTTP 适配器)/`sdk`(Starter)。

## 模块

| 模块 | 角色 |
|---|---|
| `auth-platform-protocol` | 跨上下文 DTO 契约 + `AuthzEngine` 端口(9 个操作) |
| `auth-platform-core` | `SpiceDbAuthzEngine` 适配器(SpiceDB HTTP/JSON,grpc-free)+ `schemas/*.zed`(knowledge/his) |
| `auth-platform-sdk` | Spring Boot Starter(消费方接入,`@CheckAccess` 切面,严格判权响应校验) |
| `auth-platform-server` | 判权服务(REST facade:check/checkBulk/lookup;grpc-free) |
| `auth-platform-admin` | 授权管理 + Casdoor 组/部门同步/reconcile + webhook + 审计 |
| `auth-console/` | 管控台前端(React+Vite+TS+antd,前后端分离;M1-M6 **已落地**) |

> ZedToken 水位缓存、审计持久化(Postgres)、部门同步端点、多 org 同步已落地(2026-07-16)。`server` 的 gRPC facade
> 仍是规划项(刻意,见 `docs/性能与容量规划.md`)——`AuthzEngine` 端口保留正是为将来可另加 gRPC 适配器。

## 文档导航

| 你想知道 | 看这篇 |
|---|---|
| 平台有哪些能力/API 面/边界 | [`docs/平台能力总览.md`](docs/平台能力总览.md) |
| **新项目接入要改什么（双侧清单）** | [`docs/新项目接入指南.md`](docs/新项目接入指南.md) |
| 统一登录（SSO/OIDC）手把手 | [`docs/统一登录平台接入手册.md`](docs/统一登录平台接入手册.md) |
| 当前 `document` 部门层级授权模型 | [`docs/authz-department-model.md`](docs/authz-department-model.md) |
| 高并发承载能力/瓶颈/扩容路线 | [`docs/性能与容量规划.md`](docs/性能与容量规划.md) |
| ReBAC 建模入门(换脑子)/组件/存储 | [`doc/`](doc/README.md)(getting-started/components/databases) |

## 端口(避开现有项目占用)

| 组件 | 端口 |
|---|---|
| Casdoor | 8000 |
| SpiceDB gRPC / HTTP / metrics | 50051 / 8543 / 9099 |
| SpiceDB Postgres | 15432 |
| auth-platform-server | 8200 |
| auth-platform-admin | 8201 |
| auth-console | 5273(dev) / 8202(prod) |

## 一键启停(前后端 + 基建)

`./dev.sh` 按依赖顺序拉起完整本地环境:基建(docker: postgres+spicedb+casdoor)→ 后端(server:8200 / admin:8201)→ 前端(auth-console:5273)。启动经健康检查逐层等待,幂等(端口已占的层自动跳过),后台进程日志落到 `logs/`。

```bash
./dev.sh                 # = up,一键启动全部
./dev.sh up --skip-build # 跳过 mvn install 提速(确定已构建过时)
./dev.sh up -f           # 启动后前台跟踪日志,Ctrl-C 一并停掉
./dev.sh status          # 各服务健康一览
./dev.sh logs server     # 跟踪单个日志(server|admin|frontend)
./dev.sh down            # 停止全部(含基建)
./dev.sh restart         # down 再 up
```

分层开关(对 up/down/restart 生效):`--no-infra`(基建在别处跑时)/ `--no-backend` / `--no-frontend`。依赖:docker(compose v2)、JDK21、pnpm(或 npm)、curl。详见 `./dev.sh help`。

## 起停基建

> 仅需基建(不起前后端)时用下面的原始 compose 命令;否则直接 `./dev.sh`。

```bash
cd deploy
# 起 SpiceDB 链路(postgres -> migrate -> serve)+ Casdoor
docker compose up -d
# 只起 SpiceDB
docker compose up -d spicedb
# 停并清理(防 docker-proxy 残留占端口)
docker compose down --remove-orphans
```

- Casdoor: http://localhost:8000 (默认 admin/123,OIDC 发现 `/.well-known/openid-configuration`)
- SpiceDB HTTP: http://localhost:8543 (Bearer `authz_dev_key`);gRPC localhost:50051

## 授权模型(SpiceDB `.zed`)

领域模型是 SpiceDB `.zed`(非 SQL),权威文件 `auth-platform-core/src/main/resources/schemas/knowledge.zed`
(7 个 definition:`user/group/organization/space/folder/department/document`)+ `his.zed` 样板。当前 `document`
走**部门层级知识隔离**:文档归属上传人部门(`home_dept`),`view = owner + viewer + public + home_dept->doc_reader`,
祖先部门自动可读;`share = owner + home_dept->member + home_dept->doc_admin`;`edit = owner`。旧的
`parent_space/parent_folder/public_viewer` 仅作兼容保留。完整规则见 **[`docs/authz-department-model.md`](docs/authz-department-model.md)**。

## 验证

`deploy/` 下的 bash 冒烟脚本(需 `curl` + `jq`):

```bash
bash deploy/spicedb-smoke.sh                          # 基础 ReBAC 断言(合并 schema;含部门模型向上传播断言)
bash deploy/server-smoke.sh                           # 经 server(:8200) REST 复验全链路
bash deploy/his-smoke.sh                              # his.zed 数据权限样板
bash deploy/sso-smoke.sh                              # Casdoor SSO Layer 0-2
TENANT=demo APPLY=1 bash deploy/dept-authz-fixture.sh # 部门层级模型 seed + 强一致自校验(取代 rag-authz-fixture.sh)
```

其它脚本:`casdoor-seed.sh`(role→scope)、`casdoor-tenant-provision.sh`(一键开租户,方案C Shared Application:
不再每租户建 app,幂等确保 shared app + 只建 org/user,登录用派生 client_id `<base>-org-<tenant>`,可选写 SpiceDB 成员组)。

## 状态

- ✅ **Phase 0 基建**:Casdoor + SpiceDB + Postgres 起停跑通;`knowledge.zed` schema 灌入;ReBAC 冒烟全过。验证 `deploy/spicedb-smoke.sh`。
- ✅ **Phase 1 平台核心**:`protocol`/`core`(SpiceDbAuthzEngine, HTTP, grpc-free)/`sdk`(Starter)/`server`(:8200)。构建通过 + server 端到端冒烟全绿(含 ZedToken 写后读一致性)。验证 `deploy/server-smoke.sh`。
- ✅ **Phase 2 知识库接入**:knowledge-service 加 authz 包 + `DocumentService` 双写 + `KnowledgeQueryService` 后过滤,`app.rag.authz.enabled` 开关默认 Noop(后演进为 `app.rag.authz.mode` disabled/shadow/enforce 三态)。现有 161 测试零回归;端到端集成测试 `KnowledgeAuthzIntegrationTest` 验证 owner 可见/他人不可见/授权即见/撤权即失。
- ✅ **Phase 3 后端**:`admin`(:8201)授权管理 API(grant/revoke/lookup/check)+ Casdoor 组同步(差量增删)+ webhook + reconcile。**已加固(P0-A)**:OIDC resource-server 校验 Casdoor JWT + groups(shortName)→权限,写端点需 `authz-admin`/读端点 `authz-viewer`,webhook 共享密钥——全鉴权矩阵 curl 验证(无token 401 / admin 200 / viewer 写403读200 / webhook 密钥)。`auth-console` 前端 **M1-M6 已落地**(React+Vite+TS,`./dev.sh` 一键起前后端+基建)。
- ✅ **Phase 4 his 样板**:`his.zed` 数据权限模型(本科室/科主任/主治跨科/作者)+ his-security 加 `@DataScope` 切面(默认关,`his.authz.enabled=true` 生效)。验证 `deploy/his-smoke.sh`(8 断言)+ `HisDataPermissionIntegrationTest`(端到端)。SSO 换 Casdoor = his 网关 idp/dual profile 的 issuer/jwk 配置切换(基础设施已具备)。
- ✅ **Phase 5 部门层级隔离 + Casdoor 全面接入**(2026-07-15):`knowledge.zed` 新增 `department` + 重写 `document`(部门层级模型,取代旧 D3),见 `docs/authz-department-model.md`;`admin` 加 `DepartmentSyncService`(Casdoor 嵌套 group→SpiceDB `department`,`authz.casdoor.department-sync-enabled` 门控)+ 组/部门 id 租户前缀 `<org>_<group>`(`CasdoorGroupIds`)+ `readRelationships` 直连元组差量 + `deleteThreshold` 删除熔断;`sdk`/`core` 判权响应**严格校验**(`allowed` 布尔 + checkBulk 基数对齐,错误不再折成 deny,仍 fail-closed);Casdoor SSO 上手文档(`docs/统一登录平台接入手册.md`)+ 多租户开通脚本(`casdoor-tenant-provision.sh`)。默认全关(引入即安全)。
- ✅ **Phase 6 边界项落地 + 性能硬化**(2026-07-16):**审计持久化**(admin `AuditStore` 抽端口,`authz.audit.persistence-enabled` 门控落 Postgres 独立库 `authz_admin`,幂等建表+retention 裁剪+DB 不可达 fail-fast,真实 PG 端到端验证含重启不丢);**ZedToken 水位缓存**(server `ZedTokenWatermark`:`at_least_as_fresh` 无 token 自动代入最近写水位,无水位回退 full,默认开可关);**部门同步 HTTP 端点**(`POST /admin/casdoor/sync-departments` + webhook 联动,401/409 矩阵验证);**多 org 同步**(`authz.casdoor.organizations` 列表);**HTTP 连接池**(sdk/core 换 JDK HttpClient 池化,core 补全超时);`spicedb-smoke.sh` 更新到部门模型断言(合并 schema 写入,live 全绿)。JUnit 38 测试全绿。容量分析见 `docs/性能与容量规划.md`。

## 关键风险备忘

✅ 已消除:原担心 authzed-java gRPC 与 langchain4j 根 pom(`grpc 1.59.1 / protobuf 3.25.8`)冲突。**决策改用 SpiceDB HTTP/JSON API(Spring RestClient)实现 SpiceDbAuthzEngine**,core/sdk/server 全程 grpc-free。knowledge-service 加 `auth-platform-sdk` 依赖后经 `-am` 构建验证:无 io.grpc 引入,161 测试通过。`AuthzEngine` 端口保留,未来要极致性能可另加 gRPC 适配器。
