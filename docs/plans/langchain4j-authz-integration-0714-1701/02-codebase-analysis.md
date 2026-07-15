# 代码库分析（codebase-explorer）

## 1. 代码基线说明

- AP 仓库在分析开始时工作树无变更。
- LP 仓库在分析开始时已有以下用户改动，均属于当前未提交草稿：
  - `knowledge-service/pom.xml`
  - `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java`
  - `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java`
  - `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/`（未跟踪目录）
  - `knowledge-service/src/test/java/com/lrj/platform/knowledge/authz/`（未跟踪目录）
- 本分析没有运行 Maven/npm 测试，因为任务限制除规划目录外不得写入，而测试会生成 `target/`、coverage 或前端产物。

## 2. AP：实际模块与调用链

### 2.1 SDK 与协议

调用链：

```text
消费方 public method
  -> CheckAccessAspect.around
  -> SubjectResolver.currentSubject
  -> 按 resourceIdParam 找方法参数
  -> AuthzEngine.check(..., MINIMIZE_LATENCY)
  -> RemoteAuthzEngine POST /v1/check
  -> auth-platform-server/AuthzController
  -> SpiceDbAuthzEngine
  -> SpiceDB HTTP API
```

可复用点：

| 路径 | 现有类/方法 | 结论 |
|---|---|---|
| `auth-platform-sdk/.../CheckAccess.java` | `@CheckAccess(permission, resourceType, resourceIdParam)` | 可直接用于单资源方法；资源 ID 必须已是完整字符串 |
| `auth-platform-sdk/.../CheckAccessAspect.java` | `around`, `resolveParam` | 固定使用 minimize latency；参数名找不到抛 `IllegalStateException`；没有 transport/deny 分类 |
| `auth-platform-sdk/.../SubjectResolver.java` | `currentSubject()` | Javadoc 已直接给出 knowledge + TenantContext 示例，但 LP 尚未提供 bean |
| `auth-platform-sdk/.../AuthzSdkAutoConfiguration.java` | `authzEngine`, `checkAccessAspect` | SDK 默认启用；只有存在 SubjectResolver 才注册切面 |
| `auth-platform-sdk/.../RemoteAuthzEngine.java` | check/checkBulk/lookup/write/delete/read | grpc-free，可复用；当前无超时、重试、鉴权 header、指标配置 |
| `auth-platform-protocol/.../AuthzEngine.java` | 9 个 port 方法 | 已覆盖试点所需 checkBulk、关系写删、lookup/read |
| `auth-platform-protocol/.../RelationshipUpdate.java` | `touch/create/delete` | TOUCH 适合作幂等投影 |
| `auth-platform-protocol/.../Consistency.java` | minimize/full/atLeastAsFresh | 可表达强一致与水位读 |

限制：`AccessDeniedException` 只是普通 RuntimeException，LP 没有对应 `@ControllerAdvice`，直接使用会有 500 风险。

### 2.2 server/core

- `auth-platform-server/.../AuthzController.java` 暴露 `/v1/check`、`/check-bulk`、lookup、关系写删读和 schema/expand。
- `auth-platform-core/.../SpiceDbAuthzEngine.java` 使用 Spring `RestClient` 调 SpiceDB HTTP API；关系写是一个 SpiceDB WriteRelationships 请求，单批具备 SpiceDB 原子性。
- `auth-platform-server` 未引入 resource-server/security，`application.yml` 只有 SpiceDB endpoint/key。也就是说当前 `/v1/relationships` 可由能访问 8200 的客户端写入；生产接入前必须加固。
- `RemoteAuthzEngine.checkBulk` 依赖 server 返回顺序与请求顺序一致；server 目前按请求列表重建结果，契约一致。

### 2.3 schema

`auth-platform-core/src/main/resources/schemas/knowledge.zed` 已满足核心继承：

- `group.member: user | group#member`；
- `space.admin/editor/commenter/viewer: user | group#member`；
- `document.parent_space: space`；
- document `view/edit` 继承 parent_space；
- `public_viewer: user:*`。

缺口不是 schema 类型，而是实际关系生命周期、租户命名空间、迁移与治理。

### 2.4 Casdoor 同步

实际链路：

```text
CasdoorSyncController.sync/webhook 或 ReconcileJob.reconcile
  -> GroupSyncService.sync (synchronized，仅单 JVM)
  -> CasdoorClient.groupMembers/groupNames
  -> lookupSubjects(group, "member", "user", FULLY_CONSISTENT)
  -> desired-current 差集
  -> TOUCH/DELETE 一批写 SpiceDB
```

可复用：差量算法、TOUCH/DELETE、手动/webhook/reconcile 三入口、Casdoor `id` 作为 OIDC sub 的配置方向。

必须修正：

- `CasdoorClient.shortName()` 丢弃 owner/path，两个 tenant 同名组会碰撞。
- `GroupSyncService.sync()` 的 `synchronized` 不覆盖多副本。
- current 用 `lookupSubjects`，读的是权限展开结果，不是直接 member tuple；引入嵌套组后不能用它精确删直接边。
- 配置只支持一个 `organization` 和一个 `subjectField`，不足以表达 LP 多租户映射。
- webhook 只做共享 secret 常量时间比较，没有 timestamp/nonce/HMAC 防重放（AP 自身旧计划也已指出该问题）。
- AP `application.yml` 尚未声明 `authz.casdoor.*` 示例值；默认 `enabled=false`。

## 3. LP：身份与租户调用链

### 3.1 当前 edge 双模入口

```text
Authorization: Bearer <auth-service session JWT>
  -> SessionBearerAuthFilter(order=-110)
  -> 用 SESSION_JWT_SECRET 验签
  -> InternalToken.mint

或 X-Api-Key
  -> ApiKeyToInternalTokenFilter(order=-100)
  -> application.yml apiKeys 映射 tenant/user/scopes
  -> InternalToken.mint

两者 -> X-Internal-Token -> 下游 InternalTokenAuthFilter
     -> TenantContext(tenantId,userId,scopes)
```

关键文件：

- `edge-gateway/.../SessionBearerAuthFilter.java`
- `edge-gateway/.../ApiKeyToInternalTokenFilter.java`
- `edge-gateway/.../EdgeOpenPaths.java`
- `edge-gateway/src/main/resources/application.yml`
- `platform-security/.../InternalToken.java`
- `platform-security/.../InternalTokenAuthFilter.java`
- `platform-security/.../TenantContext.java`

重要安全边界：`InternalTokenAuthFilter` 对缺失/无效 token 只是不绑定 context，并不主动 401；业务代码若不检查 scope，直连服务会落到 `TenantContext.ANONYMOUS` 继续执行。生产必须关闭 `allow-api-key-fallback` 并用网络边界/过滤器拒绝匿名业务请求。

### 3.2 auth-service

本地登录链：

```text
POST /auth/login
 -> AuthService.login
 -> UserAccountStore + PasswordHasher
 -> EffectivePermissionResolver
 -> SessionTokenIssuer (sub=tenant, uid=userId, scopes)
 -> AUTH_SESSION refresh token hash
 -> edge SessionBearerAuthFilter 再换内部 JWT
```

实际 RBAC 数据：

| 领域 | Java 模型/Store | JDBC 表 |
|---|---|---|
| 用户 | `UserAccount`, `UserAccountStore` | `USERS` |
| 个人角色 | `Role`, `RoleStore` | `ROLES`, `ROLE_SCOPE`, `USER_ROLE` |
| 租户基础角色 | `TenantPolicyStore` | `TENANT_POLICY`, `TENANT_ROLE` |
| 用户组 | `Group`, `GroupStore` | `AUTH_GROUP`, `GROUP_ROLE` |
| 组成员 | `UserGroupStore` | `USER_GROUP` |
| 刷新会话 | `RefreshSessionStore` | `AUTH_SESSION` |

`AdminService` 已对更新使用 `RbacMutationExecutor`、版本号和 If-Match，并在降权时撤销 refresh session；这些只保证 auth DB 内的事务，不涵盖 Casdoor/SpiceDB。

数据模型约束：Role/Group 全局；Group 成员用 username，SpiceDB/Casdoor 需要 Casdoor sub，因此迁移必须建立 `username/旧 USER_ID -> Casdoor sub` 交叉表。现有 `JdbcUserAccountStore` 的普通 profile update 不更新 `USER_ID`，迁移不能假设管理 API 已支持该动作。

### 3.3 前端

`capability-showcase-frontend` 目前：

- `api/auth.ts` 调 `/auth/login|refresh|register|logout`，refresh cookie + 内存 access token；
- `stores/auth.ts` 实现 login/register/refresh 单飞；
- `router/index.ts` 以本地登录状态和 scopes 做路由守卫；
- `LoginView.vue` 带 demo 账号体验；
- `AuthControl.vue` 允许手输 API key 覆盖登录；
- `api/client.ts`、`api/knowledge.ts`、`api/sse.ts`、`api/catalog.ts` 都支持 API key 优先、Bearer 次之；
- `api/authorizedFetch.ts` 遇 401 调本地 `/auth/refresh`；
- package.json 尚无 OIDC client 依赖。

Casdoor 切换会影响前端认证基础设施和测试，但业务 API 的 `Authorization: Bearer` 发送方式可以保留。

## 4. LP：knowledge-service 实际链路

### 4.1 文档写入

```text
DocumentController.uploadFile/uploadJson
 -> requireWrite(scope)
 -> DocumentService.upload
 -> computeDocId(tenant, displayName)
 -> 若同名：deleteInternal(old)
 -> 向量 + mirror + ES + graph
 -> registry.put(DocumentInfo)
 -> [未提交草稿] KnowledgeAuthz.onDocumentCreated
 -> audit + cache invalidate
```

当前问题：

- 没有一等 `space`；草稿把所有文档挂虚拟 `<tenant>_default`。
- registry/向量/ES/graph/SpiceDB 无共同事务；authz 写失败时前面的业务写已落地。
- 同名版本更新没有清旧 document owner tuple；新 uploader TOUCH 后可能多 owner。
- 文档元数据没有 `spaceId`、`ownerSubjectId`、authz projection state/version。
- `DocumentService.delete` 实际先删业务数据和 registry，再删关系；其“先清元组再删数据”注释与执行顺序相反。

### 4.2 文档读取

- `DocumentController.list/get` 直接读 registry，无 ReBAC。
- `DocumentController.delete` 只看 ingest/public-ingest scope，不看 document edit。
- `KnowledgeQueryService.query` 先多源召回、融合，再由未提交草稿 `filterReadable` 做 checkBulk，之后 rerank。
- 草稿对 shared 命中和 `docId == null` 命中直接放行；后者可能绕过细粒度授权。
- 草稿以进程内 `AtomicReference<String> watermark` 保存最后一次 ZedToken，多副本/重启/来自 GroupSync 的权限变更均不覆盖。
- `KnowledgeAuthzIntegrationTest` 在 server 不可用时通过 assumption 跳过，因此默认 CI 绿不代表 ReBAC E2E 真正执行。

### 4.3 配置与构建

- `knowledge-service/pom.xml` 的 AP SDK 依赖是未提交改动，版本硬编码 `0.1.0-SNAPSHOT`；LP 根 dependencyManagement 未管理 AP 版本。
- `knowledge-service/application.yml` 没有 `app.rag.authz` 或 `authz.client` 配置块。
- compose/Helm 没有 `AUTHZ_SERVER_URL`、authz 开关、service credential，也没有 AP server/admin 的部署依赖。
- LP Dockerfile 复制预构建 jar；跨仓库构建必须先发布/安装 AP SDK，不能只在 LP 执行普通 reactor `-am` 就假设能解析外部制品。

## 5. 现有测试覆盖

可复用测试资产：

- AP `deploy/spicedb-smoke.sh`：加载 schema、seed、继承/公开/lookup 断言。
- AP `deploy/server-smoke.sh`：通过 server 写删关系和用 ZedToken 检查。
- LP knowledge：`DocumentServiceTest`、`TenantIsolationTest`、`KnowledgeQueryServiceTest`、ES fusion、public KB、controllers、registry。
- LP security：`InternalTokenTest`、`InternalTokenRs256Test`。
- LP edge：`SessionBearerAuthFilterTest`、`ApiKeyToInternalTokenFilterTest`、`EdgeOpenPathsTest`。
- LP auth：`AuthServiceTest`、`AdminService/Controller`、JDBC store/migration、effective permission tests。
- 未提交 `KnowledgeAuthzIntegrationTest`：覆盖 owner/未授权/grant/revoke，但会跳过，且未覆盖 @CheckAccess、space、SSO、组继承、故障补偿。

AP core/sdk/server/admin 当前仓库内没有对应 Java `src/test` 文件，新增行为不能只靠脚本。

## 6. 受影响文件清单

### 6.1 AP（现有文件预计修改）

- `pom.xml`（若增加统一测试/安全依赖管理）
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/AuthzClientProperties.java`
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/RemoteAuthzEngine.java`
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/AuthzSdkAutoConfiguration.java`
- `auth-platform-sdk/src/main/java/com/lrj/authz/sdk/CheckAccessAspect.java`
- `auth-platform-server/pom.xml`
- `auth-platform-server/src/main/java/com/lrj/authz/server/AuthzController.java`
- `auth-platform-server/src/main/resources/application.yml`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorClient.java`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorProperties.java`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/GroupSyncService.java`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/ReconcileJob.java`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/casdoor/CasdoorSyncController.java`
- `auth-platform-admin/src/main/resources/application.yml`
- `auth-platform-core/src/main/resources/schemas/knowledge.zed`、`auth-platform-core/src/main/resources/schemas/his.zed`（当前能力基本足够，预计不改；列入兼容检查是因为生产只能发布完整合并 schema，禁止单独覆盖）
- `deploy/spicedb-smoke.sh`、`deploy/server-smoke.sh`、`deploy/docker-compose.yml`、`dev.sh`
- `README.md`、`doc/components.md`、`doc/databases.md`

AP 拟新增（名称为方案建议，不声称已存在）：server service-auth 配置/过滤器测试；Casdoor group ID mapper；直接 tuple reconciler；LP RBAC migration/reconcile adapter；相关单元/集成测试。

### 6.2 LP（现有文件预计修改）

- 根 `pom.xml`、`knowledge-service/pom.xml`
- `platform-security/src/main/java/com/lrj/platform/security/TenantContext.java`（契约不改，最多补校验/文档）
- `platform-security/src/main/java/com/lrj/platform/security/InternalTokenAuthFilter.java`
- `platform-security/src/main/java/com/lrj/platform/security/InternalSecurityProperties.java`
- `edge-gateway/pom.xml`
- `edge-gateway/src/main/java/com/lrj/platform/edge/SessionBearerAuthFilter.java`（退役/兼容 profile）
- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`（退役/兼容 profile）
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeOpenPaths.java`
- `edge-gateway/src/main/resources/application.yml`
- `auth-service/src/main/java/com/lrj/platform/auth/AuthController.java`、`AuthService.java`、`SessionTokenIssuer.java`（本地登录退役）
- `auth-service/src/main/java/com/lrj/platform/auth/AdminController.java`、`AdminService.java`、`JdbcUserAccountStore.java` 及其他 Store（迁移导出/只读期）
- `auth-service/src/main/resources/application.yml`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/KnowledgeQueryController.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentInfo.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentRegistry.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/InMemoryDocumentRegistry.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/RedisDocumentRegistry.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/KnowledgeAuthz.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/NoopKnowledgeAuthz.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/RealKnowledgeAuthz.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/authz/KnowledgeAuthzConfig.java`
- `knowledge-service/src/main/resources/application.yml`
- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeQueryRequest.java`、`KnowledgeHit.java`（若 spaceId 加入跨服务合同）
- 所有构造这两个 record 的调用点：`conversation-service/src/main/java/com/lrj/platform/conversation/RagPromptAugmenter.java`、`agent-service/src/main/java/com/lrj/platform/agent/actions/RagSearchAction.java`、`eval-service/src/main/java/com/lrj/platform/eval/retrieval/HttpRetrievalClient.java`、`channel-service/src/main/java/com/lrj/platform/channel/dingtalk/DingtalkMessageBridge.java` 及其测试。
- `capability-showcase-frontend/package.json`
- `capability-showcase-frontend/src/api/auth.ts`、`authorizedFetch.ts`、`client.ts`、`knowledge.ts`、`sse.ts`、`catalog.ts`
- `capability-showcase-frontend/src/stores/auth.ts`、`session.ts`
- `capability-showcase-frontend/src/router/index.ts`
- `capability-showcase-frontend/src/modules/auth/LoginView.vue`、`RegisterView.vue`
- `capability-showcase-frontend/src/components/layout/AuthControl.vue`、`ApiKeyInput.vue`
- `capability-showcase-frontend/src/config.ts`、`Dockerfile`、`nginx.conf` 及相应测试。
- `eval-service/src/main/resources/application.yml`、`deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、`deploy/helm/platform/templates/secret.yaml`、`deploy/helm/platform/templates/externalsecret-sample.yaml`、`deploy/helm/README.md`
- 已核实使用 `X-Api-Key` 的部署脚本：`deploy/rag-demo.sh`、`seed-kb.sh`、`smoke-a2a.sh`、`smoke-es-hybrid-rag.sh`、`smoke-qdrant-rag.sh`、`smoke-nl2sql.sh`；实施时以全仓搜索结果补齐同类脚本/文档。
- `config-server/src/main/resources/config/application.yml`
- `docs/参考/架构文档.md`、`docs/参考/operations.md`、`docs/平台工程/rbac-and-public-kb.md`、`docs/平台工程/deployment-guide.md`、`README.md`

LP 拟新增：space 领域模型/registry/controller/application service；`SubjectResolver` bean；resource ID codec；authz exception handler；独立 `DocumentAuthorizationRecord/KnowledgeAuthorizationRegistry`、authz projection reconciler 与 document lifecycle reconciler；Casdoor token exchange/claim mapper；OIDC callback 组件与测试。

## 7. 跨模块兼容性结论

- Spring/Java 版本兼容，AP SDK grpc-free，规避 LP 已固定的 gRPC/protobuf 依赖冲突。
- 最大构建风险是跨仓库 SNAPSHOT 制品供应，不是二进制依赖冲突；必须建立本地 install/制品仓库/CI pipeline 顺序。
- 最大数据风险是旧 userId 与 Casdoor sub 不同、全局组跨租户、旧文档无 spaceId。
- 最大运行风险是当前 Noop/boolean 开关会 fail open、server 关系写端点无鉴权、进程水位无法覆盖多副本与外部组变更。
- 最大接口风险是 spaceId 加入 protocol record 后的全仓构造器编译影响；必须提供兼容构造或一次性更新已列出的全部调用点。
