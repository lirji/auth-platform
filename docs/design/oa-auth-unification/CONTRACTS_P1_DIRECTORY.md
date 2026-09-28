# P1-04 OA 权威目录接入契约

沿用原 P1-04，不重划平台边界。用户已确认 OA 是员工/组织/离职唯一事实源，
可信 OA 来源可自动建立主体和员工成员，登录身份仅凭精确来源标识绑定。
Q-PROVISION 已关闭；角色、应用准入与组驱动权限不在本片开启。
本文件定义后续实现与验收要求，本身不代表能力已交付。

## 数据所有权与入口

OA 的 oa-org 保持员工、组织、任职和汇报线唯一写入口，写入自身数据库。
auth 仅消费最小目录事实，在自己的治理库维护主体、成员、来源映射及目录投影；
禁止直接连接 OA 库读取/写入业务表。不创建或修改 Casdoor 账号，不按邮箱/姓名合并。

本片选用持久化 Outbox + 受控 HTTP 拉取 + auth Inbox/checkpoint，复用 PostgreSQL 和 Java HTTP。
不引入 broker、常驻新服务或无界自动重试。可靠性来自数据库事实和幂等，而非请求成功日志。

OA 增加默认关闭的目录出口；固定服务凭据只能访问部署配置登记的一家 OA 企业。
`GET /internal/directory/v1/events?after_sequence=<n>&limit=<n>` 返回不超过 100 条持久化事件，
`GET /internal/directory/v1/status` 返回来源、当前最大已提交序号、已确认序号及积压状态。
`POST /internal/directory/v1/ack` 只确认已有事件的连续序号与摘要，不更改员工业务状态。
精确路径采用独立服务认证，不复用员工 JWT、isAdmin 或随意传入 tenant header。
凭据高熵、服务端只存 SHA-256，恒定长度比较；HTTPS（隔离回环除外），日志不含原文。

受控 DirectoryImportCli 单次读取有限页、在治理库逐事件提交，再回传已连续提交的检查点。
配置为 0600 文件，固定 source/environment/OA tenant/auth tenant/issuer/source URL/读取凭据。
只有既有治理库操作权的运维可运行，不提供匿名注册/任意 URL/租户的管理 HTTP。
配置和治理库来源登记不一致时拒绝启动，不能把旧游标复用于新来源或新租户。
既有治理企业必须先显式建立，来源不能根据请求临时创建企业。

## 源端事务与顺序

新增 oa-org 自有目录来源登记、版本/序号、不可变 Outbox、确认检查点；不共写 oa-iam 的角色 Outbox。
登记的 OA tenant 是分区。每个受影响写用例在修改业务数据前锁定该 tenant 的目录门闩，
业务修改、聚合版本递增及 Outbox 写入同事务提交。锁与版本不跨网络调用。
每次事件取得唯一、连续的分区序号，回滚不留下可被误认为已提交的序号空洞。
禁止用 employee.version、普通 sequence 最大值或 occurred_at 充当可靠提交顺序。

覆盖 EmployeeService 的 create/update/transfer/addAssignment/closeAssignment/setReportingLine/leave，
覆盖 OrgUnitService 的 create/move/update/dissolve。事件在用例最终状态形成后产生。
离职更新必须检查影响行数，失败回滚业务与事件。保留既有进程内事件兼容未切换链路。
任职/汇报线变化也递增专用员工目录聚合版本。

OrgSeedService 的 COPY/truncate 绕过上述事务服务，已登记目录来源存在时必须拒绝这些入口。
只读检查不能与登记并发形成窗口；登记与批量入口共享数据库互斥边界。
未登记的隔离演示环境沿用原行为，不修改既有真实数据。
正式接管前需确认所有写实例已具备捕获能力，再建立初始化快照；旧写程序不能共存于已接管来源。
本片只在隔离环境证明，不宣称完成正式切流。

## 稳定事件与最小事实

版本 1 envelope：schema_version、event_id（UUID）、source、environment、source_tenant_ref、
partition_sequence、aggregate_type、aggregate_id、aggregate_version、occurred_at、payload、payload_hash。
序号/版本为正整数；同租户同聚合版本只对应一份内容，event_id 在来源范围唯一。
事件在源端生成一次并持久化；重试发送同一字节事实，不重新生成 ID 或时间。
摘要按文末共享协议编码计算，双方使用固定字段顺序、显式 null 与稳定数组排序的协议向量测试。
正文和字段有上限，员工任职/汇报关系最多各 100 条；超限必须显式失败，不截断伪装完整。

EMPLOYEE：OA employee ID、精确 userId（可缺失）、status、直接任职列表和直接汇报线。
不传邮箱、手机号、证件号、姓名等本片无需的个人字段。
PROBATION/ACTIVE/LEAVING 均表示在职，LEFT 表示退出，未知状态拒绝。
任职保留 source assignment ID、org ID、type、is_leader、valid_from/valid_to；
汇报线保留 source ID、manager employee ID、type 和有效期，时间语义保持 OA 日期含义（左闭右开）；出口保留当前和未来关系，valid_to <= OA 当天的历史关系仍保存在 OA，不再重复导出。
只投影直接关系，不复制 closure/path 为授权关系。

ORG：OA org ID、parent ID、status；ACTIVE/FROZEN/DISSOLVED 沿用 OA 显式代码，冻结事实不映射为新增授权。
拒绝同企业部门树环、跨企业引用与自环；未知父节点事实可等待后续完整目录，不能作为授权树使用。
过深关系有明确处理上限，不能截断后当验证成功。

## auth 消费事务与身份策略

Inbox、聚合版本、来源事实、主体/成员/LegacyBinding、追加审计和检查点均在本地事务内。
无远程调用置于治理事务中。唯一约束保护 source/event_id、分区序号和聚合版本。
同事件同内容为幂等重放；同事件/序号/版本异内容记录冲突并停止正常推进，返回非成功状态。
冲突证据独立留存，不因回滚业务事务丢失，不用日志替代持久化隔离记录。
旧聚合版本不能覆盖新事实；检查点只沿已完成的连续序号推进，乱序不能跳过缺失项。

固定来源映射将 OA tenant 绑定到一个 auth Tenant 和一个精确 issuer。
员工身份主键为 source + source tenant + employee ID；既有 LegacyBinding 优先且不可重绑。
有效 userId 仅作为该 issuer 的精确 subject；无绑定时可创建 HUMAN Principal 和 LoginIdentity。
首次复用精确登录绑定只允许 ACTIVE HUMAN；SERVICE、全局停用或外部类型冲突不能被来源转成员工。
已存在同来源员工映射时，全局暂停不阻断离职等来源事实处理；不得改变主体的全局暂停状态。
同企业同主体不允许无审计地绑定多个 OA employee ID；冲突进入隔离，禁止覆盖已有映射。

userId 缺失时，允许创建来源专属 HUMAN 主体及员工成员，保持无 LoginIdentity，不能通过邮箱补绑定。
后续补齐精确标识只可绑定同一来源主体；标识已绑定其他主体时隔离，不自动合并。
同一员工已有登录标识发生改变，也必须隔离等待显式身份迁移，不静默解绑旧身份。

首次在职创建 EMPLOYEE ACTIVE，generation/version=1；首次 LEFT 保存退出事实及 LEFT 成员，
不建立有效访问上下文。ACTIVE→LEFT 时 version+1；LEFT→在职在新来源版本下 generation/version+1。
手工 SUSPENDED 优先：目录可更新来源事实，但任何在职/离职事件都不得解除暂停或绕过安全控制。
全局主体暂停也不被目录恢复。旧版本在职事件不能恢复离职成员。
当前状态实时读取沿用 P1-03，无新正向权限缓存。

## 初始化快照、对账与删除边界

受控源端初始化在登记/写入互斥边界内生成持久化批次，包含 snapshot_id、BEGIN/END、
事件数量、确定顺序摘要与分区起止序号。分批传输可恢复，源端封存后内容不变。
初始化物化最多 10000 条业务记录、16 MiB 编码正文、60 秒事务；可配置更低行数上限。超限整批回滚登记及快照，不构成生产容量达标承诺。
不能通过调用个人可见范围的通讯录 API 生成企业全量快照。

auth 只有核对 BEGIN/END、连续序号、数量与摘要后才标记快照 COMPLETE。
缺页、重复页、断连、错误结束摘要不会把快照标完整；已处理事件可安全重放。
本片不实施“缺失即删除”，即使完整快照也只导入明确事实。
删除阈值等价为禁止所有基于缺失的批量删除；员工退出必须有显式 LEFT 事实，组织撤销必须显式 DISSOLVED。
快照后继续消费其结束序号之后的持久化增量，不跳过期间提交的事件。

## 确认、故障与可观测

消费者成功提交但确认响应丢失：源端重发，Inbox 重放后回传同一连续检查点。
OA 保存 auth 确认序号；离职之后到对应事件被确认之前保持“权限回收中”语义。
原日志不得再将“已发布进程内事件”描述为跨系统撤权完成。
状态接口区分待同步/已确认/冲突；不以源端离职时间宣称其他平台已立即失权。

连接/读取超时有界；单次 CLI 最多处理配置上限（不超过 1000 条），失败非零退出，
不内嵌无截止时间循环。网络/5xx 可由下一次运行重试，契约冲突需处置，不盲目重试毒消息。
不清空 Outbox/Inbox/审计；正式保留期另行确认。备份/生产容量/RTO 不在本片虚构承诺。

## 实施 pass 与必需验证

1. 冻结本契约及规范摘要向量；auth 来源模型/Inbox/投影/生命周期真实 PG 验证。
2. OA 增量迁移、事务捕获、受控初始化和出口；隔离 PG 证明业务与 Outbox 原子性、序号提交顺序。
3. 受控拉取导入/确认闭环；双库真实验证重复、乱序、同版本冲突、断连重试与未完成快照不误删。
4. 真实 OA 在职 → auth 成员/当前上下文 → OA 离职 → auth LEFT，保持已有 Casdoor 精确身份测试。
5. 部门自环/多节点环、跨企业、COPY/truncate 保护、暂停不恢复、无内部默认应用权限均有负例。
6. 源/目标旧接口回归、默认关闭、迁移兼容及代码质量检查；同步文档、CI 与两仓交付证据。

各 pass 不能单独把 P1-04 标为 DONE。完成 P1-04 后执行 P1-07，再按用户要求暂停，不进入 P2。

## 双方共享协议与摘要定稿

OA 已有版本管理包含 auth-platform-protocol，复用这个无框架依赖的协议包，避免手写两份摘要算法。
JSON 用 snake_case；拒绝重复键、未知字段、尾随内容与浮点/文本序号。
事件的 payload 只有按 aggregate_type 对应的 employee、organization、snapshot 三者之一，其他必须为 null。
员工/部门源 ID 使用十进制正整数字符串；snapshot/event ID 使用规范 UUID。
缺失 user_id 显式 null，数组空集合不使用 null，直接关系按自身 source ID 的数值升序排列且不能重复。

payload_hash 不包含 event_id、occurred_at 或分区序号，只覆盖类型及其业务事实，用于同版本异内容判定。
另计算 event_fingerprint，覆盖完整 envelope 及 payload_hash，用于事件 ID/序号重放和传输完整性。
规范摘要采用共享协议的长度前缀 UTF-8 字节编码，而不是依赖 JSON 属性遍历顺序；
整数为大端有符号 64 位，布尔为一个字节 0/1，字符串为大端 32 位字节长度后跟 UTF-8，
null 字符串长度为 -1，数组为 32 位项数后逐项编码。所有字段顺序按协议 record 声明固定。
所有摘要 SHA-256，小写十六进制；日期为 YYYY-MM-DD，时间使用 Instant 规范 UTC 微秒字符串。
禁止 JSON 与二进制摘要各自推断字段，协议测试向量需同时被源和目标使用。

快照事件带 snapshot_id；BEGIN/END 载荷同为 start_sequence、end_sequence、expected_count、content_hash。
范围包含 BEGIN/END，expected_count=end_sequence-start_sequence-1。
content_hash 为中间事件按分区序号排列后的 32 字节 event_fingerprint 串联再 SHA-256。
不把 BEGIN/END 自身摘要加入内容摘要，避免递归定义。结束确认必须同时匹配开始声明与实际内容。
普通增量 snapshot_id=null；同一来源一次只初始化一个批次，失败后用同批 ID 恢复，不覆盖历史声明。

## P1-04 来源出口实施细节

事件页正文 `{ "events": [<event>...] }`，继续沿用上面的 snake_case 事件协议。
状态正文为 source、environment、source_tenant_ref、last_sequence、acked_sequence、acked_fingerprint、backlog；
backlog 为最后提交与已确认的差值，不代表消费者已处理但未确认的精确数量。
确认正文严格只有 `sequence`（正整数）与 `fingerprint`（64 位小写 SHA-256）；最长 1024 字节。
同摘要的旧确认可以幂等返回最新状态，不降低水位；未知序号或错误摘要返回 409。
出口默认关闭返回 404，无服务凭据 401，非 TLS 且非显式许可的本地回环连接 403。
HTTP 配置开关不停止已登记来源的事务捕获。

OA 用 `@RequiresServiceIdentity("oa-directory")` 声明独立服务身份；不复用 PublicApi 或员工权限。
HTTP 认证链、运行时 Handler 守卫、出口用例共同检查类型化服务身份；租户只取部署配置。
固定企业的系统目录 Mapper 保持 MyBatis 拦截器启用，系统读取声明 DataScopeBypass 审计元数据，
只查询本域事实表，不借个人通讯录视图生成快照。
初始化 CLI 当前只支持 0600 配置的隔离回环 PostgreSQL，前置执行真实 OA 迁移；不自动迁移、建库、清空或替换共享实例。
