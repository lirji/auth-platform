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

## CE-03-U实施与待验收

后端已推送auth29052d0与commerceb7715ce；对应CI36664884469/36664884967仍运行。CE03-U在独立分支补充静态SSO库存页及动作提示（220入口、能力数不变），沿用真实库存API与Ant Design。入库未知结果保留原键/输入，即使后续拒绝也不把未知历史误当失败；切店/退出提示未确认结果。

初次UI完整回归在新增动作提示测试重新设置Mockito桩时触发NPE（旧Answer被when执行，非业务请求失败）；改doThrow重新测试中。auth浏览器脚本首次hygiene五项phase字面量阻断，改Phase常量后通过。以上不作为产品PASS证据，等待最终完整测试、真实浏览器及截图检查。

UI最终商城完整回归398项393PASS/5skip（ui-verify-fixed.log），新增动作提示测试PASS。首个UI演练在隔离PG准备阶段失败：helper的Unix socket pg_isready误命中Docker初始化临时服务；日志显示初始化随后关闭，而数据库准备此时执行。改为127.0.0.1 TCP并指定postgres，等正式服务后开库；保留失败资源5607893aa954（已停止）。未触及原商城或业务UI。

浏览器首轮rehearsal-12f0268db7ab通过库存只读SSO、真实响应丢失/同键重试（7→10且未重复）、切店未保存提示、跨店拒绝及撤写后只读截图；在401检查失败。原因是新测试addInitScript每次导航覆盖已构造的无效会话，不是服务端放行；补与既有catalog工具一致的“已有会话不覆盖”。已查看桌面/未知结果/390截图；页面把网络异常技术文案换成中文连接提示，需重新前端构建/浏览器验证。

第二轮fda182d95537在库存首次HTTP入库403停止（32前置通过）；没有重试业务写入。读就绪为DENY×3后ALLOW，但PG保留记录证实receive引用已签发，签发后入库链路仍拒绝。旧日志缺拒绝层证据，不能断言具体根因；单次resource ALLOW不足以证明后续执行路径稳定。沿用失败关闭语义，工具就绪探测扩展为resource检查+真实引用签发/执行复核，最多40轮、250ms间隔、连续4轮ALLOW；不重试库存写入或撤权。失败响应以后仅私密保存code/message便于区别拒绝层。原失败与PG诊断证据保留，专用PG682407336aef已停止。

## CE-03-U最终本地验收 PASS

rehearsal-ba03baa64c88共58项PASS，另含库存浏览器9条细分与旧CATALOG9条细分：真实密码/PKCE回跳、只读不显示入库、表单校验、真实提交后丢弃响应并同键重试（7→10）、未保存切店取消保留输入/确认离开后跨店403、独立撤写仍可读10、退出/真实401、停止auth返回503。400—500类失败未伪造成成功；业务入库与撤权断言不自动重试。新Grant readiness实际read4轮ALLOW，receive先4轮DENY后4轮resource+execution ALLOW，完整过程私密留档。此前偶发403的具体拒绝层仍未追溯，不把本次成功宣称为生产新Grant零等待承诺。

最终截图inventory-write-form、unknown-result、390、outage已实际查看，桌面1440×1000/窄屏390×844；原只读与跨店截图前轮亦查看。表单复用主题/控件、错误/未知结果提示清晰；窄屏表格内部滚动且整页不溢出。页面无新的模态编辑器，仅切店未保存确认，实际取消/确认均通过。原商品页SSO/编辑/撤权/故障同期回归。

商城Java398项393PASS/5可选跳过（ui-verify-fixed.log）。其后只修改前端未知结果保留键/中文网络提示，重新npm build和Prettier PASS，再打包ui-package-current.log PASS；真实演练记录本次jar SHA。新增SQL/Java未在完整回归后变化。前端既有大chunk警告保留，未顺手拆包。auth契约9PASS、P6工具22PASS、220真实入口覆盖PASS。两仓hygiene最终PASS_WITH_LIMITATIONS：Java无规范formatter、静态检查未配置。工具最后把四个200比较改HTTPStatus.OK，py_compile/P6单测及hygiene复验通过，值与行为不变。

CE03-U实现与本地验证DONE，Git/CI交付随后记录。原已运行8602不切换，真实Owner/生产目标HOLD保持；下一片CE03-D商家门店目录，先补集合/创建执行引用协议，不能伪造对象事实或扩大原CATALOG。
