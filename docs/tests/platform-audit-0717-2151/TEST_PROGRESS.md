# TEST_PROGRESS — platform-audit（2026-07-17）

## 决策（用户拍板）
- 疑似 bug 处置：**A** —— 按现状写"锁定当前行为"的测试并在注释标 `TODO(issue-)`，本轮不改生产代码。
- protocol 测试依赖：**加 `spring-boot-starter-test`**（test-scope）到 `auth-platform-protocol/pom.xml`。
- 落地范围：**全部第一批（13 个测试类）**。

## 改动清单
- `auth-platform-protocol/pom.xml`：新增 test-scope `spring-boot-starter-test`（生产依赖仍为零）。
- 新增 13 个测试类（同包路径）：
  - protocol：`ProtocolValueObjectsTest`（7）
  - core：`SpiceDbAuthzEngineTest`（13，此前该模块**零测试**）
  - sdk：`CheckAccessAspectTest`（5）、`RemoteAuthzEngineHttpTest`（2）
  - server：`AuthzControllerFacadeTest`（6）、`ZedTokenWatermarkTest`（2）、`AuthzServerSecurityFilterBoundaryTest`（2）
  - admin：`AdminControllerTest`（7）、`InMemoryAuditStoreTest`（3）、`CasdoorClientTest`（6）、`ReconcileJobTest`（3）、`CasdoorSyncControllerTest`（5）、`SyncServiceBoundaryTest`（2）

## 跑测结果
- `./mvnw test`：**BUILD SUCCESS**。
  - protocol 7 / core 13 / sdk 18（含既有 11）/ server 20（含既有 10）/ admin 43（含既有 17）。
  - 合计 **101 tests, 0 failures, 0 errors**（基线 38 → 新增 63）。
- 并发测试稳定性：`InMemoryAuditStoreTest` + `ZedTokenWatermarkTest` 连跑 5 轮，5/5 全绿，无抖动。
- 迭代轮次：一次编译 + 一次全量跑测即全绿，无需修复（草案与真实签名一致）。

## 发现并上报的疑似 bug（本轮均未改生产，测试只锁定当前行为 + TODO）
真问题（值得后续修）：C01（lookupResources 空 permissionship 当放行）、CAS02（Casdoor 删组/删部门后旧 tuple 残留＝撤权不彻底）、CAS01（Casdoor 空/畸形 200 被当空快照，阈值内撤成员）、S01（server bulk facade 把引擎漏项静默降级 deny）、ADM01（先写 SpiceDB 后审计，审计失败则数据已变而无审计）、C02（流式端点 error 项静默跳过）。
详见 `03-suspected-issues.md`；测试里以 `TODO(issue-)` 标记，未固化为"正确行为"。

## 阶段五：Codex 独立验收（有条件通过）
只读 Codex 复审新增 13 类：约定遵守良好（纯 POJO + 手工 mock + 回环 stub，无 Spring context/TenantContext，H2 用 PostgreSQL mode），未删弱既有测试。据其意见我已采纳并修正：
- 补 TODO 标记两处"锁定现状但未标门禁"的安全敏感断言：`AuthzControllerFacadeTest` 未知 consistency mode 静默降级 minimize_latency（新记 issue-consistency-downgrade）、`CasdoorSyncControllerTest` 空 webhook-secret 裸奔（新记 issue-webhook-open）。
- 强化弱断言：`AdminControllerTest.revoke` 补断返回 token `zed-2`、`subjects` 补断 type、`CasdoorSyncControllerTest` webhook 补断 summary/departments 值、`RemoteAuthzEngineHttpTest` 补断 HTTP path `/v1/check`。
- 两个并发测试 `Future.get()` 加 10s 超时（回归成死锁时快速失败而非挂住构建）。
- 稳定性：`InMemoryAuditStoreTest` + `ZedTokenWatermarkTest` 累计 **20 轮全绿**。
- 修正后复跑 `./mvnw test` 仍 **BUILD SUCCESS，101 tests 全绿**。

Codex 指出的覆盖缺口（SDK lookup/write/schema HTTP 映射、core lookup/delete/expand 完整 path 校验、Casdoor 4xx、revoke 失败/check deny、syncDepartments 成功分支、AOP resolver 异常）我认同，均属计划 §4 第二批范围，本轮 A 决策不扩围，记入下方待办。

## 第二批已落地（happy-path/分支扩围，均未改生产，续 A 决策只锁现状）
`./mvnw test` **BUILD SUCCESS，113 tests**（101 → +12）。新增用例扩进既有测试类：
- `RemoteAuthzEngineHttpTest`（+3）：lookup/write/delete/schema/expand/read 六操作 HTTP path+body+映射契约、check=false、无 token 不带 Authorization 头。
- `SpiceDbAuthzEngineTest`（+4）：bulk 非数组/多项/item 缺 permissionship 三种基数&字段校验、lookupResources/lookupSubjects 请求字段+path、delete/expand path 校验（含空流返回空）。
- `CheckAccessAspectTest`（+1）：`SubjectResolver` 抛异常时 engine.check/target 零副作用。
- `CasdoorClientTest`（+1）：4xx/5xx 抛 `RestClientResponseException`（非 200 不被静默当空快照）。
- `AdminControllerTest`（+2）：revoke 引擎失败不审计、check deny 返回 false。
- `CasdoorSyncControllerTest`（+1）：`syncDepartments()` 成功分支返回 summary。

## 第三批（B 方案）：实修 fail-closed 校验家族 + 端到端验证
用户改选 B，实修 4 条与项目"严格响应校验/fail-closed"铁律同向的真问题（均只在响应畸形时触发，真实 SpiceDB happy-path 不变）：
- **C01** `SpiceDbAuthzEngine.lookupResources`：空 permissionship 由"当放行"改为**抛协议异常**（CONDITIONAL/UNSPECIFIED 仍 fail-closed 排除）。
- **C02** `SpiceDbAuthzEngine.postStream`：流中任一顶层 `error` 消息即抛（覆盖 lookupResources/lookupSubjects/readRelationships），不再返回可信部分结果。
- **C04** `SpiceDbAuthzEngine.writeRelationships/deleteRelationships`：缺 `writtenAt/deletedAt.token` 抛异常（新增 `requireToken`），不再静默返回 `ZedTokenView(null)`。
- **S01** `AuthzController.checkBulk`：引擎 map 缺某请求资源即抛，不再 `Boolean.TRUE.equals(null)` 静默降级 deny。

对应 4 条测试 TODO 已转为锁定"正确行为"的断言（core `lookupResourcesRejectsMissingPermissionship`/`streamTopLevelErrorFailsWholeCall`/`writeAndDeleteRejectMissingToken`、server `bulkThrowsWhenEngineOmitsRequestedResource`）。

**未修（需设计决策，本轮不做机械修复）**：CAS02（删组/删部门后旧 tuple 残留——需 manifest/tombstone 或按租户枚举）；CAS01/CAS03/CAS04、ADM02/AUD01/SEC01/consistency-downgrade/webhook-open 仍 TODO 门禁保留。

## 第四批：ADM01 两段审计（用户选 B：意图+结果）
`AdminController.grant/revoke` 重构为 `writeWithAudit`：写前落 `<action>.intent`（**不捕获**——审计库不可用则 fail-closed 不写 SpiceDB，符合"审计是安全记录、拒绝静默降级"）→ 写 SpiceDB → 成功落 `<action>.ok` / 失败经 `recordQuietly` 落 `<action>.fail`（best-effort，不掩盖原始写异常）后重抛。**保证任何数据面变更前必有一条 intent 记录**，杜绝"变更却无审计"，也不产生"未变更却记 ok"。
- 测试更新（AdminControllerTest，仍 9 tests 全绿）：`grantWritesTouchAndAuditsIntentThenOk`（InOrder 断言 intent→write→ok，且不落 fail）、`revokeWritesDeleteAndFallsBackToJwtSubject`（intent→write→ok）、`grantWriteFailureAuditsIntentAndFailButNotOk`（写失败留 intent+fail、绝不落 ok）、`revokeAbortsWriteWhenIntentAuditFails`（intent 审计失败则不写 SpiceDB）。ADM01 的 TODO 已移除。
- 验证：`./mvnw test` **BUILD SUCCESS，117 tests**。ADM01 的失败/中止语义只能靠注入审计异常触发（上述 mock 单测已覆盖）；happy-path 的 admin 端到端需 Casdoor admin JWT + 持久化审计库，设置成本高、且触不到失败路径，故以单测为准（server 侧 C01/C04/S01 已另做真链路冒烟）。

**验证**：
- `./mvnw test`：**BUILD SUCCESS，117 tests**（B 批 +4 回归；既有同步器/SDK 测试无回归）。
- **端到端冒烟**（另起临时 server :8290 关鉴权、指向运行中的 SpiceDB，未动 8200 上的 enforce 实例）：
  - `spicedb-smoke.sh`（Phase 0）全绿（含 LookupResources）。
  - `server-smoke.sh`（Phase 1，经完整 REST 链路）：check-bulk（验 S01）、写+`at_least_as_fresh` 立即生效+撤销（验 C04 写返回有效 token）、lookup-subjects 均 **PASS**。
  - 唯一 FAIL `bob view smk_da (组->space editor 继承)` 是**脚本陈旧断言**：当前 `document.view` 已无 `parent_space` 项（部门模型切换时移除，见 CLAUDE.md），空间→文档 view 继承本就不该成立，与本次改动无关（check 路径未改）。

## 遗留待办（后续，需先修 issue 或架构决策）
- 修 C01/C02/C03/C04/S01/A01/CAS01/CAS03/CAS04 等生产 issue 后，启用各测试里 `TODO(issue-)` 标记的 fail-closed 回归。
- ADM02（DTO/record 非空约束）、AUD01（ring 复合原子）、SEC01（精确 `/v1` 保护面）、consistency-downgrade、webhook-open 待决策后补测/修复。
- S02、R01 为已知设计限制，测试仅锁定原子可见 / 编排顺序，不断言强语义。
- 修 C01/C02/C03/C04/S01 后，启用 core lookup/stream/token 严格化与 server bulk 完整性回归。
- 修 A01 后补 AOP null metadata / null 参数 fail-closed 测试。
- 修 CAS01/CAS03/CAS04 后补 Casdoor schema 校验 / 跨 org fail-closed / query 编码测试。
- ADM02（DTO/record 非空约束）、AUD01（ring 复合原子）、SEC01（精确 `/v1` 保护面）待决策后补测。
- S02、R01 为已知设计限制，测试仅锁定原子可见 / 编排顺序，不断言强语义。
