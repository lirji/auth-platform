# CE-03-I 库存员工用例接管（实施中）

前置CE-02契约及有限资源协议已合并auth main1b1ee01，CI36663171698 SUCCESS。当前auth分支feat/central-inventory-reference、commerce分支feat/central-inventory-access，均在原目录；无新worktree。

实现范围：GET /v1/admin/inventory 与 POST /v1/admin/inventory/receipts。中央仅OPERATOR+精确inventory能力引用，沿用原Actor的executionId，不引入ADMIN。新EmployeeAccess端口、V49持久INVENTORY权威路由防旧管理员/缺头回退；Commands.runGuarded在回执读取前锁路由及真实门店版本，许可最多5秒；V50将主体/成员/代际/能力/执行引用与成功库存命令同事务记录。列表在tenant+store SQL分页前授权且返回前复核。内部订单预占/释放/售后回补不使用员工Grant。

中央引用支持精确application前缀下的inventory.read/receive，最长60秒；既有CATALOG最长37天行为保留，其他能力不因通配规则开放。真实ExecutionAuthorizationIT两项通过，包括旧CATALOG全部测试、新库存只读不能入库/换能力、越店与撤权。自有PG3536b7506d2a已停止，私有日志auth/.local/governance/commerce-contracts/execution-integration.log。auth hygiene无阻断，格式化器不可用及既有测试超时数字提示保留。

商城：真实MySQL+HTTP的CentralInventoryMySqlTest新增4项初次通过；最终增加HTTP入库及Owner锁后许可检查，复核进行中。SDK在这些测试中是契约桩，尚不能作为真实中央服务+商城跨进程验收。新页面归CE-03-U，尚未开始。

验证历史：

1. Reactor指定单个测试两次在shared-kernel的硬编码failIfNoTests=true处停止；未放宽项目检查，改完整回归，再通过install后仅commerce-app窄测试。
2. 原commerce_test_20260923数据库执行V49时遇MySQL1419（binlog+测试账号无触发器DDL权限）。未修改共享服务器全局配置；随后使用新建专用MySQL commerce-inventory-mysql-7841e58190，43308回环端口，迁移root/运行inventory_app分离，凭据600文件。
3. 专用库完整回归运行397项，其中既有OrderExpiryLaneTest.hotTenantCannotStarveOtherTenants失败（小租户全部处理前热租户20单，要求<=quantum），其他新增4项通过；不得忽略该失败。独立复核中。
4. 原共享测试库仅V49失败、V50未执行、成功最大48、残留表0行且无触发器。受控修复先保留失败元数据及将空表改名employee_authority_route_failed_ce03_20260929，再Flyway repair/migrate执行原样V49/V50。50个迁移validate通过，没有修订任何成功checksum，没有drop/清库。第一次repair预检因还发现未执行V50保守拒绝，无副作用；第二次限定精确49失败+50待执行才修复。

证据在commerce/.local/central-inventory：verify-owned.log、focused.log、repair/result-fixed.log、failed-v49.txt（后者在repair目录）。原商城8602使用commerce_local，未切换/重启。专用MySQL仍运行供后续验收，任务结束时应停止并保留数据，不自行删除。

补充进度：商城独立复核7项通过，随后完整回归397项（392通过/5可选性能跳过）通过，日志verify-final.log；源码最后只补充Owner锁后许可检查、到期许可断言与HTTP入库。事务人工审查：远程判权在Commands事务外，guard只锁运行时路由与Owner门店；命令、库存、身份审计原子提交，失败回滚；路由切换与已准入事务共享行锁串行；本地事务沿用10秒上限，5秒为许可准入窗口，不承诺分布式原子撤销。Hygiene最终无阻断，保留无格式化器与事务人工审查提示。

第一次真实中央+商城联调rehearsal-198bd271bbc6在新入库Grant后403停止，28项前置通过，业务入库未成功；没有重试写入或放宽拒绝。为区分新授权投影就绪与错误放行，增加最多12次/250ms的只读check-resource就绪探测，记录每次DENY/ALLOW/水位状态；撤权和入库断言仍不重试。第二次联调进行中，不能提前写PASS。

## 最终后端验证

第二次跨进程rehearsal-e068a97301ff PASS，共51项；入库真实HTTP首次提交可用量7，同键重试仍7、身份审计恰好1条；读权限不能入库、跨店/跨租户403；撤销写Grant后旧命令重试403，而独立读和CATALOG保持各自边界；停止授权服务返回503。read就绪探测记录3次DENY后ALLOW，receive记录4次DENY后ALLOW，证实新Grant保守投影窗口；不是重试业务写入获得通过。原P6的过期源拒绝、CATALOG任务重启/撤权/安全停止也通过。

CE-03-I后端验证PASS；CI/Git交付收尾中。CE-03-U页面、商家门店和其他新增模块仍未实施。原63节点DAG、真实OA映射及生产HOLD不变。事务审查与hygiene限制如上，不作生产容量或即时分布式撤销承诺。
