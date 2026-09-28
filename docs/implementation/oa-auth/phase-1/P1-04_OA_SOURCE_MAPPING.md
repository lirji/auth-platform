# P1-04 目录来源及适配准备

用户已确认：OA 员工和组织目录为正式员工/部门/离职唯一来源。此记录是已批准来源选择与源码差异，不是 P1-04 已实现；needs=P1-03 已满足（3634a2b，远程 CI PASS），P1-06 停用用例也已完成（9fd58ca）。

| 事实 | OA 现有来源 | auth 消费边界 |
|---|---|---|
| 员工 | oa-org Employee：tenantId、id、userId、status、version | 保留 OA tenant+employee 主键；显式 issuer+userId 映射 Principal；可信来源首次自动创建主体/员工成员；精确来源标识绑定登录身份；不按邮箱合并 |
| 企业 | 既有 OA tenantId | 受控 OA tenant→auth Tenant 映射，不直接采用请求或 Casdoor org |
| 任职/部门 | OrgUnit、EmployeeOrgAssignment、OrgQueryApi | 只消费直接任职事实与同企业树；组授权直到 P3 栅栏完成才启用 |
| 状态 | PROBATION/ACTIVE/LEAVING/LEFT | 前三者仍在职，LEFT 退出；LEAVING 不能提前当作退出。全局主体暂停与成员退出分别处理 |
| 离职 | EmployeeService.leave 事务+EmployeeAssignmentChangedEvent.left | 当前是进程内事件，缺 event_id/source/aggregate_version；不能当可靠跨进程撤权完成回执 |
| 目录增量 | DirectoryController page/delta + DirectoryMapper sync_seq/tombstone | 这是带可见范围的通讯录 API，不直接作为全企业权威同步源；受控发布 API/Outbox需增量建设 |

关键适配工作：在 OA 所属组织用例事务内生成有单调 aggregate version 的目录事件及同库 Outbox，通过公开目录契约投递 auth Inbox。复用已有 Outbox实现经验，不将 IAM 角色事件 Outbox改写成跨模块共写；保留旧进程内 IAM/缓存监听以兼容未切换链路。跨服务不读写 OA 表。

每个事件至少 source/partition/event_id/source_tenant_ref/aggregate_id/aggregate_version/schema_version/payload_hash；同事件重放不重复，同版本异内容隔离，旧离职前事件不能恢复成员。任职变化不能假设 Employee.version已递增：当前 transfer/add/closeAssignment并不总更新员工行，需要专用目录聚合版本及事务内串行分配。

page/delta 当前同时返回 changes和有限数量 deletions；nextSince/hasMore主要随changes推进，不能用该 UI 游标跳过尚未处理的 tombstone，更不能将调用人看不到的员工当不存在。新消费源须证明完整快照 start/end/计数/摘要和增量追平，未完成快照不删除。

离职在 auth 接受并持久化前，OA应显示“权限回收中”或等待确认，不能把现有日志“已触发授权回收”视为跨平台完成。旧进程内事件缺可靠证据的发现已记录，不在准备记录里顺手重构 OA。

下一实现 Owner：P1-04，Q-PROVISION 已确认，接着冻结事件与服务来源认证，独立 OA 任务工作树增量迁移及 auth Inbox/checkpoint真实重放/乱序/快照测试。当前 OA 原脏文件不变。


## 本次补充核查与有界实施顺序

OA 基线 `f07c978`，Spring Boot 3.3.5 / Java 21 / MyBatis-Plus 3.5.5，优先复用现状，不新增 broker。原仓库 CODEX_PROGRESS、既有 identity-authz-governance/PROGRESS_STATE、DEPLOYMENT_RESULT 和 tmp 是用户/其他任务内容，未修改。

生产者覆盖必须包括：

- EmployeeService.create/update/transfer/addAssignment/closeAssignment/setReportingLine/leave；任职变化与员工资料更新都应有权威版本，不能只挂现有两个事件监听器。
- OrgUnitService.create/move/update/dissolve；新消费方只保存直接 parent，不复制计算继承路径或把 path 变化当直接授权。
- OrgSeedService 使用 PostgreSQL COPY 直接写 employee/org_unit/assignment/reporting_line，绕过上述服务。目录接管时必须控制该写入口或生成有完整性证据的初始化批次，不能宣称加了服务 Outbox 就覆盖了全部写入。
- EmployeeService.leave 当前未检查 employeeMapper.updateById 的影响行数；在接入事务事件时应一并校验，否则可能发布未真实提交的离职事实。

当前已确定的不变量：

1. OA 是人员/组织事实唯一写入者；auth 通过受控契约消费，不跨库读取或改写 OA 表。
2. 源端业务变更与目录 Outbox 同事务；版本必须与写入串行关系一致，不能用时间戳或普通 sequence 最大值假装提交顺序。
3. auth 持久化 Inbox、聚合版本和来源/租户映射。重复、旧版本、同版本异内容分别处理；冲突留存并可对账，不用成功响应掩盖失败。
4. 未证明完整的快照不能按缺失员工删人。快照要有明确边界、条数/摘要、游标和增量追平策略；初始化不得直接调用带个人可见范围的通讯录接口。
5. OA LEFT 事实不能隐式恢复手工 SUSPENDED 状态；目录来源与手工安全停用的优先级、重新加入 generation 必须在写入前明确。组驱动授权保持 P3 门禁。
6. 来源凭据固定绑定 source/tenant/issuer/操作，不接受 body 选择其他租户或发行方。默认关闭、专用隔离库验证，不自动接管正式目录。

建议在同一 P1-04 下分实施 pass，保持原稳定节点：来源注册与事件/快照契约 → OA 事务生产者与可靠投递 → auth 幂等消费与状态投影 → 双库真实重复/乱序/断连/完整性测试。任一 pass 完成不把整个 P1-04 标成 DONE。

## 已确认业务输入 Q-PROVISION

用户确认选择：可信 OA 来源自动建立主体和员工成员，登录身份仅凭精确来源标识绑定。
这授权已登记来源的首次开户，不授权按邮箱、姓名、显示名称猜测合并，不自动发放角色或应用准入。
缺失精确登录标识时可保留目录主体事实，但不得制造登录绑定；标识冲突必须隔离，不能覆盖现有主体。
该决定解除 P1-04 首次建成员策略阻塞。Q-EXT 仍只影响后续真实外部业务试点。
