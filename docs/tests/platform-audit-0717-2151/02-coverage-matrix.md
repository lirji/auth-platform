# 02 — 覆盖矩阵

状态：✅ 已有测试直接覆盖；🟨 已有但不完整；❌ 缺口、由所列草案补；⚠️ 当前行为疑似错误，只写 TODO/问题复现，不锁定（见 `03-suspected-issues.md`）。

## auth-platform-core — SpiceDbAuthzEngine

| 方法/分支 | 场景与关键断言 | 状态 | 对应测试 |
|---|---|---:|---|
| 两个构造器/HTTP | loopback endpoint、Bearer、JSON content type、timeout 构造不报错 | ❌ | `SpiceDbAuthzEngineTest.checkSerializesUsersetAndFreshness`（同时覆盖四参数构造器）；默认构造器冒烟待补 |
| `check` | HAS_PERMISSION→true；NO_PERMISSION→false | ❌ | `...checkSerializesUsersetAndFreshness`、`...checkReturnsFalseForNoPermission` |
| `check` | 缺/null/空 permissionship、非法 JSON、HTTP 4xx/5xx | ❌ | `...checkRejectsMissingPermissionship`、`...rejectsMalformedJsonAndHttpError` |
| `check` 一致性 | null/minimize/full/at-least token 的精确 JSON | ❌ | `...checkSerializesUsersetAndFreshness`、`...serializesOtherConsistencyModes` |
| `checkBulk` | null/空资源不发 HTTP | ❌ | `...emptyBulkDoesNotCallServer` |
| `checkBulk` | 顺序映射、true/false、userset | ❌ | `...bulkMapsPairsInRequestOrder` |
| `checkBulk` | pairs 非数组/少/多；item 缺 permissionship；pair error | ❌ | `...bulkRejectsWrongCardinalityAndPerItemError`（参数化思路，草案拆断言） |
| `checkBulk` | 重复 ResourceRef | ⚠️ | map 天然合并 key，契约是否禁止重复待验证 |
| `lookupResources` | 拼接 JSON/换行 NDJSON、多结果、conditional 排除 | ❌ | `...lookupResourcesParsesConcatenatedJson` |
| `lookupResources` | 缺 permissionship、顶层 error、缺/null id | ⚠️ | TODO(issue-C01/C02) |
| `lookupSubjects` | 多结果、subject type、空流 | ❌ | `...lookupSubjectsParsesStream` |
| `lookupSubjects` | error/permissionship/空 id/excluded ids 语义 | ⚠️ | TODO(issue-C02)，`excludedSubjectIds` 具体协议待验证 |
| `writeRelationships` | CREATE/TOUCH/DELETE enum、resource/relation/userset、返回 token | ❌ | `...writeRelationshipsSerializesOperationsAndRequiresToken` |
| `writeRelationships` | null/空 updates；缺 token/空 2xx | ⚠️ | TODO(issue-C03/C04/ADM02) |
| `deleteRelationships` | resourceType 必有；resourceId/relation null 时省略、非空时发送；token | ❌ | `...deleteRelationshipsHonorsOptionalFilterFields` |
| `deleteRelationships` | filter null/字段 blank/缺 token | ⚠️ | TODO(issue-C04/ADM02) |
| `readSchema` | schemaText 原样返回 | ❌ | `...readsSchemaAndExpandTree` |
| `readSchema` | 缺/空 schemaText | ⚠️ | TODO(issue-C03) |
| `expand` | resource/permission/consistency 请求，原始 JSON 返回 | ❌ | `...readsSchemaAndExpandTree` |
| `expand` | 空 2xx / JSON scalar 是否允许 | ⚠️ | TODO(issue-C03)，树 schema 待验证 |
| `readRelationships` | 强制 fullyConsistent、filter、direct subject、userset optionalRelation | ❌ | `...readRelationshipsParsesDirectAndUsersetTuples` |
| `readRelationships` | error/缺内部字段/空流 | ⚠️ | TODO(issue-C02/C03) |
| `post/postStream` | 非 JSON抛协议异常；HTTP status 非 2xx 抛 | ❌ | `...rejectsMalformedJsonAndHttpError` |

## auth-platform-sdk

| 类/方法 | 场景与关键断言 | 状态 | 对应测试 |
|---|---|---:|---|
| `CheckAccessAspect.around` | allow：主体、permission、resource 精确；minimize；目标只执行一次并返回原值 | ❌ | `CheckAccessAspectTest.allowedProceedsWithResolvedNamedParameter` |
| 同上 | `fullyConsistent=true` 精确传递 | ❌ | `...fullyConsistentAnnotationUsesFullMode` |
| 同上 | deny 抛 `AccessDeniedException`，目标零调用 | ❌ | `...deniedNeverInvokesTarget` |
| 同上 | resolver/engine 异常传播，目标零调用 | ❌ | `...engineFailureNeverInvokesTarget` |
| `resolveParam` | 多参数按名字而非位置；找不到名字 | ❌ | `...allowedProceedsWithResolvedNamedParameter`、`...missingParameterNameFailsBeforeCheck` |
| `resolveParam` | names=null、args 长度不一致、值 null/blank | ⚠️ | TODO(issue-A01) |
| 注解 | runtime retention、method target、默认 `fullyConsistent=false` | 🟨 | 由反射取真实 annotation 间接覆盖；可另补元测试 |
| SpEL/下标 | `#request.id`、`arg[0]` | ⚠️ | 当前不存在该能力，见 issue-A02 |
| `RemoteAuthzEngine.parseCheckBulk` | 合法、乱序、少项、空、陌生/重复、缺 resource、allowed 缺/null/string/number | ✅ | 既有 `RemoteAuthzEngineTest` 11 cases |
| `RemoteAuthzEngine.check` | allowed true/false；缺/错类型 fail-closed | ❌ | `RemoteAuthzEngineHttpTest.singleCheckRequiresBooleanAllowed` |
| SDK HTTP | Bearer on/off、Consistency mode/token、空 bulk 不发请求 | ❌ | `RemoteAuthzEngineHttpTest` |
| 其余端口 | lookup、write/delete token、schema、expand、read relationships 正常映射 | ❌ | P2；建议沿用 core loopback 模式扩充 |
| 其余端口响应缺字段 | 当前多为静默空值 | ⚠️ | 与 issue-C03/C04 同类，SDK 防御策略待验证 |

## auth-platform-server

| 类/方法 | 场景与关键断言 | 状态 | 对应测试 |
|---|---|---:|---|
| `AuthzController.check` | DTO 完整透传；null/blank mode=minimize；full/fully_consistent（大小写）；未知 mode fallback minimize | 🟨 | 水位测试只覆盖 fresh；新增 `AuthzControllerFacadeTest.checkMapsConsistencyModesAndArguments` |
| `checkBulk` | 请求顺序输出、引擎 false/true | ❌ | `...bulkPreservesRequestOrder` |
| `checkBulk` | map 缺项/额外/null、重复请求 | ⚠️ | TODO(issue-S01) |
| `lookupResources` | subject/permission/type/consistency 和返回值 | ❌ | `...delegatesLookupMethodsWithFullConsistency` |
| `lookupSubjects` | resource/permission/type/consistency 和返回值 | ❌ | 同上 |
| `write` | engine updates、token response、水位推进 | ✅/🟨 | 既有写后 fresh；新增 facade 精确捕获 |
| `delete` | filter、token response、水位推进 | ❌ | `...writeAndDeleteAdvanceWatermark` |
| `schema` | 原样 map | ❌ | `...schemaExpandAndReadRelationshipsDelegate` |
| `expand` | 解析 object/array；非法 JSON 包装异常 | ❌ | 同上、`...expandRejectsMalformedEngineJson` |
| `readRelationships` | filter 与返回列表 | ❌ | 同上 |
| `toConsistency` | caller token、watermark on/off、无水位 full | ✅ | `AuthzControllerWatermarkTest` 4 cases |
| `toConsistency` | whitespace token、mode case、unknown | ❌ | facade 草案 |
| 写/删 token 缺失 | 应拒绝还是透传 | ⚠️ | TODO(issue-C04) |
| `ZedTokenWatermark` | 初始 null；null/blank 不覆盖；正常 advance | ❌ | `ZedTokenWatermarkTest.ignoresBlankAndPublishesNonBlankToken` |
| 水位并发 | 并发可见、值不撕裂、不超出提交集合 | ❌ | `...concurrentAdvancePublishesACompleteSubmittedToken` |
| 水位并发单调 | 旧请求晚返回覆盖新 token | ⚠️ | issue-S02，不能写 last-writer=latest 的错误断言 |
| security flag off/on | 放行；非 v1；正确/错误/缺 token；空配置 | ✅ | 既有 6 cases |
| security 401 | chain 未执行、content type/body、不接受 Basic/小写 bearer/多余空格 | ❌ | `AuthzServerSecurityFilterBoundaryTest` |
| security URI | `/v1`、`/v1evil`、双斜线/编码路径 | ⚠️/❌ | `/v1` issue-SEC01；其余 boundary test，容器规范化待 IT |

## auth-platform-admin

| 类/方法 | 场景与关键断言 | 状态 | 对应测试 |
|---|---|---:|---|
| `AdminController.grant` | TOUCH、精确 tuple/userset、token、JWT name actor、audit detail | ❌ | `AdminControllerTest.grantWritesTouchAndAuditsNamedActor` |
| `revoke` | DELETE、blank subjectRelation→null、JWT subject fallback | ❌ | `...revokeWritesDeleteAndFallsBackToJwtSubject` |
| grant/revoke 异常 | engine 异常不审计 | ❌ | `...writeFailureIsNotAudited` |
| 审计异常 | 数据面已写、API 失败 | ⚠️ | TODO(issue-ADM01) |
| `subjects` | full consistency、subjectType、映射丢 relation（按 DTO 设计） | ❌ | `...adminReadsAlwaysUseFullConsistency` |
| `resources` | full consistency及参数 | ❌ | 同上 |
| `schema` | map 原样 | ❌ | `...schemaRelationshipsAndAuditDelegate` |
| `check` | full consistency、allow/deny | ❌ | `...checkUsesFullConsistency` |
| `expand` | full consistency、忽略 subject（当前文档明确）、JSON、非法 JSON | ❌ | `...expandParsesJsonAndRejectsMalformedTree` |
| `relationships` | null optional filter 精确传递 | ❌ | `...schemaRelationshipsAndAuditDelegate` |
| `auditLog` | limit 精确传递 | ❌ | 同上 |
| Admin DTO 无效输入 | null/blank/type/relation/permission | ⚠️ | issue-ADM02 |
| `InMemoryAuditStore` | capacity floor=1、最新优先、裁剪、actor null、limit floor | ❌ | `InMemoryAuditStoreTest` 3 cases |
| `InMemoryAuditStore` 并发 | 不抛/不超 capacity/记录不破损 | ❌ | `...concurrentWritersNeverExposeMoreThanCapacity` |
| `InMemoryAuditStore` 并发精确保留 N | 复合原子性 | ⚠️ | issue-AUD01；等待确定性 hook/实现修复 |

## Casdoor 与同步

| 类/方法 | 场景与关键断言 | 状态 | 对应测试 |
|---|---|---:|---|
| `CasdoorProperties.effectiveOrganizations` | organizations 非空优先；空回退 organization；null 回退 | ❌ | `CasdoorClientTest.effectiveOrganizationsUsesListOrFallback` |
| `CasdoorClient` HTTP | Basic auth、owner query、非法 JSON/4xx | ❌ | `CasdoorClientTest` loopback stub |
| `groupMembers` | id/name subject、短名、全路径、多 org 去碰撞、去重、空 subject 跳过 | ❌ | `...groupMembersScopesAndDeduplicatesAcrossOrganizations` |
| `groupNames` | owner fallback、多 org、blank name 跳过 | ❌ | `...groupNamesUsesOwnerAndSkipsBlankNames` |
| `departmentSnapshot` | deptIds/member/parent/admin、非 admin role 忽略、未知用户名忽略 | ❌ | `...departmentSnapshotBuildsTreeAndAdmins` |
| Casdoor 缺 data/空 body | 应失败而非空快照 | ⚠️ | TODO(issue-CAS01) |
| 跨组织完整引用 | 应拒绝还是允许 | ⚠️ | TODO(issue-CAS03) |
| query 编码 | 特殊 organization | ⚠️ | issue-CAS04 |
| `GroupSyncService` | add/remove、nested 排除、熔断 > threshold、幂等 | ✅ | 既有 4 cases |
| Group sync 边界 | delete == threshold 允许；read filter 精确；touch 在 delete 前 | ❌ | `SyncServiceBoundaryTest.groupDeleteAtThresholdIsAllowedAndFilterIsScoped` |
| 删除整个 group | 旧 tuple 清理 | ⚠️ | issue-CAS02，现接口不可实现该测试的期望 |
| `DepartmentSyncService` | 新树、熔断 | ✅ | 既有 2 cases |
| Department 边界 | parent 替换、member/admin 删除、幂等、filter 精确 | ❌ | `SyncServiceBoundaryTest.departmentParentReplacementIsAtomicInOneWrite` |
| 删除整个 dept | 旧 tuple 清理 | ⚠️ | issue-CAS02 |
| `ReconcileJob` | group then optional department；department absent；异常传播/停止 | ❌ | `ReconcileJobTest` 3 cases |
| Reconcile 原子性 | 第二阶段失败导致半同步 | ⚠️ | issue-R01 |
| `CasdoorSyncController.sync` | provider absent 409；present 200 | ❌ | `CasdoorSyncControllerTest` |
| `syncDepartments` | provider absent/present | ❌ | 同上 |
| webhook secret | 配置非空时缺/错 401；正确触发 group+可选 dept | ❌ | 同上 |
| webhook secret flag off | 空 secret 当前放行 | ❌ | 同上；仅开发默认是否可接受待部署验证 |
| `CasdoorGroupIds.encode` | 正常/碰撞/非法 | ✅ | 既有 7 cases |
| `JdbcAuditStore` | H2 建表/CRUD/retention | ✅ | 既有 4 cases（现用 PostgreSQL mode；铁律新测试要求 MySQL mode，与该实现 DDL 兼容性待验证） |

## auth-platform-protocol

| 值对象/工厂 | 合法值映射与派生行为 | 非法输入 | 状态/测试 |
|---|---|---|---|
| `ResourceRef.of/ref` | type/id 保留，`type:id` | null/blank/type 含分隔符 | 合法：❌ `ProtocolValueObjectsTest.resourceFactoryAndRef`; 非法：⚠️ issue-ADM02 |
| `SubjectRef.user/of/ofRelation` | user 默认 type，relation null/userset 保留 | null/blank relation/type/id | 合法：❌ `...subjectFactoriesPreserveUserset`; 非法：⚠️ |
| `Relationship` | record 值语义 | 任一字段 null/blank | 合法：❌ `...relationshipHasValueSemantics`; 非法：⚠️ |
| `RelationshipUpdate` 三工厂 | operation 精确 | null operation/tuple 字段 | 合法：❌ `...updateFactoriesSelectExactOperation`; 非法：⚠️ |
| `RelationshipFilter` | `ofResource` relation=null；`of` 保留通配 | resourceType null/blank、仅 relation | 合法：❌ `...filterFactoriesPreserveWildcards`; 非法：⚠️ |
| `Consistency` 三工厂 | mode/token 精确 | AT_LEAST null/blank；其它 mode 带 token | 合法：❌ `...consistencyFactoriesEncodeModes`; 非法：⚠️ |
| `ZedTokenView` | token 值语义 | null/blank | 合法：❌ `...zedTokenHasValueSemantics`; 非法：⚠️ |

构建缺口：protocol 当前无 JUnit/AssertJ test dependency，详见 issue-BLD01；不把依赖缺失误标成已覆盖。
