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

## 阶段 5 — 文档与终检
（待阶段 4 定案）
