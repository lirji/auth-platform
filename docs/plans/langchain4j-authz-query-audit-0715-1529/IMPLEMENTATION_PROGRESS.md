# 实施进度 — langchain4j 授权查询审计·方案 A

> 依据 `FINAL_PLAN.md`（含 §16 Claude 复核补充）。用户 2026-07-15 批准「全量按方案 A 实施」。
> 恢复工作先读本文件。分阶段：① 数据结构/版本冻结 → ② 核心逻辑(SDK F3) → ③ 接口/门禁(F1/F2) → ④ 测试 → ⑤ 文档/终检。

## 阶段 1 — 版本冻结与前置核实（§16.2）

- [x] 核实 langchain4j 消费的 SDK 版本：`knowledge-service/pom.xml` → `auth-platform-sdk:0.1.0-SNAPSHOT`。
- [x] 核实自定义仓库 `/Users/liruijun/personal/repository` 已安装 jar **含严格 `parseCheckBulk`**（`unzip -p ... | strings` 命中 `parseCheckBulk`，方法签名齐全）→ 当前部署制品的 bulk 对齐结论成立。
- [x] auth-platform SDK 源码为**未提交工作树改动**（`M RemoteAuthzEngine.java` / `M pom.xml` / `?? src/test`）。**动作**：F3 改完后必须 `install -Dmaven.repo.local=/Users/liruijun/personal/repository` 让 langchain4j 拿到新版。
- 版本快照：auth-platform HEAD=`444cf35`；langchain4j 侧另有 rag-tenant-authz 未提交改动（提交时须拆分，见记忆）。

## 阶段 2 — 核心逻辑：SDK F3 严格布尔校验 ✅ 完成

- [x] 新增私有 `requireAllowed(JsonNode, op)`：`allowed` 必须存在且 `isBoolean()`，否则抛 `IllegalStateException`（异常只含节点类型，不含 body）。`check`（single）与 `parseCheckBulk`（bulk）同批接入。
- [x] `RemoteAuthzEngineTest` 增 4 例（missing/null/string/number allowed），经 bulk 路径覆盖共用 helper；11/11 全绿。
- [x] `./mvnw clean install` 全模块 BUILD SUCCESS（sdk 11、server 6、admin 等全绿）→ F3 不破坏下游。
- [x] 已 `install` 到 `~/.m2` 与自定义仓库 `/Users/liruijun/personal/repository`；`javap` 确认自定义仓库 jar 含 `requireAllowed`；langchain4j knowledge-service 离线编译 SUCCESS（消费链打通）。

## 阶段 3 — 接口/门禁：F1（缓存互斥）+ F2（D3 preflight）✅ 完成（脚本层，需活栈跑）

- [x] **F2** `auth-platform/deploy/rag-authz-fixture.sh`：APPLY 后新增强一致 `/v1/permissions/check`，实测成员经 D3 链对样本文档有 `view`，不满足 `exit 3`（杜绝"写了但判权没生效"切 enforce）。`bash -n` + dry-run 验证通过。
- [x] **F1** `langchain4j/deploy/smoke-rag-tenant-authz.sh`：新增 step 0b 发布约束——`CONVERSATION_SEMANTIC_CACHE_ENABLED=true` 与 authz 互斥则失败退出；另加可选 step5（同租户 stranger 负向）+ step6（grant→revoke 即时生效环），`token_for` 抽公共。`bash -n` 通过。
- 注：脚本的实跑需活栈（SpiceDB+Casdoor+enforce 全栈），本地无法执行，仅语法/结构校验。

## 阶段 4 — 测试（consumer 单测可跑；D3 集成/E2E 需活栈）— 待用户确认范围

FINAL_PLAN §9.1 列出的 langchain4j 测试增补（`RealKnowledgeAuthzTest` 二批失败/部分 map/指标、`KnowledgeQueryServiceAuthzTest` shared-AND/reranker 不见 deny 正文/池边界、`CheckAccessShareTest` engine 异常不写 viewer、`KnowledgeAuthzIntegrationTest` D3 三态）。
**阻塞点**：① 这些落在 langchain4j 仓，而该仓当前有大量**与本任务无关的未提交改动**（rag-tenant-authz + OIDC 批3），混入需谨慎拆分；② D3 集成/E2E 需活栈，本地无法 `跑测试` 验证（违反"每阶段编译+跑测试"纪律）。→ **已就此向用户 checkpoint。**

## codex-review 闭环（2026-07-15，对提交 f3bbfc6）

Codex 独立审查后，Claude 逐条核验，采纳 3 项（用户批「修 ①②③」），均已修 + 全量构建绿：

- **① D3 门禁假阳性（fixture，中）**：原取"首个成员"判 view，若 `OWNER_USER==该成员`或存量文档已直授，会经 owner/直授路径假阳性通过，不证明 D3 链。→ 改取**非 OWNER_USER 成员**校验；存量直授无法被 seed 排除，已在脚本+计划注明为已知局限。
- **② 缺样本 skip 却 exit 0（fixture，中）**：违背"绕过脚本无法得绿"。→ APPLY 下无法隔离验证 D3 即**非零退出（exit 4）**，留 `ALLOW_UNVERIFIED=1` 给分步 seeding。
- **③ SpiceDbAuthzEngine 吞 pair error（core，中，本提交外）**：core:59-63 按下标取 pairs、忽略 per-item `error`、缺 permissionship 折成 false；server 再重建干净响应 → SDK 的 F3 在真实 server 面前永不触发，shadow 仍把 SpiceDB 错误记成 deny。→ `check`/`checkBulk` 加 `hasPermission` 严格校验（pairs 基数 + per-item error + permissionship 非空即抛），与 SDK F3 对称，端到端不再"错误伪装成 deny"。仍 fail-closed。
- **④ single check 无 HTTP 测试（低）**：已认，属暂停的 Stage 4 测试（需 MockRestServiceServer）。**⑤ resource asText/重复请求（低）**：rag-tenant-authz 代码、理论情形，暂不改。

验证：`./mvnw clean install` 全模块 **BUILD SUCCESS**（sdk 11、server 6、admin 等全绿）；fixture `bash -n`+dry-run 通过。③ 的运行期路径需 server-smoke（活栈）实测。**均在分支 `sdk-strict-authz-response` 追加提交。**

## 阶段 5 — 文档与终检
（待阶段 4 定案）
