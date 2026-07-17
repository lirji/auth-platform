# 01 — 被测面与仓库现状

审计时间：2026-07-17（Asia/Shanghai）。本轮只阅读生产代码、POM、配置和既有测试；不修改业务代码，不向 `src/` 写测试。基线命令 `mvn test` 已执行成功：SDK 11、server 10、admin 17，共 38 个测试通过；protocol、core 均为 `No tests to run`。

## 模块、公共面与优先级

| 优先级 | 模块 | 实际类/公共方法 | 本轮必须锁定的交互 |
|---|---|---|---|
| P0 | `auth-platform-core` | `SpiceDbAuthzEngine`：两个构造器；`check`、`checkBulk`、`lookupResources`、`lookupSubjects`、`writeRelationships`、`deleteRelationships`、`readSchema`、`expand`、`readRelationships` | HTTP 路径/认证头/请求 JSON；三种 `Consistency`；userset 的 `optionalRelation`；单条与批量 fail-closed；拼接 JSON/NDJSON；流中错误；写后 ZedToken；过滤器可选字段；畸形/空/错误响应 |
| P0 | `auth-platform-sdk` | `CheckAccessAspect` 构造器、`around`；`CheckAccess` 四个属性；`SubjectResolver.currentSubject`；`RemoteAuthzEngine` 的 9 个端口方法（重点 `check`、`checkBulk`） | 主体先解析、参数按真实参数名解析、资源构造、full/minimize 分支、deny/依赖异常不执行目标方法；SDK HTTP 契约与严格 `allowed` |
| P1 | `auth-platform-server` | `AuthzController` 构造器及 9 个 REST facade 方法；`ZedTokenWatermark.advance/latest`；`AuthzServerSecurityFilter` 构造器和过滤路径 | DTO→端口逐字段透传；一致性字符串；bulk 结果完整性；写/删水位；expand JSON；feature flag on/off；并发可见性；`/v1/**` 鉴权边界 |
| P1 | `auth-platform-admin` | `AdminController`：`grant`、`revoke`、`subjects`、`resources`、`schema`、`check`、`expand`、`relationships`、`auditLog`；`InMemoryAuditStore.record/recent` | TOUCH/DELETE 精确元组；JWT actor；审计调用时序；管理读强一致；userset；边界 limit；容量及并发 |
| P1 | `auth-platform-admin` | `CasdoorClient`：构造器、`groupMembers`、`groupNames`、`departmentSnapshot`；`CasdoorProperties.effectiveOrganizations` | Basic auth、单/多组织、`id`/`name` subject、短组名/全路径、组树 parent、role→admin、缺字段/错误体/跨组织引用 |
| P1 | `auth-platform-admin` | `ReconcileJob.reconcile`；旁路入口 `CasdoorSyncController.sync/syncDepartments/webhook` | 组同步与可选部门同步的调用顺序/异常传播；feature flag 缺 bean；webhook secret 与未启用分支 |
| P1 回归 | `auth-platform-admin` | `GroupSyncService.sync`、`DepartmentSyncService.sync`、`CasdoorGroupIds.encode`、`JdbcAuditStore.record/recent` | 已有差量/直接关系/删除熔断/租户编码/H2 持久化测试；补阈值等号、过滤器精确性、删除实体后的陈旧关系风险审查 |
| P2 | `auth-platform-protocol` | `ResourceRef.of/ref`；`SubjectRef.user/of/ofRelation`；`Relationship`；`RelationshipUpdate.touch/create/delete`；`RelationshipFilter.ofResource/of`；`Consistency.minimizeLatency/fullyConsistent/atLeastAsFresh`；`ZedTokenView` | 工厂映射和值语义；非法 null/blank；AT_LEAST token 约束。当前 record 紧凑构造器均未做参数校验 |

注：仓库中不存在 `TenantContext`，也没有可审计的 ThreadLocal 租户上下文。不能为了套用约定而虚构相关类；本轮租户隔离落在 Casdoor organization→SpiceDB object id 的编码及跨组织引用边界。

## 现有相关测试

| 测试位置 | 已有重点 | 明显未覆盖 |
|---|---|---|
| `auth-platform-sdk/src/test/java/com/lrj/authz/sdk/RemoteAuthzEngineTest.java` | `parseCheckBulk` 基数、乱序、重复、陌生资源、resource/allowed 缺失与类型错误 | 单 `check` 的私有 `requireAllowed` 实际 HTTP 路径；请求一致性/Bearer；其余 7 方法响应边界 |
| `auth-platform-server/src/test/java/com/lrj/authz/server/AuthzControllerWatermarkTest.java` | fresh 无 token：无水位回 full、写后用水位、caller token 优先、flag off | delete 推水位、其余 consistency mode、全部 facade、bulk map 缺项、写 token 异常、并发 |
| `auth-platform-server/src/test/java/com/lrj/authz/server/AuthzServerSecurityFilterTest.java` | flag off、非 `/v1`、缺/错/正确 token、空配置 fail-fast | `Bearer` 语法边界、`/v1` 精确路径、chain 是否确实未执行、401 body/content type |
| `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/GroupSyncServiceTest.java` | 增删差量、忽略嵌套 group、删除熔断、幂等 | threshold 等号、读取过滤器精确值、Casdoor 已删除 group 的陈旧 tuple |
| `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/DepartmentSyncServiceTest.java` | 新树 member/parent/admin、删除熔断 | 幂等、换 parent、删 parent/admin、过滤错误 relation、threshold 等号 |
| `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/CasdoorGroupIdsTest.java` | 组织前缀、碰撞、非法字符 | 大小写是否应归一（业务待验证） |
| `auth-platform-admin/src/test/java/com/lrj/authz/admin/JdbcAuditStoreTest.java` | 自建表、写读、actor、limit、retention、重开 | 并发 retention（非本轮 P0） |
| 无 | core、AOP、完整 server/admin controller、Casdoor HTTP、ReconcileJob、InMemoryAuditStore、protocol | 全部为缺口 |

## 拟落地测试类与精确位置

代码草案全部在 `TEST_PLAN.md`，不在本轮写入以下路径：

1. `auth-platform-core/src/test/java/com/lrj/authz/core/SpiceDbAuthzEngineTest.java`
2. `auth-platform-sdk/src/test/java/com/lrj/authz/sdk/CheckAccessAspectTest.java`
3. `auth-platform-sdk/src/test/java/com/lrj/authz/sdk/RemoteAuthzEngineHttpTest.java`
4. `auth-platform-server/src/test/java/com/lrj/authz/server/AuthzControllerFacadeTest.java`
5. `auth-platform-server/src/test/java/com/lrj/authz/server/ZedTokenWatermarkTest.java`
6. `auth-platform-server/src/test/java/com/lrj/authz/server/AuthzServerSecurityFilterBoundaryTest.java`
7. `auth-platform-admin/src/test/java/com/lrj/authz/admin/AdminControllerTest.java`
8. `auth-platform-admin/src/test/java/com/lrj/authz/admin/InMemoryAuditStoreTest.java`
9. `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/CasdoorClientTest.java`
10. `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/ReconcileJobTest.java`
11. `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/CasdoorSyncControllerTest.java`
12. `auth-platform-admin/src/test/java/com/lrj/authz/admin/casdoor/SyncServiceBoundaryTest.java`
13. `auth-platform-protocol/src/test/java/com/lrj/authz/protocol/ProtocolValueObjectsTest.java`

## 运行命令

模块回归（共享库均带 `-am`）：

```bash
mvn -pl auth-platform-protocol -am test
mvn -pl auth-platform-core -am test
mvn -pl auth-platform-sdk -am test
mvn -pl auth-platform-server -am test
mvn -pl auth-platform-admin -am test
mvn test
```

聚焦单类（必须从 reactor 根目录执行并带 `-pl`）：

```bash
mvn -pl auth-platform-core -Dtest=SpiceDbAuthzEngineTest test
mvn -pl auth-platform-sdk -Dtest=CheckAccessAspectTest test
mvn -pl auth-platform-sdk -Dtest=RemoteAuthzEngineHttpTest test
mvn -pl auth-platform-server -Dtest=AuthzControllerFacadeTest test
mvn -pl auth-platform-server -Dtest=ZedTokenWatermarkTest test
mvn -pl auth-platform-server -Dtest=AuthzServerSecurityFilterBoundaryTest test
mvn -pl auth-platform-admin -Dtest=AdminControllerTest test
mvn -pl auth-platform-admin -Dtest=InMemoryAuditStoreTest test
mvn -pl auth-platform-admin -Dtest=CasdoorClientTest test
mvn -pl auth-platform-admin -Dtest=ReconcileJobTest test
mvn -pl auth-platform-admin -Dtest=CasdoorSyncControllerTest test
mvn -pl auth-platform-admin -Dtest=SyncServiceBoundaryTest test
mvn -pl auth-platform-protocol -Dtest=ProtocolValueObjectsTest test
```

当前 POM 待办：`auth-platform-protocol/pom.xml` 没有任何 test dependency；`ProtocolValueObjectsTest` 落地前必须先获准为该模块增加 `spring-boot-starter-test`（test scope），否则不是代码草案错误，而是模块没有 JUnit/AssertJ classpath。其余四个模块已有 `spring-boot-starter-test`。这些纯单测和本地 loopback HTTP stub 不使用内部 JWT，故无需 `INTERNAL_JWT_SECRET`；若未来补应用启动冒烟测试，按约定设置至少 32 字节。
