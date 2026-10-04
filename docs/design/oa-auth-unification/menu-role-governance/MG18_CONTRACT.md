# MG18 人工权限复核（FROZEN）

2026-10-03。基于已批准MG18与D-HR人工核对／显式负责人选择。MG15／MG17产品及必要验证DONE。本片在既有治理库、严格撤权、独立诊断和控制台内实现，不配置自动岗位授权、定时调度、通知或新中间件。

## 范围与业务不变量

一次任务固定完整tenant／application／environment、一个目标成员及创建时generation、1–100条用户显式选择的来源。创建仅接受同一已加载人员报告来源页中的ID（含source_cursor）和其basis_hash，complete=false拒绝创建；不是全员、全分区或该人员全部历史权限复核。源范围、固定角色版本、来源类型、受益人／组、原Grant版本／状态、期限及最小完整来源快照不可变。分页或目标切换清空选择，不自动补选新来源。

任务保存创建者、负责人当前成员ID／generation、原因和原依据；负责人必须在相同分区有当前有效管理委派、HUMAN主体、有效企业成员与独立诊断资格。允许创建者负责自身创建的任务；没有新增职责分离要求。任何当前合格诊断管理员可读任务、显式取消或重新指定当前合格负责人（原因、任务版本、审计），实际决定与撤权确认仅负责人本人当前代际可做。失权403、依据／版本变化409，普通成员、仅Owner及跨分区／不存在任务统一拒绝，读取前后检查身份与权限。

## 决定、完成与恢复

条目状态PENDING、INVESTIGATE、KEPT、WAIT_REVOKE、REVOKED、CANCELLED。KEEP只记录保留意见，不创建、恢复或延期任何Grant；历史不可用来源同样不能因KEEP复活。INVESTIGATE保存原因，仍未完成，可在重新读取后决定KEEP或REVOKE。

REVOKE只对原版本仍PENDING／ACTIVE来源调用现有AccessManagement.strictRevoke，与条目检查点、命令及审计同一数据库事务；立即记录WAIT_REVOKE，返回202。GROUP操作撤销整个组来源，必须显式确认影响全组受益人，不能转为针对单人的个人撤权。保留其他来源逐条显示，不宣称全部权限消失。

明确confirm命令读取实际撤权回执，必须COMPLETED且grant_id、version=原版本+1、真实operation_id与本条撤销意图匹配，才能记录REVOKED；未完成／阻断返回409并保留WAIT_REVOKE，不在GET修改任务。确认已发撤权只验证原命令效果，允许在人员事实变更或取消任务后继续确认；不新增授权。任务全部KEPT或REVOKED才COMPLETED，有调查项则NEEDS_INVESTIGATION，其余RUNNING。取消仅将PENDING／INVESTIGATE标CANCELLED，保留KEPT／已发撤权及其审计；取消后仍可确认原撤权，任务保持CANCELLED，无任何恢复Grant效果。

每次新决定绑定刚读取的current_basis_hash、任务与条目版本；目标generation必须仍与创建时一致。来源业务快照／原版本变更拒绝；内部投影从PENDING收敛ACTIVE无需伪造新业务版本。原basis不可变，每次详情另返回当前真实basis，自己的撤权改变依据后须重新读取再处理下一条，不把最初basis永久当后续命令依据。新来源不会被隐式加入任务。来源被其他命令撤销／变更不默认为本任务已处理，应重新核对／创建新任务。

数据库约束、固定分区查询、版本CAS、命令唯一性、append-only审计保护并发与恢复。每个操作一个5秒短事务，不做事务内图网络请求；图仍由原可靠投影执行。写入口锁当前主体→成员，再锁分区→任务，与当前生命周期顺序保持一致；当前资格写后重核验，失败全部回滚。断网／超时客户端冻结原command_id和原body重试；当前资格先核验，已完成同命令在依据校验前返回原任务引用的当前详情，不能重复撤权；相同命令不同body为COMMAND_CONFLICT。

## API与字段

复用/api/governance/v1/access：

- GET /reviews：完整分区、可选membership_id、after任务UUID；稳定ID分页20／下一游标。
- GET /reviews/{id}：完整分区，返回任务（id、成员、原generation／basis、负责人／generation、state／version、原因、创建更新时间）、固定条目（id、原完整来源、original_hash、state／version、决定原因、撤销命令和实际确认operation_id），当前personnel_report（第一页与游标，可继续跳转人员页）、responsible_qualified、can_decide。GET无业务写入，Cache-Control:no-store。
- GET /review-responsibles：当前分区独立诊断配置内合格成员，最多100项，仅成员ID／generation／version，不返回登录绑定。
- POST /reviews：command_id、完整分区、membership_id、responsible_membership_id／responsible_generation、basis_hash、source_cursor、grant_ids[1..100]、reason。原快照由服务端读取，不收客户端伪造来源。
- POST /reviews/{id}/decision：command_id、分区、expected_task_version、item_id／expected_item_version、current_basis_hash、decision（KEEP／REVOKE／INVESTIGATE）、reason、whole_group_ack。REVOKE受理202，其他200。
- POST /reviews/{id}/confirm：command_id、分区、任务与条目版本、item_id、reason；只确认WAIT_REVOKE的实际严格回执。
- POST /reviews/{id}/cancel：command_id、分区、expected_task_version、reason。
- POST /reviews/{id}/responsible：command_id、分区、expected_task_version、responsible_membership_id／responsible_generation、reason。

所有POST都no-store；reason去空后1–500字，不接收Token／完整OA正文；400参数、401匿名、403失权／跨分区、409版本／依据／未完成证明、503依赖异常沿用稳定错误契约。typed DTO与Mapper/XML绑定参数，不返回裸SQL或内部堆栈。新增V36，不编辑V29–V35；所有表字段中文注释。

## 页面与验收

人员页勾选当前100条来源，显示所选范围，选择真实合格负责人后人工创建。新增“权限复核”入口可找回任务；详情展示固定输入、当前来源、进度与每项决定，原因必填，整组撤销明确确认；刷新／403／503隐藏旧明细，409提示重新读取，未知结果保留原命令。取消说明已撤权不恢复，重新指定负责人有审计。复用既有详情弹层／表格／反馈组件与1440／390／320视口。

真实PG验证不可变输入／注释／跨分区／独立资格、失权和版本冲突、幂等、同事务撤权及审计失败回滚、部分决定与关闭Runtime再恢复、取消不恢复；真实授权图确认撤一源其他来源仍允许，最后撤权才拒绝，未完成回执不能完成。隔离IdP／HTTP／浏览器实际创建和决定／确认／取消、错误恢复与三视口截图；真实OA引擎未联调明确记录。原业务Grant写入0，不生产部署。复核与命令／审计沿用受控治理保留，不删除未完成项、不添加未经确认的统一保留期限／自动清理；需要业务确定保留期时另记后续治理事项。
