# 需求分析（requirements-analyst）

## 1. 任务定义

把 `langchain4j-platform` 的**人类身份与粗粒度授权真相源**统一到 Casdoor：用户属于一个 Casdoor organization，organization 等于平台 tenant；用户经 role 获得以 Casdoor permission 名表达的业务 scope；edge 验证 Casdoor access token 后只把 `permissions[].name ∩ 11 项 allowlist` 写入形状不变的内部 JWT。迁移必须覆盖：

1. Casdoor 数据准备；
2. edge 与前端从默认关闭到 dual、再到 oidc 的全平台灰度；
3. 停止自建 `auth-service` 登录/RBAC 对外服务和人类静态 API Key；
4. 保留纯机器调用的 `X-Api-Key`，全面改为 `client_credentials` 不属于本轮。

本轮只产出规划，不实施业务代码或环境变更。

## 2. 已确认规则

| 编号 | 规则 | 规划含义 |
|---|---|---|
| R1 | 人用 Casdoor；机器/CI/脚本本轮保留 `X-Api-Key` | `ApiKeyToInternalTokenFilter` 不能在 Phase C 删除；必须把“人类 key”与“机器 key”盘点、分开下线 |
| R2 | 现有 auth-service 数据仅为 dev/演示 | 不读 auth DB、不迁密码、不建 username→sub 数据迁移；只依据代码种子和显式 manifest 在 Casdoor 重建 |
| R3 | 前端按 `apikey → dual → oidc` | `dual` 是 Casdoor 优先、API Key 兜底，不是权限合并 |
| R4 | knowledge 历史 subject 可重建 | 不迁 SpiceDB 历史 username；新写关系必须从内部 JWT 得到 Casdoor `sub` |
| R5 | `owner` 是 tenant，单 org=单 tenant | 本轮一个用户只能有一个 organization；不接受 header/query 指定 active tenant |
| R6 | 用户分配角色必须先于 permission 重建 | 每次新增/调整角色成员后，必须重建相关 permission，并新签 token 验证 claim |
| R7 | `VITE_CASDOOR_CLIENT_ID` 必须命中 `edge.casdoor.audiences` | 发布前做自动配置一致性检查 |
| R8 | 前端与 edge 的 11 scope allowlist 逐字一致 | 同时检查集合和值；前端仅用于展示/预判，服务端判权才是安全边界 |
| R9 | DR-11：先开 edge，再切前端 `dual/oidc` | 反向顺序会令 Casdoor Bearer 落入 session filter 并得到 401 |
| R10 | 所有开关默认关闭且可快速回退 | Phase C 不应在观察期删除源码、Secret、数据库或备份 |

11 项 scope 为：`chat`、`ingest`、`approve`、`agent`、`channel`、`eval`、`vision`、`voice`、`analytics`、`role-admin`、`public-ingest`。

5 个角色的期望映射（来自实际 `SeedRoles.defaults()`）为：

| 角色 | scope |
|---|---|
| viewer | chat |
| editor | chat, ingest |
| analyst | chat, analytics |
| approver | chat, approve |
| admin | 全部 11 项 |

## 3. 现有演示身份的重建边界

实际 `SeedUsers.defaults(...)` 只有三条，规划不得推测更多：

| username | Casdoor org / tenant | role | 说明 |
|---|---|---|---|
| alice | acme | admin | 角色权限以 Casdoor admin 的 11 项为准；不迁旧 direct scopes |
| bob | globex | viewer | chat |
| analyst-a | tenantA | analyst | chat、analytics |

用户输入中的“等”只能通过后续经审批的 manifest 增加；脚本不得扫描或导入 auth DB。

## 4. 业务边界与非目标

### 本轮范围

- Casdoor org/user/role/permission/role assignment 的声明式建模、幂等 reconcile、dry-run、差异报告与后置 token 断言；
- 各环境 edge/前端灰度顺序、服务 scope 403/200 矩阵、监控与逐点回滚；
- legacy session、`/auth/**`、auth-service 运行实例和人类 API Key 的安全收敛；
- Casdoor 可用性、JWKS、token 体积、审计、角色变更传播、多 org 限制等生产风险。

### 非目标

- 不改变内部 JWT claim 形状、`X-Internal-Token` 名称或下游传播协议；
- 不为每个服务重新实现 Casdoor SDK/OIDC 验签；Casdoor token 只在 edge 验证；
- 不迁 auth DB 密码、refresh session、RBAC 关系表或历史 SpiceDB subject；
- 不做一个自然人的多 org active-tenant 选择和 token exchange；
- 不用 `client_credentials` 全面替换静态 API Key；
- 不删除 auth DB、Git 历史或备份；这些是另行审批的不可逆动作；
- 不把 auth-platform 的 Casdoor group→SpiceDB reconciler 扩展成通用 Casdoor 管理平台。

## 5. 关键边界条件

1. **权限为空必须 fail closed**：permission 未展开、未知 scope、allowlist 为空都不能自动补权。
2. **角色写后展开**：当前脚本注释和既有实测表明 role membership 后改不会自动刷新既有 permission 展开；角色分配与 permission reconcile 是一个操作单元，但 Casdoor API 无跨对象事务，需以顺序、日志和后置 token 验证补偿。
3. **不自动删除未知对象**：reconcile 默认只 create/update manifest 所有对象；`--prune` 不纳入常规发布。
4. **并发单写者**：同一 org 同时只允许一个 reconcile，避免 delete/add permission 与角色修改交错。
5. **构建期前端开关**：`VITE_AUTH_MODE` 不是运行时 flag。按用户/比例灰度需要并存两份前端构建或独立 canary URL/Ingress 权重。
6. **机器 key 仍经 edge 换内部 JWT**：下游不应持有或接受生产 API Key 目录；直连服务的网络边界和 `allow-api-key-fallback` 必须收紧。
7. **Casdoor 故障语义不同**：dual 期 decode/JWKS 失败可回落 legacy；oidc-only 人类流量没有 session 兜底，必须把 Casdoor/JWKS 作为关键依赖建设。
8. **旧 token 延迟撤权**：role/permission 变更只影响新签 token；撤权有效时间取决于 Casdoor access token TTL，不能宣称即时撤权。

## 6. 发现的架构前提差异（发布阻塞）

用户给定前提称“所有下游端点均调用 `TenantContext.hasScope`，缺 scope 返回 403”。对当前 `langchain4j-platform` 主代码的全文检索不能确认该结论：

- 已确认 scope 门：`DocumentController.requireWrite/requireIngest`、`MultimodalImageSearchController.requireIngest`、`WorkflowController.requireApprove`、`AdminController.requireRoleAdmin`；
- 未在 chat、agent、channel、eval、vision、voice、analytics 的主代码中找到 `hasScope` 或等价 403 门；
- `InternalTokenAuthFilter` 只解析并绑定身份，未解析成功时继续以 `TenantContext.ANONYMOUS` 执行，并不是统一认证/授权拒绝器；
- 前端 `capabilities.yml` 中上述多数能力的 `requiredScopes` 实际为空，前端目录也不能替代服务端授权。

因此，规划把“11 项 scope 的负向 403/正向 200 黑盒矩阵”设为 **Phase B-0 硬门**。若执行环境确有未合入当前 main 的统一门禁，先同步对应提交并重跑；若没有，则必须先另立受控前置改造补齐服务端 scope enforcement。不得在矩阵失败时仍开启生产灰度，也不得仅修改前端 `requiredScopes` 冒充服务端判权。本规划不把该差异偷偷当成已完成事实。

## 7. 歧义与处理结论

| 歧义 | 处理 |
|---|---|
| “所有用户、租户、角色、scope 均由 Casdoor 管”与“机器 API Key 保留”是否冲突 | 人类 IAM 全部 Casdoor；机器 key 的 tenant/user/scopes 映射仍暂存在 edge，属于明确的阶段性例外和最终方案弱点 |
| Phase A 是否迁移 auth DB | 否。只重建三条实际 demo seed 和显式新增 manifest |
| 多 org 用户如何迁 | 不迁、不复制、不支持；检测到同一主体跨 org 即失败并进入二期设计 |
| auth-service 管理前端如何处理 | oidc 全量后隐藏/停用旧 RBAC 控制台，管理入口转 Casdoor 管理台；不得让 `role-admin` 继续修改 legacy 真相源 |
| legacy 下线是否删除 `ApiKeyToInternalTokenFilter` | 否，本轮保留纯机器调用；只删除/禁用人类 key 和 session 路径 |
| Phase C “下线”是否删除源码/数据库 | 首先运行时下线并保留可恢复镜像/数据库；源码和 DB 物理删除是观察期后的另行审批点 |
| 本地 Casdoor 常驻是否可在本次分析中验证 | 本次只读访问 `localhost:8000` 失败，故 Casdoor 写 API、跨 org 应用配置等仍标“待验证”，不能凭经验填字段 |

## 8. 总体验收标准

- Phase A 可在空 Casdoor 和已存在 Casdoor 上重复执行：第二次 dry-run 为零差异；无未知对象被删除；每个用户新签 token 的 `owner/sub/permissions` 符合 manifest。
- 配置契约检查通过：前端 client ID 命中 edge audience；两边 11 scope 完全一致；issuer/JWKS/redirect/CORS 正确；edge 在 enabled 且缺必需配置时启动失败。
- 五角色 × 十一能力矩阵得到确定的 403/200，且当前代码差异已解决或对应提交已同步。
- 灰度严格按 edge on → frontend dual → frontend oidc；每一步都有观测窗口与反向操作。
- oidc 全量后旧 `/auth/login|register|refresh|logout|public-config` 不再对外可达，session Bearer 不再被 edge 接受，auth-service 可缩容为 0。
- 机器 API Key 仍能经 edge 工作；下游直连不能用 `X-Api-Key` 获得生产身份；人类界面不再展示或发送 API Key。
- 任何不可逆删除前均有单独审批、可恢复备份和已演练恢复；本轮默认停在可逆的运行时下线态。
