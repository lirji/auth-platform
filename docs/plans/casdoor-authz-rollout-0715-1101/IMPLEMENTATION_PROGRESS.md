# 实施进度 — 全平台权限接入 Casdoor（casdoor-authz-rollout）

> 关联 [FINAL_PLAN.md](./FINAL_PLAN.md)。codex-plan 产出（requirements / codebase-analysis / solution A–D / comparison / test-plan）+ Claude 复核补全 FINAL_PLAN。

## 状态：方案已批准，实施**暂停**，等并行 workstream 落地（2026-07-15）

- **方案已定**：Codex 方案 A（声明式 manifest + 幂等 reconcile + 单栈 feature-gated strangler）为主；用户批准「推荐路线」= **本轮就做全平台服务端 scope 强制**（Phase B-0）。
- **关键前提修正（已实测确认，写入 FINAL_PLAN §0）**：先前「各服务 hasScope 缺 scope→403」是错的。全量核验：仅 4 个 Controller 强制 scope（`AdminController` role-admin、knowledge `DocumentController`/`MultimodalImageSearchController` ingest、`WorkflowController` approve）；**chat/agent/analytics/channel/eval/vision/voice/interop/async 全部只带 scope 不校验**。故「都接入」的实质是 Phase B-0 新增 edge 集中式 `EdgeScopeEnforcementFilter`（路由前缀→required scope，缺→403，默认关/shadow/enforce 三态）。

## 为何暂停（阻塞项）

langchain4j-platform 工作树被**并行 workstream `rag-tenant-authz`（docs/plans/rag-tenant-authz-0715-1005/）大量未提交改动占用**，且与本轮 edge 改动**同文件、部分同目的**：
- 已改（未提交）：`edge-gateway/`{`CasdoorSecurityProperties.java`(加 `Mode{DUAL,ONLY}`)、`CasdoorDecoderConfig.java`、`CasdoorTokenExchangeFilter.java`、`application.yml`、测试}、`deploy/`{docker-compose.yml、helm values.yaml、smoke-rag-tenant-authz.sh}、`knowledge-service/`(9M+6??)、`platform-protocol`。
- **语义重叠**：其 `Mode{ONLY}`（严格 Casdoor-only、无 token→401、不落 legacy）≈ 本轮 **Phase C（下线 legacy）的机制** → 不要重复实现 Phase C。
- **正交部分**：本轮 **Phase B-0 的 `EdgeScopeEnforcementFilter`（per-scope 路由门禁）是净新增**，并行线未做；仅恰好要动同一 `application.yml`。

用户决策（2026-07-15）：**选项 2 —— 等 `rag-tenant-authz`（其 Phase 4-5 未完）先提交落地，再在干净结果上接本轮**，避免脏树编译连带、提交纠缠、`application.yml`/Mode 语义冲突。

## 恢复条件与下一步（等并行线落地后）

1. 确认 `rag-tenant-authz` 已提交、langchain4j-platform 工作树对 edge 干净。
2. **Phase B-0（净新增，主交付）**：`edge-gateway/.../EdgeScopeEnforcementFilter.java`（order -95，读换发后的内部 JWT scopes；`edge.authz.mode=off(默认)/shadow/enforce` + `edge.authz.route-scopes` 路由前缀→scope 最长前缀匹配；缺 scope shadow-log 或 403）+ `EdgeAuthzProperties` + 测试 + application.yml 默认 map（/chat,/extract,/memory→chat；/chat/sql,/analytics→analytics；/agent→agent；/channel→channel；/eval→eval；/vision→vision；/voice→voice；rag/workflow/admin 已有服务级细门，不在 edge 重复）。以 SimpleMeterRegistry 单测；跑 `mvn -pl edge-gateway -DskipTests=false -Dmaven.repo.local=/Users/liruijun/personal/repository test`。
3. **Phase A**：`auth-platform/deploy/casdoor-seed.sh`→reconcile(dry-run/apply/journal/postcondition) + `casdoor-rollout-manifest.json` + `casdoor-rollout-smoke.sh`；先探活本地 Casdoor :8000 核实 `add-user/update-user/update-permission` 写端点与字段（Codex 分析时不可达，标「待验证」）。
4. **Phase B**：契约预检 → edge `EDGE_CASDOOR_ENABLED=true` → 前端 dual → oidc；5 角色×11 能力 403/200 矩阵。
5. **Phase C**：**复用并行线的 `Mode{ONLY}`**（不重造）+ session filter 独立开关停用 + `EdgeOpenPaths` 收敛 /auth/* + auth-service scale-to-zero；保留机器 api-key；不删源码/DB。

## 未决/风险（详见 FINAL_PLAN §5/§7）
- 两条 Casdoor-authz workstream 需在合并期协调 `application.yml`、edge Mode 语义、compose/helm；本轮 Phase C 应建立在并行线 Mode 之上。
- 12 项隐藏失败场景（角色加了 permission 未刷新、脚本把 5xx 当已存在、token TTL 延迟撤权、header 超限 431 盲区、多 org 写错 tenant 等）见 FINAL_PLAN §5。
