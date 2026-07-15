# 测试方案与验收标准（test-designer）

## 1. 测试原则

- deny 与 transport failure 分测：权限不足是 403/404，AP 不可用是 503；不能把失败都当 deny，也不能 fail open。
- 真实 SpiceDB/Casdoor E2E 不得用 assumption 静默跳过作为发布门；可跳过的 developer test 与 CI required profile 分开。
- 所有跨租户用例都使用相同 docId/spaceId，证明隔离来自 tenant 前缀而非测试数据巧合。
- 所有写路径至少验证：首次、重复、并发、半失败、重试、撤销、reconcile。
- 所有 SSO 用例使用 access token，并校验 issuer/audience；明确拒绝 id_token。

## 2. 单元测试

### 2.1 AP SDK/server

拟新增测试：

- `CheckAccessAspectTest`
  - SubjectResolver 返回 Casdoor sub；resource 参数完整传入。
  - resourceIdParam 不存在/为 null/空串时快速失败。
  - deny 抛 `AccessDeniedException`；allow 才执行目标方法。
  - 使用真实 Spring proxy 测 self-invocation 不被误判为已覆盖。
- `RemoteAuthzEngineTest`
  - service auth header、connect/read timeout、checkBulk 顺序、错误响应解析。
  - 401/403/429/5xx 分类，不记录 secret。
- `AuthzControllerSecurityTest`
  - health permit；无/错误 service credential 拒绝 `/v1/**`；正确 credential 可用。

### 2.2 AP admin reconcile

- group ID codec：tenant、原 group、role 的规范化与碰撞测试；`acme/engineers` 与 `globex/engineers` 不同。
- direct tuple diff：直接 user、nested group#member 都能 TOUCH/DELETE；不得用 transitive lookup 误删。
- Casdoor 某页失败/返回畸形：整轮禁止 DELETE。
- dry-run 不写；apply 计数与 diff 一致；重复 apply 为零变更。
- 变更量超过阈值、snapshot version 改变、分布式锁未取得时中止。
- role→relation 未配置时报告 error，绝不默认 viewer。

### 2.3 edge/Casdoor

拟以 mock JWKS/签名 key 测：

- 正确 issuer/aud/exp/nbf 的 access token -> 内部 JWT；`uid == token.sub`。
- 错 issuer、错 aud、过期、未生效、签名错、缺 sub、缺 tenant -> 401。
- token 中 active tenant 不在其已验证 organization/membership 集合、或请求 header 尝试覆盖 tenant -> 401/403，绝不签发目标 tenant 的内部 JWT。
- 原 Authorization 被剥离，只有 `X-Internal-Token` 进入下游。
- group/scopes claim 格式错误 fail closed；未知 group 不增权。
- Casdoor token 和 legacy session/API key 同时出现：按灰度配置显式拒绝或固定优先级并告警，不能静默混合。
- Casdoor-only profile 下 API key/session token 均 401。

### 2.4 knowledge domain/authz

- `KnowledgeResourceIdsTest`：`document(tenant,doc)`/`space(tenant,space)` 精确输出；空值、长度、非法字符；客户端伪造 tenant 无效。
- `TenantContextSubjectResolverTest`：sub 原样成为 `SubjectRef.user`；anonymous/missing user 在 enforce 下拒绝。
- space registry：default space、tenant isolation、version/并发、Redis 旧 JSON 缺字段兼容。
- document migration：旧 DocumentInfo 自动归 default space；独立授权记录中的 owner 保持 unknown/PENDING_MIGRATION，除非有可审计 manifest，不虚构 owner。
- `DocumentInfo` 现有构造调用继续编译，旧 Redis JSON 反序列化后 `spaceId=default`；新增字段不改变既有响应字段语义。
- application service AOP：upload 检查 space edit，get 检查 view，delete/replace 检查 edit；真实 Spring proxy 执行。
- list/query：一次 checkBulk；未授权、null docId、shared、重复 docId、空 candidates；null docId 在 enforce 不得默认放行。
- space/document 独立授权投影记录：PENDING/PENDING_MIGRATION 不外显；TOUCH 后 ACTIVE；AP 失败保留待修复；重复 reconcile 收敛。
- replace owner：同名重传不累积 owner；非 owner 但 editor 重传不夺取 owner。
- delete：先撤权后删数据；业务删除失败时关系已撤且 job 可重试；关系撤销失败时业务数据不删。
- `AccessDeniedException` 映射与正文/资源存在性不泄露。

## 3. 组件/契约测试

### 3.1 HTTP 合同

- `/rag/documents` 旧请求不带 spaceId -> default；旧响应字段不变，新增字段为向后兼容末尾字段。
- `/rag/query` 旧四字段 JSON 仍可反序列化；新增 spaceId 可选。
- protocol record 若加字段，保留旧 Java convenience constructor；全仓 `new KnowledgeQueryRequest`/`new KnowledgeHit` 编译通过。
- 401/403/404/503 与 `X-Error`/统一错误体合同固化。
- `/auth/login|register|refresh|logout` 在 Casdoor-only profile 不再是 open 的可用登录入口；具体选择 404/410 固化测试。

### 3.2 前端

- OIDC callback 成功/错误/state 不匹配、原深链恢复、silent renew 单飞、logout/end-session。
- token 采用与 AP auth-console 一致的 `sessionStorage` 会话级策略；不进 URL、日志或 `localStorage`，关闭 tab/session 后不可继续复用。
- 不再渲染 demo 密码、自助注册、API key 输入。
- API 401 触发 OIDC renew 一次并重试一次；失败清会话并回登录，避免循环。
- 管理路由 scopes 来自经验证 token profile；不能由 API key 本地状态伪造。

## 4. 集成测试

### 4.1 required AP + SpiceDB

启动独立 Postgres/SpiceDB/AP server，加载真实 `knowledge.zed`，测试必须执行而非 skip：

1. owner 创建 space/document，可 view/edit。
2. 同租户陌生用户 deny。
3. document viewer allow view、deny edit。
4. role group 绑定 space viewer/editor/admin 后继承正确。
5. nested `roleGroup#member@originalGroup#member` 继承正确。
6. 撤成员、撤 role binding、删 group 后 fully-consistent 立即 deny。
7. public_viewer 对任意具体 user allow；非公共资源不受影响。
8. 同 ID 跨 tenant deny。

### 4.2 knowledge 多存储半失败

用 fault-injection fake 或 Testcontainers 分别让 registry、vector、ES、graph、AP 在各阶段失败，验证状态机与补偿。至少覆盖：

- vector 成功/AP 失败；
- AP 成功/registry active 标记失败；
- 撤权成功/vector 删除失败；
- reconcile 进程中断后重启；
- 两实例同时 reconcile 同一资源。

### 4.3 RBAC 迁移/reconcile

构造含个人角色、租户基础角色、用户组角色、跨租户同名组、空组、自定义未映射角色的 auth DB：

- dry-run 报告源/目标数量、异常项与预计 DELETE；
- crosswalk 缺失阻止该用户授权，不用 username 兜底；
- Casdoor group import manifest 首次创建、重复执行零重复、部分失败续跑；切换后 direct membership 只以 Casdoor 为准；
- apply 后逐用户/space 权限矩阵与 `EffectivePermissionResolver` 的迁移前期望一致（relation 映射范围内）；
- 每个 tenant/user 的 Casdoor token scope 集与迁移前 `EffectivePermissionResolver` 基线相等；未知/未允许 scope 不进入内部 JWT；
- 第二次 apply 零变更；修改成员后 webhook + reconcile 收敛。

## 5. 端到端测试

### 5.1 浏览器 SSO

真实 Casdoor 测试租户和 PKCE 应用：登录 -> callback -> `/rag/query` -> 内部 JWT -> TenantContext -> SpiceDB check。断言 audit trace 能关联同一 `sub`，且浏览器网络中看不到内部 JWT。

### 5.2 角色/组场景

- Casdoor 把用户加入 imported editor group；webhook 后可向对应 space 上传/编辑。
- 移出 group 后在定义 SLO 内失权；旧浏览器 token 即使未过期，ReBAC 仍拒绝资源访问。
- 改 token scopes 只影响粗粒度功能，不能绕过 document/space ReBAC。

### 5.3 机器身份

用 Casdoor 测试 service account 获取 access token，运行 eval `/rag/query`；过期后能自动续取，错误 audience 被 edge 拒绝。

## 6. 并发、性能与容量

- 100 个并发同名上传：最终一个逻辑 document、版本单调、一个 owner、一个 parent_space；不允许孤儿 tuple。
- 两管理员并发变更同 group：旧 snapshot 不覆盖新版本。
- query 候选 10/50/100/500 的 checkBulk 基准；禁止 N+1。
- enforce fully-consistent 下 p95 增量目标：相对 shadow 基线不超过 80 ms（初始建议，压测后固化）；错误率 <0.1%。
- reconcile 10k users/1k groups/10k spaces 的批次、内存与 SpiceDB 写限制压测；单批大小配置化。
- 过滤后 topK 满足率 >= 95%；不足需记录，不得通过返回未授权 hit 补齐。

## 7. 安全测试

- JWT alg confusion、kid 不存在/JWKS rotation、重放过期 token、id_token 当 access token。
- tenant claim 注入、group 名路径/分隔符碰撞、超长 resource ID。
- 直接访问 knowledge 端口：无内部 JWT 401；API key fallback 关闭。
- 直接访问 AP server：无 service credential 401/403；写关系受 NetworkPolicy 限制。
- check-only credential 调关系写返回 403；knowledge write credential 不能写非 knowledge resource type/relation；不同服务 credential 不可互换。
- webhook timestamp/nonce/HMAC 防重放、速率限制。
- 日志/错误响应扫描不得出现 Bearer、client secret、SpiceDB key、正文。

## 8. 回归范围

- LP：platform-security、edge-gateway、auth-service（只读迁移期）、knowledge-service、platform-protocol、conversation、agent、eval、channel、前端全套测试。
- AP：protocol/core/sdk/server/admin 单元测试 + 两个 smoke 脚本。
- 保留 public KB、ES hybrid、graph、multimodal、tenant isolation、RBAC admin If-Match 回归。
- `mvn test`/npm test 的执行顺序先安装 AP artifacts，再跑 LP reactor；CI 不可从开发机 `.m2` 偶然取 SNAPSHOT。

## 9. 灰度与回滚演练验收

1. shadow 记录 allow/deny delta，不改变响应；误差归零或有签字豁免。
2. canary tenant enforce 后，错误率/延迟/deny 激增均在阈值内。
3. 关闭 enforce 能立即回 shadow，但不会删除 tuple/迁移数据。
4. edge 从 Casdoor-only 回兼容 image 的演练可完成；旧 DB/SESSION secret 只在回滚窗口封存，不常驻所有服务。
5. reconcile 的 DELETE kill switch 和变更量熔断实测有效。
6. 用备份关系清单可恢复误删 tuple；恢复后权限矩阵通过。

## 10. 最终可验证验收清单

- [ ] required integration test 无 skip。
- [ ] 身份四处 sub 一致。
- [ ] query/list/get/delete/upload 权限矩阵全绿。
- [ ] 跨租户、同名组、同 ID 场景全绿。
- [ ] AP 故障不泄露数据，恢复后自动收敛。
- [ ] migration dry-run/apply/reapply/rollback 全绿。
- [ ] Casdoor-only 下 API key/local session 全拒绝。
- [ ] 性能、撤权延迟、topK 满足率达到门槛。
- [ ] 全仓回归与部署模板渲染通过。
