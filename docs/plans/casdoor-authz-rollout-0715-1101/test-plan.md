# 测试方案与验收标准（test-designer）

## 1. 测试原则

- 先负向、后正向：没有所需 scope 必须 403；有 scope 才验证 200；无效身份必须 401。
- 每一阶段都能单独测试和回滚，不把 Phase A 数据正确性推迟到流量切换时发现。
- 业务依赖用固定 fixture/stub，避免 LLM、ASR、数据库状态把“有权”误报为 5xx；生产 smoke 再验证真实依赖。
- 不在日志、JUnit 输出、curl trace 或报告中写 access token、refresh token、client secret、API Key。
- edge 是外部黑盒入口；同时做内部 JWT 合同测试，证明 `owner/sub/scopes` 未在传播中改变。

## 2. Phase A：迁移工具测试

### 2.1 静态与单元级

| 用例 | 输入 | 期望 |
|---|---|---|
| manifest schema | 缺 org、重复 username、未知 role、跨 org 同主体 | fail-fast，零写入 |
| scope 映射 | 5 角色定义 | 与 `SeedRoles.defaults()` 完全一致，全集恰为 11 项 |
| 规范化 diff | current 顺序/空白不同 | noop，不产生写 |
| 错误分类 | add-role 返回 409 / 401 / 500 | 仅经 GET 证明“已存在”才 noop；401/500 失败退出 |
| dry-run | 任意差异 | 输出 plan，不调用写 API |
| 默认非 prune | Casdoor 有 manifest 外对象 | 报 drift，不删除 |
| secret 脱敏 | API 返回/命令失败 | 输出中不含 token/secret/password |

建议用可记录请求的本地 stub HTTP server 测 shell；若实现时不引入 Bats，可用仓库已有 shell 风格 + 独立 fixture server，但必须进入 CI。

### 2.2 集成与幂等

1. 空的 disposable org：dry-run 有差异，apply 成功，再 dry-run 为零差异；
2. 已存在正确对象：apply 为 noop；
3. 角色已存在且有 users：reconcile 不清空成员；
4. 用户角色变更：先更新 assignment，再刷新 permission；新 token 权限正确；
5. 中途在第 N 个 permission 注入 500：脚本非零退出、journal 标出完成边界；修复后重跑收敛；
6. 两个 apply 并发：第二个拿不到同 org 单写锁并退出，不交错 delete/add；
7. 网络超时/429：有限指数退避；超过预算失败，不无限挂起；
8. 没有经验证的原位 update API 时，delete+add 只在 Phase A/维护窗执行；失败后 token probe 阻断发布。

### 2.3 数据验收

对 `alice/acme/admin`、`bob/globex/viewer`、`analyst-a/tenantA/analyst` 分别新签 token：

- `owner` 等于期望 org；
- `sub` 非空、稳定，且不同用户不重复；
- `permissions[].name ∩ allowlist` 分别等于 11 项、`{chat}`、`{chat,analytics}`；
- 重新登录/refresh 后仍一致；
- 未知 permission 不进入内部 JWT；
- 多 org 或 owner 不明确的用户不允许进入 apply 清单。

## 3. 配置合同测试

| 合同 | 断言 |
|---|---|
| client/audience | `VITE_CASDOOR_CLIENT_ID ∈ CASDOOR_AUDIENCES` |
| allowlist | `config.ts:SCOPE_ALLOWLIST` 与 edge YAML 11 项集合、大小写完全一致 |
| issuer/JWKS | token `iss` 精确等于 edge issuer；JWKS key 能验证 token |
| redirect | callback/login/oidc-silent 的实际 URL 均登记，CORS/CSP 允许真实 origin |
| DR-11 | edge enabled 且 health/readiness 正常后，才允许部署 dual/oidc 前端 |
| header size | 8.8KB 基线 token 成功；接近配置上限成功；超过上限可预测地 431 并告警 |
| 默认关闭 | 无 env 时 edge Casdoor=false、前端 apikey，不改变旧链 |

## 4. 角色 × scope 403/200 矩阵

先为 viewer/editor/analyst/approver/admin 各准备一个同 org 测试用户；角色分配后重建 permission，再签发新 token。表中 `200` 表示用固定 stub/fixture 构造的合法请求必须成功，`403` 表示必须在进入业务副作用前拒绝。

| role | chat | ingest | approve | agent | channel | eval | vision | voice | analytics | role-admin | public-ingest |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| viewer | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| editor | 200 | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| analyst | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 200 | 403 | 403 |
| approver | 200 | 403 | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| admin | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |

### 4.1 代表端点与实际方法

| scope | 端点 | 实际类 / 方法 | 200 fixture 前提 |
|---|---|---|---|
| chat | `POST /chat` | `ConversationController.chat` | 固定 LLM/stub，body `{"message":"..."}` |
| ingest | `POST /rag/documents` JSON | `DocumentController.uploadJson` | 唯一 title、内存/测试向量存储 |
| approve | `GET /workflow/tasks` | `WorkflowController.tasks` | workflow enabled + 测试 DB |
| agent | `GET /agent/capabilities` 或 `POST /agent/run` | `AgentCapabilitiesController.capabilities` / `AgentController.run` | 优先无副作用 capabilities；门应与 agent scope 一致 |
| channel | `GET /channel/capabilities` | `ChannelController.capabilities` | 无外部 channel 依赖 |
| eval | `GET /eval/capabilities` | `EvalController.capabilities` | 无外部 target 依赖 |
| vision | `POST /vision/caption` | `VisionController.caption` | vision enabled + stub model + 最小合法图片 |
| voice | `POST /voice/transcribe` | `VoiceController.transcribe` | voice enabled + stub provider + 最小音频 fixture |
| analytics | `GET /analytics/schema/tables` | `AnalyticsSchemaController.tables` | NL2SQL enabled + fixture DB |
| role-admin | `GET /auth/admin/roles` | `AdminController.listRoles` | 仅 Phase B legacy 管理面验证；Phase C 应不可达 |
| public-ingest | `POST /rag/documents?visibility=public` | `DocumentController.uploadJson` | public KB enabled + 唯一 fixture |

**硬门**：当前 main 对 chat/agent/channel/eval/vision/voice/analytics 找不到服务端 scope 检查。上述负向用例若不是 403，Phase B-0 失败；不得用前端禁用按钮代替。先同步声称存在的门禁提交，或单独实施并评审服务端 scope enforcement，再重新从单元到 E2E 全跑。

## 5. edge 与内部 JWT 测试

在现有 `CasdoorTokenExchangeFilterTest` 等基础上覆盖：

- Casdoor token 成功：内部 JWT 的 tenant/user/scopes 与 owner/sub/allowlist 一致；Authorization/API Key 被剥离；
- 缺 owner/sub：401；未知 scope 被丢弃；空 permissions 得到空 scope，受保护端点 403；
- 错 issuer、audience、签名、过期：无机器 key 时最终 401；dual 期仅明确携带有效机器 key 才可走机器身份；
- 伪造 `X-Internal-Token`：无法从公网透传；
- session-enabled=false：旧 session Bearer 最终 401；机器 key 仍 200；
- auth routes 移除后 `/auth/login|register|refresh|logout|public-config|admin|me` 不再路由到 auth-service；
- 下游跨服务调用继续转发同一 tenant/user/scopes；
- edge 重启、JWKS 首次获取、key rotation、Casdoor 暂时不可达等场景有确定结果和指标。

## 6. 前端测试

- `apikey` 全回归：现有测试与构建不变；
- `dual`：登录后同时注入 Bearer/API Key，edge 以 Casdoor 为准；未登录可用机器测试 key 的路径仅限受控测试构建；
- `oidc`：不显示 API Key/legacy 账密登录/RBAC legacy 控制台；
- callback state/nonce、refresh 单飞、过期模态、deep-link、SLO、多标签登出、SSE 续订继续通过；
- token/secret 不出现在 localStorage、URL、history、curl 预览和错误文案；sessionStorage 是已确认例外；
- 独立 dual 与 oidc 构建都执行 `npm test`、type-check、production build。

## 7. Phase C 收敛回归

| 场景 | 期望 |
|---|---|
| Casdoor 人类 token | 所有授权矩阵继续通过 |
| 旧 session access token | session 关闭后 401 |
| 旧 refresh cookie 调 `/auth/refresh` | route 收敛后不可达 |
| 纯机器 `X-Api-Key` 经 edge | 仍按绑定 tenant/user/scopes 工作 |
| 人类前端 API Key UI | oidc 构建中不存在 |
| 直接访问下游 + API Key | 生产配置下不能获得身份；网络策略阻断更优 |
| `/auth/admin/**` | Phase C 不可达，不能继续写 legacy RBAC |
| auth-service scale=0 | edge readiness 和其他服务不依赖它 |
| edge 回滚镜像 + auth-service scale=1 | 在备份/secret 保留期内演练可恢复 |

## 8. 非功能与异常测试

- **可用性**：Casdoor 两实例/托管 HA（若部署支持，待环境验证）、Postgres 故障、JWKS 缓存、登录/token p95；
- **容量**：并发登录、refresh 单飞、多标签、JWKS rotation；edge 验签 CPU；
- **安全**：alg/issuer/aud/exp、owner 缺失、未知 scope、header 注入、CORS/CSP、redirect allowlist、secret 泄漏扫描；
- **审计**：角色/permission/user 变更可关联 actor、org、object、before/after、plan id；不记 token；
- **租户隔离**：acme token 只能得到 owner=acme；无法用 header/query 访问 globex；多 org 清单 fail-fast；
- **撤权**：记录角色撤销时间，确认新 token 立即无 scope，旧 token 最晚在配置 TTL 内失效；
- **回滚演练**：每个环境至少一次 dual→apikey/edge off；Phase C 前演练恢复 route/session/auth replica。

## 9. 构建命令与报告

edge 构建必须显式使用项目本地仓库：

```bash
cd /Users/liruijun/personal/LLM/langchain4j-platform
mvn -pl edge-gateway -DskipTests \
  -Dmaven.repo.local=/Users/liruijun/personal/repository package
docker build -t <edge-candidate> edge-gateway
```

测试报告必须记录：代码 commit、镜像 digest、Casdoor version、manifest hash、issuer/audience、环境、角色 token 的脱敏 claim 摘要、矩阵结果、指标窗口、回滚操作与执行人。

## 10. 最终通过条件

所有 Phase A 幂等/部分失败用例、配置合同、5×11 矩阵、edge 安全用例、前端三态回归、Phase C 旧凭证拒绝和机器 key 保留用例全部通过；任一预期 403 得到 2xx，或任一未知/跨租户 scope 被授权，均为零容忍失败。
