# auth-platform — 内部统一权限平台

内部统一 IAM:**Casdoor(身份/登录/SSO)+ SpiceDB(Zanzibar/ReBAC 细粒度授权)**,单一核心系统,接入层引擎可插拔。让名下多个项目(his-platform/langchain4j-platform/recsys/blog/…)不再各写一套登录+权限。

完整设计见 `~/.claude/plans/mock-velvet-mist.md`。

## 架构一览

```
前端 SPA ──OIDC──▶ Casdoor(身份/SSO)
业务服务 ──SDK──▶ auth-platform-server(判权) ──gRPC──▶ SpiceDB(ReBAC)
                 auth-platform-admin(授权管理/Casdoor 同步) + auth-console(管控台前端)
```

按 DDD 限界上下文划分:判权决策→`server`;授权管理+身份同步→`admin`;`core`(AuthzEngine 端口 + SpiceDb 适配器)/`protocol`/`sdk`(Starter)。

## 模块(规划)

| 模块 | 角色 |
|---|---|
| `auth-platform-protocol` | 跨上下文 DTO 契约 |
| `auth-platform-core` | 领域模型 + `AuthzEngine` 端口 + `SpiceDbAuthzEngine` 适配器 + `schemas/*.zed` |
| `auth-platform-sdk` | Spring Boot Starter(消费方接入,`@CheckAccess` 切面) |
| `auth-platform-server` | 判权服务(REST+gRPC facade,CheckBulk,ZedToken 水位缓存) |
| `auth-platform-admin` | 授权管理 + Casdoor Webhook/同步/reconcile + 审计 |
| `auth-console/` | 管控台前端(React+Vite+TS+antd,前后端分离) |

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

## 验证

```bash
# Phase 0 冒烟: 灌 knowledge.zed + seed 关系 + 跑判定断言(继承/单篇授权/公开/lookup)
bash deploy/spicedb-smoke.sh
```

## 状态

- ✅ **Phase 0 基建**:Casdoor + SpiceDB + Postgres 起停跑通;`knowledge.zed` schema 灌入;ReBAC 冒烟全过。验证 `deploy/spicedb-smoke.sh`。
- ✅ **Phase 1 平台核心**:`protocol`/`core`(SpiceDbAuthzEngine, HTTP, grpc-free)/`sdk`(Starter)/`server`(:8200)。构建通过 + server 端到端冒烟全绿(含 ZedToken 写后读一致性)。验证 `deploy/server-smoke.sh`。
- ✅ **Phase 2 知识库接入**:knowledge-service 加 authz 包 + `DocumentService` 双写 + `KnowledgeQueryService` 后过滤,`app.rag.authz.enabled` 开关默认 Noop。现有 161 测试零回归;端到端集成测试 `KnowledgeAuthzIntegrationTest` 验证 owner 可见/他人不可见/授权即见/撤权即失。
- ✅ **Phase 3 后端**:`admin`(:8201)授权管理 API(grant/revoke/lookup/check)+ Casdoor 组同步(差量增删)+ webhook + reconcile。**已加固(P0-A)**:OIDC resource-server 校验 Casdoor JWT + groups(shortName)→权限,写端点需 `authz-admin`/读端点 `authz-viewer`,webhook 共享密钥——全鉴权矩阵 curl 验证(无token 401 / admin 200 / viewer 写403读200 / webhook 密钥)。`auth-console` 前端已完成 frontend-plan(→ `auth-console/PLAN.md`),**待实现(M1-M6)**。
- ✅ **Phase 4 his 样板**:`his.zed` 数据权限模型(本科室/科主任/主治跨科/作者)+ his-security 加 `@DataScope` 切面(默认关,`his.authz.enabled=true` 生效)。验证 `deploy/his-smoke.sh`(8 断言)+ `HisDataPermissionIntegrationTest`(端到端)。SSO 换 Casdoor = his 网关 idp/dual profile 的 issuer/jwk 配置切换(基础设施已具备)。

## 关键风险备忘

✅ 已消除:原担心 authzed-java gRPC 与 langchain4j 根 pom(`grpc 1.59.1 / protobuf 3.25.8`)冲突。**决策改用 SpiceDB HTTP/JSON API(Spring RestClient)实现 SpiceDbAuthzEngine**,core/sdk/server 全程 grpc-free。knowledge-service 加 `auth-platform-sdk` 依赖后经 `-am` 构建验证:无 io.grpc 引入,161 测试通过。`AuthzEngine` 端口保留,未来要极致性能可另加 gRPC 适配器。
