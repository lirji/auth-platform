# Test Plan and Acceptance Criteria

> 视角：test-designer。测试分为纯单元、Spring/AOP 集成、真实 AP+SpiceDB 集成、全链回归和故障注入。当前审计未运行 Maven，避免在允许目录外产生 `target/`；文中是实施阶段必须执行的门禁。

## 1. 单元测试

### SDK 协议

扩展 `auth-platform-sdk/src/test/java/com/lrj/authz/sdk/RemoteAuthzEngineTest.java`：

1. bulk 正序、乱序均按 ResourceRef 对齐。
2. 少项、多项、重复、未请求 resource、缺 resource type/id 均抛 `IllegalStateException`。
3. **新增**：缺 `allowed`、`allowed:null/string/number/object` 均抛；只接受 JSON boolean。
4. **新增**：single check 空 body、缺 allowed、非 boolean allowed 均抛。
5. 重复请求资源明确拒绝或在调用前去重；knowledge 路径应证明只发送互异资源。

完成标准：合法响应映射不变；所有畸形响应均进入异常而不是普通 false。

### knowledge authorization

扩展 `RealKnowledgeAuthzTest`：

- single/bulk 的 permission、resource type/id、subject、full consistency 精确断言。
- bulk-size=2、3 个资源产生 2 批，跨批合并无漏项。
- 第二批抛异常时 enforce 返回空集、shadow 返回原全集，不能保留第一批局部 allow。
- 返回 map 缺 key时按 deny；Remote SDK 正常路径不会出现该情况，但自定义 engine 仍 fail-closed。
- shadow deny/error 指标分别计入 deny/error。

扩展 `KnowledgeQueryServiceAuthzTest`：

- 同 doc 多 chunk 只判一次；deny 文本不进入 reranker fake。
- shared 命中不进 bulk；tenant/shared 同 mergeKey 经 fusion AND 后必须按 tenant 判权。
- null docId tenant 命中 enforce 丢弃，shared null-docId 可放行。
- topK、reranker multiplier、candidate multiplier、max-candidates 边界与整数溢出保护。

### DocumentService

计划新增/扩展聚焦测试：overwrite=edit、list=view bulk、get=view、delete=edit；disabled/shadow/enforce 三态；依赖异常下 get 404/delete false/overwrite 403。断言 shared 分区不调用 document ReBAC，tenant 分区始终传裸 docId 给 `KnowledgeAuthz`。

## 2. Spring/AOP 集成测试

扩展 `CheckAccessShareTest` 与 context 测试：

- controller → 代理 bean 外部调用真实命中切面；禁止以直接 `new` 作为唯一测试。
- `resourceIdParam=documentResourceId` 解析成功，资源精确为 `document:acme_d1`。
- deny 时 viewer 写方法 never；allow 时 exactly once；engine 异常时 never，HTTP 为 5xx 或统一后的明确错误码。
- 三种 mode 的 bean 矩阵：disabled/shadow 无 share controller（若 Q1 沿用），enforce 的 controller/service/resolver/aspect/handler 全部同时存在。
- 编译参数名可用；若构建配置变化导致参数名丢失，测试必须失败。

完成标准：AOP 不因 self-invocation/条件装配/参数名退化而失效。

## 3. 真实 AP + SpiceDB 集成

使用冻结版本 SpiceDB（不得继续以 `latest` 作为可重复验收基线），加载真实 `knowledge.zed`：

1. 新建 document owner+parent_space 后：owner 的 edit/view=true。
2. D3：members group 用户 view=true；同 tenant 非成员=false；另一 tenant 同 docId=false。
3. viewer grant 后立刻 true，revoke 后立刻 false；single/bulk/AOP 均 full。
4. editor 对 edit=true，并按现行业务可 share；viewer 对 edit=false。
5. bulk 1、bulkSize、bulkSize+1、maxCandidates 数量；响应资源乱序代理测试。
6. 删除关系后文档不可见；不存在资源 deny。

完成标准：所有 permission 推导与 schema 一致，无跨租户 true；D3 缺 tuple 时 preflight 明确失败而非继续 enforce。

## 4. 故障注入

| 场景 | shadow 期望 | enforce 期望 |
|---|---|---|
| AP 连接拒绝/超时 | query/list/get/delete check 放行既有行为并记 error | query/list 空、get/delete/overwrite拒绝，不泄漏 |
| AP 401/500 | 同上 | 同上 |
| AP 返回空/坏 JSON | 同上 | 同上 |
| bulk 少项/错 resource/缺 allowed | 全集 + error，不计普通 would-filter | 空集 + error |
| AOP check 依赖异常 | endpoint 不存在（当前规则） | viewer 写 never，响应非 2xx |
| 第二批 bulk 失败 | 全集，不混用首批结果 | 空集，不返回首批 allow |

## 5. 缓存旁路专项

### 当前阶段硬门禁

- `RAG_AUTHZ_MODE=shadow|enforce` + `CONVERSATION_SEMANTIC_CACHE_ENABLED=true`：部署检查必须失败。
- 同一发布的实际容器环境必须证明 cache=false；仅引用 application 默认值不算验收。

### 长期方案验收（本轮不实施）

若未来重开缓存，至少验证：同 tenant 用户 A/B 权限不同不共享回答；revoke 后旧回答不可命中；group/default-space 变更失效；缓存条目无法验证 source 时 fail-closed miss。

## 6. 回归与 E2E

- `/rag/query`：owner/member/viewer/stranger/跨租户/public/null-docId 组合。
- `/rag/documents` list/get/delete/overwrite：404 防枚举、403 overwrite、删除关系顺序。
- share/unshare：edit allow、viewer deny、revoke 即时生效。
- graph/image：enforce 403；shadow/disabled 保持原行为。
- 日志扫描：不得出现未授权正文、embedding text、AP service token；只允许 id/数量/模式。
- 全仓扫描继续断言只有 knowledge-service 引入 SDK，lookup 数量为 0；如新增调用必须更新审计表。

## 7. 命令与发布门

实施 Agent 应按顺序执行：

1. `./mvnw -pl auth-platform-protocol,auth-platform-sdk -am test`
2. 安装本地 SDK 到项目约定 Maven repository 后，`mvn -pl knowledge-service -am test`
3. `mvn -pl conversation-service -am test`（缓存互斥/文档回归）
4. 真实 AP+SpiceDB 集成测试；不可达不得以 skip 作为发布绿灯。
5. `deploy/smoke-rag-tenant-authz.sh` 真实 Casdoor→edge→knowledge→AP→SpiceDB。

最终门禁：单元/模块测试全绿；required E2E 不 skip；故障注入满足表格；semantic cache 冲突组合被阻断；D3 preflight 通过。
