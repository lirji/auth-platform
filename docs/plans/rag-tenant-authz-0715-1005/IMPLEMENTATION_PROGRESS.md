# IMPLEMENTATION_PROGRESS — RAG 多租户 + ReBAC 授权

> 恢复工作先读本文件 + `FINAL_PLAN.md`。逐阶段记录：做了什么、测试结果、是否满足验收。
> 跨两仓库：`langchain4j-platform`（业务）、`auth-platform`（IAM，本仓库）。
> **双 Maven 仓库坑**：改 auth-platform SDK/protocol 后须
> `./mvnw -pl auth-platform-protocol,auth-platform-sdk install -DskipTests -Dmaven.repo.local=/Users/liruijun/personal/repository`。

## 决策冻结
- **同租户默认可见性 = 选项①（同租户默认可见）**（2026-07-15 用户拍板）。backfill manifest 写
  `space:<t>_default#viewer@group:<t>_<membersGroup>#member`。
- 方案 = A（融合后 checkBulk 生产化）+ 合并硬化。核心机制已存在/已提交/跑在 shadow，实施=安全切 enforce + 前置硬化。

## 阶段进度

### Phase 1 — 数据结构与领域模型  ✅ 完成（2026-07-15）
范围（仅本阶段）：
- [x] knowledge-service 新增并校验 `RagAuthzProperties`（mode/candidateMultiplier/maxCandidates/bulkSize/strictTenantOnly），非法值启动失败（`InitializingBean.afterPropertiesSet`，未引入 validation 依赖）；经 `KnowledgeAuthzConfig` `@EnableConfigurationProperties` 无条件注册。application.yml 补默认值。**未改** query/filter 运行时行为。
- [x] auth-platform-admin 新增 group 租户化 codec `CasdoorGroupIds`（v1 `<org>_<group>`，字符集 `[A-Za-z0-9][A-Za-z0-9-]*`，歧义/含 `_`/`/` 输入 fail-closed 抛出），含碰撞单测。**未改** GroupSyncService 运行时行为。
- [x] 定义关系 backfill manifest 格式 → `backfill-manifest-format.md`（含 D3=① default-space 成员组 viewer 绑定），不新增 SQL。
- [x] category→folder 仅记决策（FINAL_PLAN §14），不实现。

完成标准（codex 阶段1）：✅ 非法 mode（枚举绑定失败）/上限（afterPropertiesSet）启动失败；✅ group id 单一构造入口 + 碰撞单测；✅ manifest 格式已定义可对账。

改动文件：
- langchain4j-platform：`knowledge-service/.../authz/RagAuthzProperties.java`(新)、`KnowledgeAuthzConfig.java`(改+@EnableConfigurationProperties)、`application.yml`(改+4 项默认)、`RagAuthzPropertiesTest.java`(新)。
- auth-platform：`auth-platform-admin/.../casdoor/CasdoorGroupIds.java`(新)、`CasdoorGroupIdsTest.java`(新)。

测试结果：
- `CasdoorGroupIdsTest`：**Tests run: 7, Failures: 0, Errors: 0**（`./mvnw -pl auth-platform-admin -am test`）。
- `RagAuthzPropertiesTest`：**Tests run: 6, Failures: 0, Errors: 0**（`mvn -pl knowledge-service -am test -Dmaven.repo.local=/Users/liruijun/personal/repository`）。
- 两模块 `-am` 编译通过（含改动的 main 类）。

注意/遗留：
- Phase 2 起才**消费** RagAuthzProperties 的上限（KnowledgeQueryService overfetch/取 max、RealKnowledgeAuthz 分批）并把 `GroupSyncService`/`CasdoorClient` 迁移到 `CasdoorGroupIds`。
- 未起完整 Spring 上下文验证（需 qdrant/ES）；`@EnableConfigurationProperties` 为标准生命周期，afterPropertiesSet 会被调用，逻辑已由单测覆盖。

### Phase 2 — 核心业务逻辑  ✅ 完成（2026-07-15）

**2a SDK checkBulk 严格校验**（auth-platform）
- `auth-platform-sdk/.../RemoteAuthzEngine.java`：`checkBulk` 抽出 `parseCheckBulk`——校验 results 是数组、基数=请求数、每个请求资源恰好一次、无未请求/重复/缺字段，任一违反抛 `IllegalStateException`（上游 enforce fail-closed）。不再按下标盲映射（旧逻辑漏项静默当 deny、乱序错位授权）。
- `auth-platform-sdk/pom.xml`：加 `spring-boot-starter-test`（test scope，原无测试基建）。
- 新增 `RemoteAuthzEngineTest`（7 项：正确映射、乱序对齐、截断/空/多项/重复/缺字段 fail-closed）。

**2b knowledge-service 检索硬化**（langchain4j）
- `es/ElasticsearchEsGateway.java`：抽出静态 `buildSearchBody`（租户 term 恒在，可测）+ `requireTenant` 空 tenant fail-fast（search/deleteByDoc）。
- `authz/RealKnowledgeAuthz.java`：`filterReadable` 按 `bulkSize` 分批 checkBulk + 合并；新增 `knowledge.authz.{candidates,allowed_docs,underfill,check_bulk.latency}` 指标；新增 `(engine,mode,meter,bulkSize)` 构造器（旧构造器默认 bulkSize=100，行为不变）。
- `KnowledgeQueryService.java`：请求期一次性快照 tenantId/userId；`computePoolLimit` 授权开启时有界 overfetch（倍数取 rerank 与 authz.candidate-multiplier 的 **max 非相乘**，封顶 max-candidates）；`filterReadable(tenantId,userId,candidates)`。注入 `RagAuthzProperties`。
- `authz/KnowledgeAuthzConfig.java`：shadow/enforce bean 传 `props.getBulkSize()`。
- 新增 `ElasticsearchEsGatewayTest`(5)、`KnowledgeQueryServiceAuthzTest`(4：去重成 1 资源、enforce 丢 deny/无 docId、disabled 不调用、shadow 不拦截)；扩 `RealKnowledgeAuthzTest`(+2 分批/容量指标)。

**2c admin group 租户化**（auth-platform）
- `casdoor/CasdoorClient.java`：`groupMembers`/`groupNames` 产出租户化 id `<org>_<group>`（经 `CasdoorGroupIds`），删除旧 `shortName`。
- `casdoor/GroupSyncService.java`：当前态改用 `readRelationships` 读 **direct tuple**（只认 `user`+relation=null，排除嵌套组间接成员的误删）；加**删除熔断**（超阈值中止整轮不写）。
- `casdoor/CasdoorProperties.java`(+deleteThreshold=1000)、`CasdoorConfig.java`（传阈值）。
- 新增 `GroupSyncServiceTest`(4：增删差量、嵌套组不误删、删除熔断中止不写、幂等不写)。

**测试结果**：auth-platform 全模块绿（SDK 7 + GroupSync 4 + codec 7 + server 既有 6…）；knowledge-service **全量 204 tests, 0 failures/errors/skipped（41 类）**。已把改过的 protocol+sdk 安装到 `/Users/liruijun/personal/repository`（双仓库坑）。

**与 FINAL_PLAN 的有意偏差（已分析）**：
1. **maxTopK 未单列**：候选上限统一由 `max-candidates` 封顶；且所有新上限**仅作用于授权开启路径**，disabled 路径候选池公式逐字不变（守住"disabled 逐字一致"）。
2. **容量/延迟指标放在 RealKnowledgeAuthz**（已有 MeterRegistry），未给 KnowledgeQueryService 注入 meter（减小改动面）。
3. **strict-tenant-only×public 互斥校验**下沉到 Phase 3（edge/配置层，属 enforce 期约束）；RagAuthzProperties 本阶段只自校验自身字段。
4. **多 organization 列表遍历延后**：本阶段实现单 org 的租户化（`<org>_<group>`），"跨租户同名组不合并"完成标准已满足；`organizations` 列表 + 多副本 lease 留待后续（需真实环境）。
5. CasdoorClient HTTP 层测试未加（编码由 `CasdoorGroupIdsTest` 覆盖，sync 逻辑由 `GroupSyncServiceTest` 覆盖）——列为 Phase 4 gap。

### Phase 3 — 接口与适配层  ✅ 主体完成（2026-07-15）

**3a edge 严格模式**（langchain4j edge-gateway，可单测）
- `CasdoorSecurityProperties.java`：加 `Mode{DUAL,ONLY}`（disabled 由 enabled=false 表达）。
- `CasdoorTokenExchangeFilter.java`：无 Bearer / 验签失败 → `onMissingOrInvalid`：DUAL 透传 legacy、**ONLY 直接 401**（不落 legacy）。tenant 恒取 owner。
- `CasdoorDecoderConfig.java`：启动校验 ONLY 模式下 `tenant-claim=owner`，否则拒绝启动。
- 扩 `CasdoorTokenExchangeFilterTest`（+3：ONLY 无 Bearer 401、ONLY 验签失败 401、ONLY 有效仍换发）。**edge 全量 25 tests, 0 失败, 2 skip**。

**3b 部署接线**（config-only，需起栈验证）
- `deploy/docker-compose.yml`：knowledge 注入 `RAG_AUTHZ_MODE/AUTHZ_SERVER_URL/AUTHZ_SERVER_TOKEN/RAG_AUTHZ_*`；edge 注入 `EDGE_CASDOOR_MODE`。
- `edge-gateway/application.yml`：`edge.casdoor.mode=${EDGE_CASDOOR_MODE:dual}`。
- `deploy/helm/platform/values.yaml`：config 补 `RAG_AUTHZ_MODE/AUTHZ_SERVER_URL/RAG_AUTHZ_*/EDGE_CASDOOR_MODE`；secrets 补 `AUTHZ_SERVER_TOKEN`（附债务注：生产应下沉 knowledge 专属 Secret + NetworkPolicy，FINAL_PLAN §6.3 #7）。默认全 disabled/dual。

**3c 脚本**
- `auth-platform/deploy/rag-authz-fixture.sh`：按 backfill-manifest（D3=① 成员组绑 default space viewer）为一个租户 seed SpiceDB 关系；**默认 dry-run**，APPLY=1 才写。已离线 dry-run 验证（7 元组形态正确）。
- `langchain4j/deploy/smoke-rag-tenant-authz.sh`：跨服务 required E2E 驱动（Casdoor→edge→knowledge→ES→AP→SpiceDB），断言租户隔离 + 文档级 ReBAC + ONLY 无 token 401；不可 skip、前置不满足即失败。已 `bash -n`。

**Phase 3 未完/延后（诚实记录）**：
- **strict-tenant-only × public.enabled 启动互斥校验**：本轮**未**落地（需在 `KnowledgeAuthzConfig` 读跨前缀 `app.rag.public.enabled`）→ 转 Phase 4/收尾补。
- **AP server `/v1/**` 安全开关的 compose 接线**：AP server 无应用 Dockerfile、不在 langchain compose；作为**运营配置**（`authz.server.security.enabled/token`）写进 E2E 脚本前置，未改 auth-platform compose。
- **NetworkPolicy 模板**：未加（helm 无此模板），以 values.yaml 注释记为债务。
- **E2E 实跑**：需起 enforce 全栈 → Phase 4。

**注意：langchain4j-platform 工作树含与本任务无关的既有未提交改动**（`KnowledgeQueryController`/`KnowledgeRuntimeView`/`RagRuntimeInfo` 的 "RagRuntime 运行时信息" + OIDC 前端），非本次所改；已在 204-test 全量中同绿。提交时需与用户确认拆分。

### Phase 4 — 测试  🟡 部分完成（2026-07-15）
- ✅ **strict-tenant-only × public 启动互斥校验补齐**：`KnowledgeAuthzConfig.validateStrictConsistency`（strict=true 时 public.enabled 必 false 且 mode≠disabled，冲突拒绝启动）+ 无条件 `InitializingBean` bean。新增 `KnowledgeAuthzConfigTest`(4)。
- ✅ **活栈核心验证（SpiceDB :8543 + AP server :8200）**：`rag-authz-fixture.sh APPLY=1` seed acme/beta 后，SpiceDB `check` 6/6 断言符合——同租户成员经 D3① 路径可见、陌生人拒、**跨租户一律拒**；AP server `/v1/check-bulk` 返回形状（results[] 回带 resource+allowed、基数一致）与 SDK `parseCheckBulk` 要求兼容、判定正确。
- ✅ **全链容器 E2E 通过（重建镜像 + 真实容器）**：本机打包 knowledge/edge jar（含新代码+新 SDK）→ `docker compose build` → 切 `RAG_AUTHZ_MODE=enforce` / `EDGE_CASDOOR_MODE=only` 重启。
  - **Proof 1 edge ONLY**：edge(:18080) 无 token `POST /rag/query` → **401**（不落 legacy）。
  - **Proof 2 knowledge enforce**：注入 HS256 内部 JWT（edge 会注入的同款）直打 knowledge(:8084)，同一查询「退款政策 无理由退款 生鲜」：alice(acme 成员)→命中 **`a66563a5a4528e01`**（用户原始 ES 文档）；stranger(非成员)→**空**。证明 ES 租户 filter→enforce checkBulk→真实 AP(:8200)→真实 SpiceDB→D3① 可见 + ReBAC 过滤全链，且 2597 acme 文档只放行有 view 的 1 条（underfill 有界符合设计）。
  - **未经 live Casdoor 令牌走 edge→knowledge 那一跳**（需 Casdoor org/user + sub-UUID 与 SpiceDB 对齐的重活）：以 edge ONLY 401 + 内部 JWT（edge 注入物）+ 换发逻辑单测 共同覆盖。
- ✅ **已把运行栈恢复安全态**：knowledge=disabled、edge=dual（health 200，与改前逐字一致）；新镜像在 disabled/dual 下行为不变。
- ⬜ **未做**：故障注入（AP/SpiceDB 不可达）、性能压测；live Casdoor 令牌全流程。
- ⚠️ 活栈 SpiceDB 留有测试关系（acme：alice/bob 成员+`a66563a5a4528e01`/`d_99`+D3①绑定；beta：carol+`b_77` 纯测试）；幂等 TOUCH，可保留或清理（`deleteRelationships` by resource）。

### Phase 5 — 文档与最终检查  ⬜ 未开始
### Phase 3 — 接口与适配层  ⬜ 未开始
### Phase 4 — 测试  ⬜ 未开始
### Phase 5 — 文档与最终检查  ⬜ 未开始
