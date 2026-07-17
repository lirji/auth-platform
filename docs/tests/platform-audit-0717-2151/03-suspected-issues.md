# 03 — 交互逻辑疑似问题

以下均由仓库当前实现直接推导；它们不是测试应锁定的“正确行为”。对应失败契约在测试草案中只以 `TODO(issue-...)` 描述，须先确认/修复生产语义再启用。

## P0：判权结果可信度与 fail-closed

### ISSUE-C01：LookupResources 缺 `permissionship` 反而被当作有权限

- 预期行为：每个 lookup result 必须有非空、可识别的 permissionship；缺字段/空字段是协议错误，应抛异常，不得返回资源。
- 现状：`SpiceDbAuthzEngine.lookupResources` 的条件是 `permissionship.isEmpty() || permissionship.endsWith("HAS_PERMISSION")`，空值直接进入收集分支（约第 120–129 行），与同类 `check`/`checkBulk` 的严格注释冲突。
- 复现路径：SpiceDB stub 对 `/v1/permissions/resources` 返回 `{"result":{"resourceObjectId":"secret"}}`；调用 `lookupResources(user:u1,"view","document",...)`，当前得到 `["secret"]`。
- 建议处置：空/未知 enum 抛 `IllegalStateException`；只接纳明确的 `LOOKUP_PERMISSIONSHIP_HAS_PERMISSION`（是否兼容未来枚举名待验证）。修复后启用 core 草案中的 TODO 回归。

### ISSUE-C02：流式端点的 error/畸形项被静默跳过或构造成空对象

- 预期行为：流中任一顶层 `error`、缺 `result`、缺关键 id/tuple 字段，应使整次调用失败；不得返回“可信的部分结果”。
- 现状：`lookupSubjects` 只找 `result.subject.subjectObjectId`，顶层 error 被跳过；JSON `null` id 会被转成空字符串；`readRelationships` 对缺 relationship 跳过，但对存在 relationship 且内部字段缺失时构造空 `ResourceRef`/`SubjectRef`。`postStream` 只负责语法解析。
- 复现路径：返回两段拼接 JSON：第一段合法 `u1`，第二段 `{"error":{"code":"internal"}}`；当前 `lookupSubjects` 返回 `[user:u1]`。或返回 `{"result":{"relationship":{"relation":"viewer"}}}`，当前读出空 type/id tuple。
- 建议处置：为各流式消息定义严格结构校验和 error 检测，任一项失败即抛；错误信息不回显完整响应体。

### ISSUE-C03：空 2xx 响应在多数端口被伪装成正常空值

- 预期行为：需要响应契约的 2xx 空 body 是协议错误。
- 现状：`post` 将 null/blank 解析为 `{}`，`postStream` 将 blank 解析为 `[]`；因此 `writeRelationships`/`deleteRelationships` 返回 token=null，`readSchema` 返回空串，`expand` 返回 `{}`，lookup/read 返回空集合。只有 check/checkBulk 因后续严格校验会抛。
- 复现路径：HTTP 200、零长度 body；调用上述任一方法。
- 建议处置：按操作明确是否允许空结果；写/删/schema/expand 必须严格，lookup 的“无结果”应是有效流结束但需确认 SpiceDB 空流协议，不能把截断与正常结束混为一谈。

### ISSUE-C04：写/删缺 ZedToken 仍作为成功返回，水位不会推进

- 预期行为：写操作成功响应必须含非空 `writtenAt.token`/`deletedAt.token`，否则上游无法保证 read-after-write，应按依赖协议故障失败。
- 现状：core 用 `asText(null)` 构造 `ZedTokenView(null)`；server 返回 200 token=null，`ZedTokenWatermark.advance` 又忽略空 token。
- 复现路径：stub 返回 `{"writtenAt":{}}`，经 core→server `write`，可得到空 token success。
- 建议处置：core 首先严格校验；server 再做防御性非空校验，避免替换引擎时回归。

### ISSUE-S01：server bulk facade 把引擎漏项静默降级为 deny

- 预期行为：`AuthzEngine.checkBulk` 返回值必须精确覆盖请求资源；缺项、额外项或 null 都是端口协议故障，应抛出，让调用方区分 deny 与依赖错误。
- 现状：`AuthzController.checkBulk` 以 `Boolean.TRUE.equals(map.get(r))` 组装响应；缺项/null 都变为 `allowed=false`。SDK 随后的严格响应校验无法发现，因为 server 已补齐了每个请求资源。
- 复现路径：mock engine 对请求 `[d1,d2]` 返回 `{d1=true}`；controller 当前响应 `d1=true,d2=false`。
- 建议处置：server 校验 key 集合、非 null value 和重复请求策略；修复前不要写“缺项应 false”的测试。

### ISSUE-S02：并发写完成顺序可使单实例水位回退

- 预期行为：自动代入的水位不能比本实例已经观察到的更新旧。
- 现状：`AtomicReference.set` 仅保证线程安全，不保证 token 因果顺序。若较新的写 B 先返回并 `advance(B)`，较旧的写 A 后返回并 `advance(A)`，最终缓存 A。代码注释也只承诺“单实例串行写”。SpiceDB ZedToken 又不可直接比较。
- 复现路径：两个并发请求；让 A 先提交但延迟 HTTP 返回，B 后提交先返回。按 B→A 顺序调用 `advance`，最终 `latest()==A`。
- 建议处置：明确将 feature 限定为串行/提示 token；或让调用者始终回传自己的 token；如需并发全局语义，需 SpiceDB 支持的 revision 顺序或外部单调水位，不能靠字符串比较。测试只锁定原子可见和 blank 忽略，不锁定“最后线程获胜就是最新”。

## P0：AOP 与输入契约

### ISSUE-A01：AOP 参数元数据缺失时抛 NPE，且 null 资源变成字面量 `"null"`

- 预期行为：参数名不可发现、参数数组不匹配、命中值为 null/blank 时，判权前明确 fail-closed，错误说明配置或输入问题；不能查询伪资源。
- 现状：`resolveParam` 直接遍历 `names.length`，`getParameterNames()==null` 时 NPE；命中 null 参数时 `String.valueOf(null)` 得到 `"null"`，随后会调用 `engine.check(... document:null ...)`。
- 复现路径：mock `MethodSignature.getParameterNames()` 返回 null；或 names=`[docId]`、args=`[null]`。
- 建议处置：显式校验 metadata、数组长度和 id 非空；异常消息不要泄漏敏感实参。修复后启用 AOP 草案 TODO。

### ISSUE-A02：需求口径中的 SpEL/下标取参与当前注解契约不一致

- 预期行为：若产品要求 `#request.id`、`arg[0]` 或嵌套属性，应有明确语法、解析失败策略和注入防护。
- 现状：`CheckAccess.resourceIdParam` 文档只承诺“参数名”，实现只做字符串等值匹配；仓库没有 SpEL parser 或下标语法。把 SpEL 场景写成当前成功断言会虚构能力。
- 复现路径：注解配置 `resourceIdParam="#request.id"`，实际参数名 `request`；当前抛“未在方法参数中找到”。
- 建议处置：产品确认只支持参数名还是扩展表达式。若扩展，优先受限解析器/明确 allowlist，禁止任意方法调用；单列安全测试。当前蓝图只锁定精确参数名，SpEL 标为待验证。

## P1：Casdoor、多租户和对账

### ISSUE-CAS01：Casdoor 合法但缺 `data` 的 200 响应被当作空快照，可能触发撤权

- 预期行为：用户/组/角色 API 的 schema 不完整应中止整轮，不写 SpiceDB。
- 现状：`get` 把 blank 变 `{}`；调用方对 `.path("data")` foreach，缺失即空。若 groups API 正常列出组、users API `{}`，`GroupSyncService` 会把这些组的 desired 成员视为空并删除当前成员（只受阈值限制，阈值内仍会删）。
- 复现路径：`get-users?owner=acme` 返回 `{}`，`get-groups` 返回组 g；SpiceDB 当前 g 有 u1，阈值 1000；同步生成 DELETE u1。
- 建议处置：CasdoorClient 校验顶层及 `data` array；将“业务上确实为空”与“响应缺失/截断”分开。

### ISSUE-CAS02：已从 Casdoor 删除的组/部门不会进入差量集合，旧 tuple 永久残留

- 预期行为：全量对账应能撤销已删除实体的直接关系，或明确有 tombstone/webhook 删除流程。
- 现状：`GroupSyncService` 的 groups 仅是当前 `groupNames ∪ desired.keySet`；`DepartmentSyncService` 同理只遍历当前 snapshot keys。SpiceDB 端没有按租户枚举全部现存 group/department，因此被 Casdoor 完全删除的实体不被读取、不被清理。
- 复现路径：上一轮有 `group:acme_g#member@user:u1`；Casdoor 删除 g 后 `groupNames={}`、`groupMembers={}`；`sync()` groups=0，不调用 read/write，旧访问仍可能生效。
- 建议处置：维护已同步 manifest/tombstone，或增加按租户前缀安全枚举并纳入熔断；明确删除实体生命周期。

### ISSUE-CAS03：完整组/父/角色 owner 引用可跨当前 organization 写入

- 预期行为：遍历 org=acme 时，用户 group、parentId、role owner/user ref 要么必须同 org，要么经过显式跨租户授权规则；默认应 fail-closed。
- 现状：`scopedGroupId` 信任 `<other>/<group>` 的 org 段；group/role 的 `owner` 也直接编码；role user ref 丢弃 org 段后只按用户名在当前 org 查找。异常或恶意 Casdoor 数据可能把 acme 用户写入 other 的对象，或把 other/alice 误解析为 acme/alice。
- 复现路径：acme users 返回用户 u1、groups=`["beta/admin"]`；当前 `groupMembers()` 生成 `beta_admin -> u1`。
- 建议处置：默认校验引用 org 与请求 org 相等；若确需跨 org，增加显式 allowlist 和审计。

### ISSUE-CAS04：organization 直接拼 query，未编码且配置未先校验

- 预期行为：query 参数应由 URI builder 编码；非法 org 应在发请求前 fail-fast。
- 现状：路径形如 `"/api/get-users?owner=" + org`。空格、`&`、`?` 等会改变请求语义；而安全编码只在解析返回对象时才触发。
- 复现路径：organizations=`["acme&owner=beta"]`，观察服务端 query。
- 建议处置：使用 URI variable/builder，并在 properties 绑定后统一校验组织名。

### ISSUE-R01：ReconcileJob 两阶段无共同事务/故障隔离，可能长期半同步

- 预期行为：应明确组同步成功、部门同步失败时的可观测性和重试语义；不能误报整轮完成。
- 现状：先 `sync.sync()` 再可选 `departmentSync.sync()`；第二步失败时第一步已提交，Spring scheduled 默认仅记录异常，下轮才重试。反向则 group 失败导致 department 不执行。
- 复现路径：group mock 成功写入；department mock 抛异常。
- 建议处置：接受最终一致性则加分阶段指标/告警和独立重试；要求原子则现有两个 SpiceDB write 无法提供跨调用原子性，应合并计划后单写。测试锁定调用与异常传播，不假称事务原子。

## P1：admin、审计与输入

### ISSUE-ADM01：授权写成功后审计失败，客户端看到失败但授权已生效且无审计

- 预期行为：安全变更不能出现“数据面已变、审计缺失”；至少要有可靠 outbox/补偿与明确错误语义。
- 现状：`AdminController.grant/revoke` 先 `engine.writeRelationships`，再 `audit.record`。后者抛异常不会回滚 SpiceDB。客户端重试 grant（TOUCH）还会制造重复审计；revoke 的重试语义也依赖 SpiceDB DELETE 行为。
- 复现路径：engine 返回 token t，audit mock 抛 `RuntimeException("db down")`；controller 抛错，但 verify engine 已写。
- 建议处置：设计可靠审计（outbox/写前 intent+完成状态/重试队列）；在决定前仅测试“engine 失败不审计”，不把审计失败后的部分成功当正确行为。

### ISSUE-ADM02：公共 DTO/协议 record 无任何非空、非 blank 或组合约束

- 预期行为：object type/id、relation、permission、AT_LEAST token、ZedToken 应在边界明确验证；无效值不应深入 `Map.of` 形成 NPE 或发往外部服务。
- 现状：protocol 和 server/admin DTO 都是无 compact constructor 的 record；`Consistency.atLeastAsFresh(null)` 可构造，后在 core `Map.of("token", null)` 才 NPE；`ResourceRef.of(null,"x")` 可构造；controller 无 `@Valid`。
- 复现路径：`Consistency.atLeastAsFresh(null)` 后调用 core `check`；或 `new GrantRequest(null,... )` 调 admin。
- 建议处置：在 protocol 值对象集中定义约束，并在 HTTP DTO 增加 Jakarta Validation/显式校验；统一异常映射。协议测试先锁定合法工厂，非法输入测试为 TODO，不断言当前“允许 null”。

### ISSUE-AUD01：InMemoryAuditStore 的并发容量裁剪不是复合原子操作

- 预期行为：并发 record 后容量不超过 capacity，且不应因为并行裁剪长期少于 capacity；“最近”顺序应有定义。
- 现状：`addFirst`、`size`、`pollLast` 分别线程安全，但 `while(size>capacity)` 不是原子临界区；多个线程可能同时观察超限并多次 poll。`ConcurrentLinkedDeque.size()` 还是 O(n) 且并发下是瞬时视图。
- 复现路径：小 capacity（1/2），两个线程 barrier 后同时 record，多轮压力可观察过度裁剪；该复现受调度影响，不应做默认 flaky 回归。
- 建议处置：锁住 add+trim，或改用有界结构/原子计数；用可控 hook 后写确定性并发测试。当前草案只稳定验证并发“不超容且记录结构完整”，精确不丢待修复后测。

## P2：安全边界与构建可测性

### ISSUE-SEC01：`AuthzServerSecurityFilter` 不保护精确 `/v1`

- 预期行为：若安全策略定义为 `/v1/**`，需确认根 `/v1` 是否也在保护集合；新增 root handler 时不应意外裸奔。
- 现状：判断是 `requestURI.startsWith("/v1/")`，精确 `/v1` 走放行。当前 `AuthzController` 没有 root handler，因此暂时只会落到 404，但这是易随路由新增变化的旁路。
- 复现路径：enabled=true、URI `/v1`、无 header，filter chain 会执行。
- 建议处置：安全口径若包含根路径，条件改为 `equals("/v1") || startsWith("/v1/")`；否则把例外写进明确文档和回归。

### ISSUE-BLD01：protocol 模块当前无法直接落地约定格式的测试

- 预期行为：每个有生产代码的模块都具备 JUnit5/AssertJ test classpath。
- 现状：`auth-platform-protocol/pom.xml` 无 test dependency，基线显示 `No tests to run`。草案若直接放入 `src/test` 会 testCompile 找不到 JUnit/AssertJ。
- 复现路径：落地 `ProtocolValueObjectsTest` 后执行 `mvn -pl auth-platform-protocol test`。
- 建议处置：获准后添加 `spring-boot-starter-test` test scope（构建配置变更，不是业务行为变更）；本轮按硬约束不改 POM。
