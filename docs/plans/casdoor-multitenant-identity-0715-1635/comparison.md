# 候选方案比较（risk-reviewer + plan-judge）

## 1. 统一比较口径

| 方案 | 租户边界 | application | tenant claim | audience管理 | 新租户是否免edge重启 |
|---|---|---|---|---|---|
| A 动态注册表 | Casdoor org | 每org一个app | `owner` | registry热更新，校验`(owner,aud)` | 是 |
| B 全局org+property | 用户属性 | 单app | 自定义`tenantId`（待验证） | 单aud | 是 |
| C 单app多org | Casdoor org | 全局单app（能力待验证） | `owner` | 单aud+owner registry | 是 |
| D 平台org+tenant group | Casdoor group | 单app | `groups`中唯一tenant group | 单aud | 是 |

## 2. 端到端改动面

| 维度 | A | B | C | D |
|---|---|---|---|---|
| 前端登录 | tenant lookup+动态manager | 单manager，tenant只作提示 | tenant参数/子域+单manager | 单manager，tenant只作提示 |
| edge | 动态registry与owner-aud绑定 | 改tenant claim+enabled registry | 单aud+owner registry | groups解析唯一tenant |
| Casdoor开通 | org+app+user+role+permission | global org中的user/property/role | org+user+role+permission | group+user+role+permission |
| ReBAC前缀 | 不变 | owner来源全改 | 不变 | group→tenant映射全改 |
| GroupSync | 单org→多org遍历 | 从owner改property | 单org读取方式取决于API | 需识别tenant/功能group |
| admin | `(tenant,username)`+Casdoor adapter | 全局name编码+tenant filter | `(tenant,username)`+adapter | tenant-group唯一约束+adapter |
| subject迁移 | legacy ID→Casdoor sub | 同左 | 同左 | 同左 |
| app运营 | 最高 | 最低 | 低 | 最低 |

## 3. 风险评审

### 3.1 兼容性

- A 保留当前 `owner→tenantId`、edge ONLY owner断言、前端 `userFromAccessToken`、`KnowledgeResourceIds` 和 SpiceDB `<tenant>_` 前缀，兼容性最好。
- B 改写 tenant claim 和用户命名，所有依赖 owner 的模块都需双读迁移；遗漏一处会把平台org当tenant。
- C 理论上保留 owner语义，但仓库脚本证明app绑定organization，跨org登录能力尚未证明。
- D 改写claim和`CasdoorGroupIds`，还需区分tenant group与协作group，兼容风险与B相近。

### 3.2 事务、部分失败与补偿

四案都无法让Casdoor对象、registry、auth DB和SpiceDB组成ACID事务。

- A用“token probe后才publish registry”定义可见性提交点；部分创建不接流量，可重跑收敛。
- B/D的用户tenant property/group与历史资源/tuple迁移有真实在线搬迁语义，部分失败可能把用户置于错误tenant，补偿更难。
- C数据过程类似A但少app；前提能力失败会在登录阶段暴露，而不是reconcile阶段可完全静态确认。

### 3.3 并发与幂等

- A可以自然按org分区锁；不同tenant并行、同tenant串行。
- B单org成为所有租户写热点，Casdoor对象级并发冲突更集中。
- C仍可按org分区数据，但全局app更新必须全局单写。
- D给用户同时改多个group时必须保证“恰好一个tenant group”，若Casdoor无条件写/版本号支持（待验证），并发风险最高。

### 3.4 性能与容量

- A的edge registry O(1)且请求热路径本地；控制面对象最多。
- B/C/D只有单audience；edge最轻。B/D单org集合巨大，Admin API分页和UI可能退化。
- D把全部groups带入token时最容易放大header；当前edge已因约8.8KB token把上限调到32KB（`edge application.yml:1-6`）。
- C共享app是登录流量和配置单点，不能按tenant限流/隔离。

### 3.5 安全

- A有org天然边界，新增风险集中在registry完整性与`(owner,aud)`绑定；clientId公开不构成secret泄漏。
- B依赖custom property不可由用户自改；一旦属性缺失/被篡改会错租户。
- C需防organization参数与owner不一致、SSO cookie自动选错org；且共享app爆炸半径大。
- D的一个错误group membership就可能跨租户；多tenant group必须fail-closed，不能“取第一个”。
- 所有方案都必须保留issuer/signature/time、scope allowlist、header剥离和Phase B-0，且不能信任前端选择。

### 3.6 数据迁移

- A与legacy每用户已有tenant最自然对齐；角色在每org复制，group按org迁移。
- B需编码全局唯一username并改`name→username`展示；迁移映射额外复杂。
- C与A相近，但要验证全局app能对所有org签正确owner。
- D需把tenant关系转换为group membership并重写group ID规则，历史tuple对账更复杂。
- 四案共同需要legacy USER_ID→Casdoor sub crosswalk；不能沿用既有计划“跳过历史subject”的dev假设。

### 3.7 灰度与回滚

- A可并存旧/新tenant apps、registry revision和静态audience emergency fallback；owner协议不变，回滚最好。
- C若从A渐进合并app可双app共存；直接上C则能力验证失败会阻塞。
- B/D需要edge/frontend双claim读和SpiceDB双tuple窗口，回滚成本显著高。
- 所有方案回滚前端到apikey时都必须同时恢复auth-service route/session/filter/secret，既有计划已指出不能只换SPA。

## 4. 隐藏失败场景

1. audience在全集中但owner属于另一个registry record；若只做membership会把合法token错配tenant。
2. registry snapshot先于Casdoor permission完成发布，新租户能登录但全部403或权限不全。
3. snapshot半写/旧revision覆盖新revision，edge部分实例看到不同tenant集合。
4. callback丢失pending tenant，使用默认manager兑换code，表现为state/code失败或错误app。
5. silent renew在切tenant后仍持旧manager事件监听，把旧tenant token写回auth store。
6. 同名用户跨tenant仍被admin store以username patch到错误行。
7. legacy direct scope、tenant role、group role只迁了个人角色，导致有效权限集合缩水。
8. Casdoor role.users变化后permission未刷新，token无预期permissions。
9. SpiceDB旧subject未crosswalk：登录和scope正常，但历史文档owner/viewer全部deny。
10. crosswalk先DELETE旧subject再TOUCH新subject，故障时产生授权丢失；若先TOUCH再分批DELETE，产生短暂双主体访问。
11. GroupSync一次拉取不全，被误判为删除；现有delete threshold是全轮，不是per-org，多租户需重构。
12. auth-service与Casdoor双写时网络超时造成“写其实成功但客户端重试”，没有幂等operation id会重复创建。
13. admin adapter直接把Casdoor 409当version conflict，但真实409语义/响应字段未验证。
14. app漏登记silent/post-logout URI：首次登录成功，续期或登出才失败。
15. `dual`同时带Bearer/API key时Casdoor成功会剥key；不能用key补足缺scope，这是现有正确语义。
16. `EDGE_CASDOOR_MODE=ONLY`过早开启会同时拒绝合法机器API key；机器入口策略必须先明确，或保留独立受控入口。
17. Casdoor撤权后旧access token在TTL内继续有效；不能把admin写成功等同即时撤权。
18. F1误开tenant级语义缓存，acme用户A缓存含其可见文档，用户B命中后绕过ReBAC。
19. registry public list暴露全部tenant名；默认只允许精确lookup，是否公开列表由业务决定。
20. `latest` Casdoor升级改变Admin API/token claim，reconcile或登录无预警漂移。

## 5. 统一评分

每项1–5，5为最好；“复杂度/测试难度/回滚成本”的5分别表示复杂度低、容易测试、回滚成本低。等权总分35，不用高分掩盖release blocker。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 一org一app+动态registry | 5 | 4 | 3 | 4 | 4 | 4 | 5 | **29** |
| B 全局org+custom property | 3 | 2 | 3 | 3 | 4 | 2 | 2 | **19** |
| C 单app多org路由 | 2 | 3 | 4 | 4 | 5 | 1 | 4 | **23** |
| D 平台org+tenant group | 3 | 2 | 3 | 3 | 4 | 2 | 2 | **19** |

评分解释：

- A不是“低复杂度”：动态registry、热更新和高权限reconcile都是真实新增系统，因此复杂度只给3；但它保留现有安全不变量并最易回滚。
- B的app扩展性很好，但单org对象规模和属性隔离降低总评；custom claim未验证使正确性不能给高分。
- C若产品能力成立，结构会很漂亮、扩展性最高；当前最根本能力未由仓库/运行证明，所以正确性2、测试难度1。不能因潜在简洁而确认偏差地选它。
- D有“group即membership”的概念优势，但groups多值、并发和token体积使它并不比B安全。

## 6. 评审结论

采用A为主，不机械照搬全部细节：

- 保留A的org隔离、owner tenant claim、每tenant app和动态owner-aud registry；
- 吸收C的tenant alias/子域体验，但不依赖未验证的单app跨org能力；
- 吸收B的显式稳定claim合同思想：把`owner`作为不可变安全合同而非任意可配置字段；
- 吸收D的group membership统一治理，但GroupSync仍是ReBAC适配，不把group升级成tenant真相源；
- 使用既有rollout的manifest/diff/journal/单写者原则与Phase B-0，不新建第二套scope方案。

所选方案必须在验收记录中保留这些弱点：org/app线性运营成本、registry安全关键性、多对象非事务、subject crosswalk风险、token撤权TTL和admin adapter高权限攻击面。
