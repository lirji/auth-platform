# 测试方案与验收标准

## 1. 测试原则

1. 安全断言必须同时验证“允许的出现”和“禁止的绝不出现”，不能只断言状态码 200。
2. tenant filter、resource ID、subject ID、permission、批量去重都要做精确参数断言。
3. `disabled`、`shadow`、`enforce` 分别测试；只有 enforce 结果计入授权发布门。
4. fake 单测验证确定性逻辑，真实 ES/SpiceDB/Casdoor 测试验证协议与部署；二者不可互相替代。
5. required E2E 环境缺失应失败并提示前置，不得 assumption skip 后仍通过发布门。
6. 所有测试 fixture 使用独立 tenant/doc/group 前缀，结束后按 manifest 清理，不清空共享环境。

## 2. 单元测试

### 2.1 edge：可信身份

扩充 `CasdoorTokenExchangeFilterTest`：

| 用例 | 输入 | 精确断言 |
|---|---|---|
| 有效 token | owner=tA, sub=uA | 内部 JWT `sub=tA, uid=uA`；Authorization/API key 被剥离 |
| 伪造 tenant header/query | owner=tA，同时传 tB | 内部 tenant 仍为 tA；伪造值不传播 |
| 无 owner | 仅 sub | 401，不进 chain |
| 无 sub | 仅 owner | 401 |
| issuer/audience/过期错误 | decoder reject | only 模式 401；dual 模式只用于灰度且行为固定 |
| tenant claim 配成非 owner | 启动配置 | 严格 profile 启动失败或属性不存在 |
| forged X-Internal-Token | 外部头 | 被剥离，不可绕过认证 |

扩充 `CasdoorDecoderConfig` 测试：issuer、JWKS、audiences 任一缺失都 fail-fast；aud 至少命中一个允许 client ID。

### 2.2 ES：tenant term 不变量

计划新增 `ElasticsearchEsGatewayTest`，对提取出的 search-body builder 做纯 JSON 断言：

- tenant=`acme`、无 category：`bool.filter` 恰有 tenant term，值为 `acme`。
- 有 category：tenant term 仍存在，另有 category term。
- tenant null/blank：抛配置/参数异常，且不会调用 RestClient。
- query 含 ES DSL 特殊文本：仅作为 match 值，不改变 filter 结构。
- limit 0/负数/超大：按服务端边界归一或在上游拒绝。
- `deleteByDoc` 同时包含 tenant/docId 两个 term。

该测试必须检查 JSON 树，不以字符串 contains 代替结构断言。

### 2.3 查询编排与去重

计划新增 `KnowledgeQueryServiceAuthzTest`，注入 fake retrieval source、fake reranker、记录型 `KnowledgeAuthz`：

1. ES 返回 d1#0、d1#1、d2#0，checkBulk 输入 docId 只有 `[d1,d2]` 且顺序稳定。
2. vector/keyword/ES 同时命中同一 doc，仍只判一次文档。
3. checkBulk 允许 d1、拒绝 d2：reranker 只收到 d1 的候选，响应不含 d2 文本。
4. `docId=null`：enforce 丢弃，shadow 保留并打点。
5. authz error：enforce 不返回候选，shadow 返回并产生 error counter。
6. disabled：不调用授权端，结果与既有 `KnowledgeQueryServiceTest` 一致。
7. `topK > maxTopK`、候选乘法溢出：被稳定截断，不发超大 ES/checkBulk；authz 与 reranker multiplier 取最大值而不是相乘。
8. 未授权候选占满前 topK：candidateMultiplier 生效；授权后不足仍返回实际数量并记录 underfill。
9. public enabled：核心严格 profile 启动拒绝或测试明确不纳入本租户断言，防无意 bypass。

### 2.4 `RealKnowledgeAuthz`

扩充现有测试：

- subject 精确为 `user:<sub>`。
- 每个资源精确为 `document:<tenant>_<docId>`，permission=`view`。
- bulkSize 分批后结果并集正确且保持输入映射。
- 任一资源 missing/false 均不进入 readable。
- SDK 抛超时/401/500：enforce 空集，shadow 全集并记录 error。
- tenant/docId null/blank：调用远端前失败。
- `FULLY_CONSISTENT` 精确传递。

### 2.5 SDK 协议

计划新增 `RemoteAuthzEngineTest`，使用本地 mock HTTP server：

- 正常 check-bulk cardinality 与资源顺序映射。
- 少结果、多结果、结果资源与请求不一致、非 JSON、空 body：抛明确协议异常。
- 401/403/500/连接超时/读取超时：抛异常，不返回 allow。
- Bearer service credential 只发往配置的 AP server。

### 2.6 Casdoor group 同步

计划新增 `GroupSyncServiceTest`：

- tenant A/B 都有 `readers`，资源 ID 分别为 `A_readers`、`B_readers`。
- 配置 organizations=[A,B] 时一次 reconcile 都会处理；A 分页失败只禁止 A 的 DELETE，B 的安全变更仍可执行并产生 partial 告警。
- organization 含空格、`/`、`&` 等边界字符时作为单个已编码 `owner` 参数发送，不能注入额外查询参数。
- Casdoor user `id` 原样成为 SpiceDB user subject；name 不误用。
- 首次添加、重复同步零新增、移除成员、空组清理。
- Casdoor 分页/畸形响应/中途失败时，本轮禁止执行 DELETE。
- 删除量超过阈值时熔断，只报告 dry-run。
- 多批次部分失败可重跑，TOUCH 幂等。
- current 集合通过现有 `readRelationships(group, id, member)` 获得 direct tuple；嵌套/计算所得成员不会被当作本同步器可删除的 direct `user` membership。
- organization/group 编码覆盖 `_` 等边界字符与长度上限，碰撞测试通过；不得以字符串 split 还原原值。

## 3. 组件/集成测试

### 3.1 knowledge + fake AP

启动 knowledge-service，使用 fake AP server 记录 `/v1/check-bulk`：

- 通过内部 JWT 设置 tenant/sub。
- 写入同租户 d1/d2 与另一租户 d3 的 ES fixture。
- 查询断言 ES 只召回当前 tenant；AP 只收到当前 tenant resource。
- AP 对 d1 allow、d2 deny；响应只含 d1。
- AP 延迟超过 SDK timeout；enforce 无正文并有 error 指标。

### 3.2 AP server + 真实 SpiceDB

加载真实 `knowledge.zed`，执行：

1. 写 `group:t_readers#member@user:u`。
2. 写 `space:t_default#viewer@group:t_readers#member`。
3. 写 d1/d2 parent_space，其中 d2 另有直接 viewer。
4. `/v1/check-bulk` 断言组继承、直接授权、无权用户。
5. 撤销 membership 后使用 fully-consistent 立即判否。
6. tenant A/B 相同裸 docId 与相同组短名不串权。

AP server 开启 security 后，无 token/错 token 401，正确 service token 200。

### 3.3 knowledge + AP + SpiceDB

把现有 `KnowledgeAuthzIntegrationTest` 分为：

- developer optional：服务不在可跳过。
- CI required profile：环境缺失直接失败。

required 场景：owner 上传可见、同租户未授权不可见、viewer grant 后可见、revoke 后立即不可见、跨租户不可见、list/get/delete 与 query 语义一致。

### 3.4 真实 Elasticsearch

真实索引写入：

- tenant A/B 使用相同关键词和 category；A 查询返回的 `_source.tenantId` 全部为 A。
- 抓取 slowlog/代理记录或通过测试 gateway 暴露 request body，确认 tenant term。
- category 特殊字符仍按 keyword term 精确匹配。
- ES 404 返回空；ES 500 时 ES source 降级，但其他源结果仍必须经过 ReBAC。

## 4. 端到端 required 测试

计划新增 `deploy/smoke-rag-tenant-authz.sh`，明确依赖真实 Casdoor、edge、knowledge、ES、AP server/admin、SpiceDB。流程：

1. 创建测试 organization/tenant（或使用隔离的已有测试 org）。
2. 创建用户 U1/U2，记录各自 `sub`；确认 token `owner=<tenant>`。
3. 分配可获得 `chat` 的 role；把 U1 加入 `readers` group，U2 不加入。
4. 运行 Casdoor→SpiceDB group sync，断言 tuple 是 `group:<tenant>_readers#member@user:<U1-sub>`。
5. 绑定 `space:<tenant>_default#viewer@group:<tenant>_readers#member`。
6. 以已授权上传者创建文档 D，确认 ES tenantId 和 document parent_space tuple。
7. U1 登录拿真实 access token，经 edge 调 `/rag/query`：命中 D。
8. U2 同租户查询：不命中 D。
9. tenant B 用户用相同 query/category 查询：不命中 tenant A 文档。
10. 把 U2 加组并同步：立即命中；移组并同步：立即不命中。
11. 停 AP/阻断 checkBulk：enforce 不返回 D；恢复后恢复正常。
12. 清理只删除本测试 manifest 中的 user/group/tuple/doc，不执行全库清空。

脚本必须验证响应正文中的 `docId/text/tenantId`，不能只验证 HTTP 状态。

## 5. 回归测试

- `mvn -pl knowledge-service -am test`：既有 query、ES fusion、tenant isolation、public（在非严格 profile）、document lifecycle 全绿。
- `mvn -pl edge-gateway -am test`：session/API-key dual 灰度回归和 Casdoor-only 新行为分别测试。
- auth-platform：protocol/core/sdk/server/admin 全量测试；`spicedb-smoke.sh`、`server-smoke.sh`。
- conversation/agent/channel/eval 的 `KnowledgeQueryRequest` 构造与契约测试不改。
- `disabled` profile 快照：不调用 AP，返回排序/分数/source 与基线一致。

## 6. 异常与安全测试矩阵

| 分类 | 用例 | 通过标准 |
|---|---|---|
| 认证 | forged/expired/wrong aud token | 401；无内部 JWT |
| 租户 | header/query/body 伪造 tenant | 无效；ES term 仍等于 owner |
| 授权 | deny/missing response | 文档正文不返回 |
| 依赖 | AP/SpiceDB timeout/5xx | enforce fail-closed，指标告警 |
| 协议 | bulk 少项/错序/重复 | 明确 error，零误放行 |
| 输入 | 超大 topK、blank query/category 特殊字符 | 有界执行，无 DSL 注入 |
| 并发 | grant/revoke 与查询并发 | revoke 完成后的 fully-consistent 查询全 deny |
| 幂等 | 重复 group sync/backfill | tuple 数不增长、结果不漂移 |
| 数据 | 历史文档无 owner | 不伪造 owner；按 parent-space policy 决定 |
| 公共库 | strict profile 误开启 public | Spring 启动失败；不会进入可服务状态 |

## 7. 性能与容量测试

至少测试候选数 10/50/100/200、允许率 0%/10%/50%/100%、并发 1/20/100：

- 记录 ES latency、AP HTTP latency、SpiceDB checkbulk latency、总 query P50/P95/P99。
- 比较 minimize-latency 与 fully-consistent，但首期不能只因更快就改一致性。
- 验证 maxCandidates/bulkSize，不产生超限 request。
- 监控授权后 underfill 比例。
- 验收阈值需由目标环境基线确定；当前代码未提供生产 SLO，数值标记为待验证，禁止在文档中臆造。

## 8. 发布门

- [ ] 所有安全单测通过。
- [ ] 真实 ES tenant A/B 隔离通过。
- [ ] 真实 SpiceDB group/space/document 继承与撤权通过。
- [ ] Casdoor→edge→knowledge→AP→SpiceDB required E2E 未跳过且通过。
- [ ] disabled 回归通过。
- [ ] shadow 指标观测窗口满足业务确认；无未知 owner/group 大缺口。
- [ ] enforce 故障注入无正文泄露。
- [ ] 性能压测满足待确认 SLO，或已批准容量限制。
- [ ] 回滚演练成功。
