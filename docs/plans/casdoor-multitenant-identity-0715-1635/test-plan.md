# 测试方案（test-designer）

## 1. 原则与分层

- 先验证身份/租户负向，再验证scope负向，最后验证业务成功；401、403、业务4xx/5xx必须可区分。
- 不以mock Casdoor替代固定版本live contract；mock用于故障注入和边界，live用于最终字段/claim/redirect证明。
- 每一阶段都输出可机器读取报告：commit/image digest、Casdoor/SpiceDB版本、manifest hash、registry revision、脱敏claim摘要、测试矩阵、回滚演练。
- token/password/client secret/API key不得进入JUnit、Vitest snapshot、shell trace、journal或CI artifact。
- 任何skip只表示“未验证”，不能计入通过。

## 2. 候选方案可行性spike

| 方案 | 必做spike | 通过标准 |
|---|---|---|
| A | 两个org/app签token；验证owner/aud不同；edge动态热加载第三app | 两租户成功、错配拒绝、reload无重启且老租户不中断 |
| B | 固定Casdoor版本把不可自助修改property写入access token | claim稳定存在；用户不能自改；refresh后一致；缺/多值fail-closed |
| C | 一个app用`organizationName`或等价官方参数登录两个org | 两org授权码、refresh、silent、logout都成功且owner正确；否则方案淘汰 |
| D | token groups完整性、唯一tenant group和大group容量 | 0/1/2 tenant group结果确定；旧token撤权窗口记录；header不超预算 |

只有A是主线必交付；B–D spike可作为ADR附件，不阻塞A。C不得只证明password grant，必须证明SPA Authorization Code + PKCE全生命周期。

## 3. manifest/reconcile单元与契约测试

### 3.1 schema和静态校验

| 用例 | 期望 |
|---|---|
| 重复tenant/org/clientId | fail-fast，零远端写 |
| tenant含`_`、`/`或空白且将进入`CasdoorGroupIds v1` | fail-fast或显式v2迁移；不得静默替换 |
| app缺callback/silent/postLogout任一URI | fail-fast |
| registry含clientSecret/password/token | schema拒绝 |
| user重复键 | 按`(tenant,username)`判重；跨tenant同名允许 |
| 未知role/scope | fail-fast；scope必须属于11项allowlist |
| redirect origin不在环境白名单 | fail-fast |
| revision回退/相同revision不同hash | 拒绝发布并告警 |

### 3.2 Casdoor API contract

固定镜像版本后录制并断言：

- get/add/update organization/application/user/role/permission/group的路径、HTTP码、`status/msg/data`形状；当前仅仓库中的add/delete/get部分有证据，update全部待验证；
- already-exists、not-found、401、403、409、429、500分类；不能用`|| true`吞错；
- app的`organization/clientId/redirectUris/grantTypes`读回值与desired完全一致；
- role assignment后刷新permission，新token的`permissions[].name`正确；
- password reset/初始密码策略，确认legacy hash不能直接导入时的用户迁移流程。

### 3.3 dry-run、幂等、并发、部分失败

1. 空Casdoor：dry-run有plan，apply成功，二次dry-run零diff。
2. 对象已完全存在：apply为noop，不清空role.users/redirects。
3. 对象存在但漂移：只更新owned字段；非owned字段报告drift不覆盖。
4. 第N步注入500/timeout：非零退出、journal停在精确step；重跑收敛。
5. 写成功但响应超时：重跑先GET current，识别已成功，不重复创建。
6. 429：有上限的指数退避+jitter；预算耗尽失败，不无限挂起。
7. 同org两个apply：第二个拿不到lease；不同org在配置并发度内可并行。
8. permission只能delete+add时注入add失败：tenant不publish registry，既有active tenant不得走该路径；恢复后重跑。
9. registry publish失败：Casdoor对象保留但tenant状态非active；重试publish即可完成。
10. publish后edge某副本reload失败：该副本readiness stale并退出流量，LKG仍服务旧tenant。

## 4. registry与edge单元/集成测试

### 4.1 registry loader

- 双索引查询一致；duplicate/disabled/expired/坏hash/未知schema拒绝；
- 原子替换期间并发1000次token校验，不得看到半快照；
- 拉取404/401/500/timeout/畸形JSON继续LKG并增加`reload_failed`；
- 超过`max-staleness` readiness失败但存量请求行为按配置明确（推荐继续LKG、阻止发布/摘流量）；
- 10k tenant snapshot加载时间、内存和GC在预算内；lookup p99不随tenant数线性增长。

### 4.2 JWT validator矩阵

| iss/signature/time | owner | aud | registry | 期望 |
|---|---|---|---|---|
| valid | acme | app-acme | active exact pair | 通过并换内部JWT |
| valid | acme | app-globex | 两者都各自active | 401 owner-aud-mismatch |
| valid | unknown | app-acme | 无owner | 401 unknown-tenant |
| valid | disabled | app-disabled | disabled | 401 disabled-tenant |
| valid | 缺失 | app-acme | active | 401 missing-claim |
| valid | acme | 未知aud | active owner | 401 invalid-audience |
| bad任一项 | 任意 | 任意 | 任意 | DUAL透legacy/ONLY 401，沿用现语义 |

额外断言：

- 多aud token只要有且仅有一个与owner匹配的启用record才通过；歧义配置在registry加载时已拒绝；
- unknown scope丢弃；空permissions得到空scope并由Phase B-0在受保护route返回403；
- 入站伪造`X-Internal-Token`被剥；有效Casdoor+API key时Casdoor优先且key不进内网；
- `EDGE_CASDOOR_MODE=ONLY`仍强制tenant claim为owner；动态registry不能绕过此启动约束；
- registry revision作为低基数日志/响应诊断信息，不写入内部JWT协议。

### 4.3 现有edge回归

扩展而不是替换：

- `CasdoorTokenExchangeFilterTest`现有11类用例全部通过；
- `CasdoorJwksIntegrationTest`用两个真实app参数化；
- `SessionBearerAuthFilterTest`、`ApiKeyToInternalTokenFilterTest`、`EdgeOpenPathsTest`和既有Phase B-0测试全绿；
- JWKS冷启动、缓存、key rotation、Casdoor暂时不可达在DUAL/ONLY下结果确定。

## 5. 前端测试

### 5.1 registry和登录页

- tenant为空/未知/disabled/lookup超时显示可操作错误，不发authorize；
- acme解析app-acme、globex解析app-globex；不把client secret写入DOM/storage；
- `apikey`模式不请求registry、不构造UserManager，legacy登录与API key回归逐字通过；
- `dual/oidc`显示tenant选择，dual仍可受控使用API key，oidc不显示key。

### 5.2 manager生命周期

- `getUserManager(tenantConfig)`按`issuer+clientId`复用，不跨tenant复用；
- start login写pending tenant/revision；callback刷新页面后仍能恢复正确manager；
- callback token owner不等于expected tenant时清User/sessionStorage并报错；
- active tenant bootstrap、refresh single-flight、UserLoaded事件只更新同tenant会话；
- acme登录后切globex：清acme User/事件/API key/history，再跳globex；旧acme silent事件不得回写；
- callback失败“重新登录”保留原tenant，不退回默认acme；
- 两个tab分别登录不同tenant，sessionStorage隔离；登出广播不得把token/tenant敏感数据带进消息。

### 5.3 redirect与浏览器E2E

每个tenant app验证：

- callback、silent、post-logout URI均精确登记；
- deep link通过state恢复，开放重定向仍由`sanitizeRedirect`阻断；
- state/nonce/code过期、用户取消、第三方cookie限制有确定错误；
- hard refresh、refresh token轮换、多标签登出、CSP/CORS、history fallback全通过；
- `sessionStorage`只有OIDC User及最小tenant上下文，无secret。

## 6. admin和数据迁移测试

### 6.1 legacy导出与映射

- 从实际`USERS/USER_ROLE/ROLE_SCOPE/TENANT_ROLE/AUTH_GROUP/GROUP_ROLE/USER_GROUP`导出current，验证行数与引用完整性；
- `USERS.ROLES`、`ROLES.SCOPES`只作影子对账，不覆盖关系表权威数据；
- 同名跨tenant fixture正确产生两个Casdoor users和两个sub；
- direct scopes生成的确定性迁移role（或经contract验证的等价映射）可重跑且scope集合等价；
- tenant base role、group role展平后，每用户effective scope与`EffectivePermissionResolver.resolve()`输出集合一致；来源信息若Casdoor不能表达，保留在migration audit而不伪造。

### 6.2 密码/账号状态

- legacy bcrypt hash不直接塞入未验证字段；迁移用户得到一次性激活/重置路径（能力待固定版本验证）；
- disabled用户在Casdoor禁用且不能签token；
- last-admin保护在Casdoor-backed adapter中有等价负向测试；
- 用户迁租默认拒绝；若未来开放，必须单独saga测试。

### 6.3 SpiceDB subject crosswalk

1. 扫描所有`user:<legacyUserId>` direct relationships并按tenant-prefixed resource分类；
2. 映射唯一时，同一write batch TOUCH Casdoor sub + DELETE legacy ID；
3. 未知/一对多/跨tenant资源前缀冲突时阻断，不自动猜；
4. 注入batch失败后原关系不变（远端原子性需live验证）；
5. fully-consistent check确认新sub allow、旧ID deny；
6. rollback manifest可反向TOUCH旧+DELETE新；
7. GroupSync二次运行不把旧ID重新写回。

### 6.4 admin adapter

- 所有user route/body key包含tenant；`sam@acme`操作不影响`sam@globex`；
- operation id幂等：重复POST返回同operation或noop，不重复远端对象；
- plan hash被修改/过期则拒绝apply；两个管理员并发更新同org只有一个active operation；
- UI正确展示pending/partial/failed/succeeded和最新diff，不把远端409未经分类冒充乐观锁冲突；
- Casdoor authoritative后legacy写开关关闭返回确定状态；对账不一致阻止oidc canary。

## 7. GroupSync多租户测试

- acme/globex同名`members`分别写`group:acme_members`和`group:globex_members`；
- 每org独立current/desired、delete threshold、checkpoint和metric；acme拉取失败不删除globex也不阻断globex新增；
- nested group不被当direct member删除（保持现有测试）；
- 多副本同时sync同org只有lease owner写；失去lease立即停；
- 分页中断不得把partial desired当完整集合；无完整页水位时零DELETE；
- webhook风暴被合并/限速，周期reconcile最终收敛。

## 8. 多租户登录与隔离E2E（发布硬门）

准备至少：

- `alice@acme` admin，app-acme；
- `bob@globex` viewer，app-globex；
- 可选同名`sam@acme`和`sam@globex`；
- invalid token、wrong-aud、owner-aud-mismatch、disabled tenant fixtures。

流程：

1. acme从SPA选择acme完成PKCE，断言access token `owner=acme/aud=app-acme`；经edge得到业务响应tenant=acme。
2. globex同理；两者在同一浏览器不同tab并行。
3. acme写文档，globex用自己的合法token读取/分享/删除均失败或空集合；反向同样。
4. 伪造tenant header/query/path不改变内部tenant。
5. owner-aud错配401；valid owner/aud但缺scope由Phase B-0 403。
6. 新建beta并publish registry；不重启edge、不重建SPA，beta完成首次登录；撤回registry revision后beta不可新登录、acme/globex继续。

## 9. scope矩阵（复用既有Phase B-0）

不重写route规则，只把既有5×11矩阵对至少两个tenant参数化：

| role | chat | ingest | approve | agent | channel | eval | vision | voice | analytics | role-admin | public-ingest |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| viewer | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| editor | 200 | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| analyst | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 200 | 403 | 403 |
| approver | 200 | 403 | 200 | 403 | 403 | 403 | 403 | 403 | 403 | 403 | 403 |
| admin | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |

要求每个403在业务副作用前发生；前端隐藏按钮不算通过。edge `off/shadow/enforce`分别验证不拦截+无日志、只记would-deny、真实403。

## 10. F1/F3与跨模块回归

- 配置合同：`RAG_AUTHZ_MODE=shadow|enforce`且`CONVERSATION_SEMANTIC_CACHE_ENABLED=true`时发布校验失败；两模式下运行态`SemanticCache.isEnabled()`为false。
- 安全回归：用户A可见私有文档生成回答后，用户B问同义问题不能从tenant级cache得到A答案。
- F3：`RemoteAuthzEngineTest`全部畸形响应用例、`SpiceDbAuthzEngine` pairs/permissionship/error用例全绿；不得把异常降级为普通deny指标。
- 内部JWT跨服务保持tenant/sub/scopes；ReBAC对象前缀仍取owner tenant。

## 11. 灰度、故障与回滚演练

### 11.1 灰度阶梯

每一级至少跑身份负向、两租户E2E和scope矩阵抽样：

1. Casdoor数据/registry准备，运行流量仍apikey；
2. registry shadow加载，不参与validator；
3. edge enabled+DUAL，SPA apikey；
4. SPA dual canary（独立build/URL）；
5. Phase B-0 shadow→enforce；
6. SPA oidc canary→100%；
7. edge ONLY（机器入口策略已确认）；
8. legacy admin read-only、session/route off、auth-service scale 0。

### 11.2 故障注入

- Casdoor登录/token/JWKS不可达、key rotation；
- registry源超时、坏签名、旧revision、实例分裂；
- auth-platform-admin/SpiceDB不可达和partial page；
- Casdoor 429/500、写成功响应丢失；
- edge滚动发布期间新旧registry兼容；
- token接近/超过32KB，入口返回可观测431；
- 100并发首次登录、refresh single-flight、多tab、1000tenant registry刷新。

### 11.3 回滚组合

- identity：SPA `oidc→dual→apikey`；edge `ONLY→DUAL→enabled=false`；registry回上一revision。
- scope：既有Phase B-0 `enforce→shadow→off`。
- admin：Casdoor adapter写停→legacy写仅在DB/route/session仍保留且crosswalk未过不可逆点时临时恢复。
- subject：执行反向crosswalk batch；必须在Casdoor token流量降回legacy身份后做，避免新sub请求与旧tuple错位。
- Phase C后恢复登录必须同时恢复auth-service副本、`/auth` route、session filter和secret，再部署apikey前端。

## 12. 最终通过条件

- 所有schema/contract/幂等/并发/部分失败测试全绿；
- 两租户（含同名用户）登录、silent、logout和跨租户负向E2E全绿；
- owner-aud矩阵全绿，新tenant免edge重启证明完成；
- legacy→Casdoor effective scope集合对账100%，冲突清零或显式豁免签字；subject crosswalk无未知项；
- 两tenant的5×11矩阵全绿；F1合同与F3回归全绿；
- 完成至少一次全阶梯正向灰度和反向回滚演练；
- 任一越权2xx、任何owner-aud错配通过、任何跨tenant数据可见均为零容忍失败。
