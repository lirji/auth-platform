# MG13：直接授权角色版本迁移（FROZEN）

2026-10-03。在已批准 MG13 和 [MG12](MG12_CONTRACT.md) 的保守切换顺序内冻结实施契约。用户对推荐顺序回复“继续”，沿用已告知的解释；不承诺连续访问。仅本治理服务拥有任务、来源谱系及授权写入。

## 不变量和执行边界

- 仅显式选定 1–50 个当前有效 DIRECT，旧／新角色同分区、同编码且新版本更高，新增能力为空。管理上限、自授予、成员代际、截止、能力停用、范围兼容及严格分区重验；GROUP／OA 留给 MG14。
- 创建任务只冻结计划，不撤权。请求逐项携带原 Grant 版本、原排他截止及范围哈希（TENANT_ALL 为 null），与当前数据不符 409；任何不合资格或缺失对象整批拒绝，不默默缩小集合。每任务最多50项。
- 原 Grant 的角色、成员代际、来源、固定范围字节／哈希、开始和截止持久保存。新 Grant 复制原范围字节／哈希，截止完全相同，开始不早于实际授新时刻和原开始；期限不续期。原记录只通过正式撤权用例改变状态／版本，不自动恢复。
- 新来源固定 `role-migration:<old_grant_uuid>:<new_role_uuid>`，预分配新 Grant UUID 和两条执行命令 UUID；唯一 `(old_grant_id,new_role_id)`。普通直接／组授权拒绝保留前缀；追加数据库 INSERT 栅栏阻止旧节点绕过，新 Grant 必须匹配任务固定谱系和当前 READY_TO_GRANT 子项。既有前缀来源不覆盖，创建前检测冲突。
- 每推进请求只推进一个显式子项一步，在当前管理员身份下重验并执行一个最长5秒 READ_COMMITTED SQL事务。固定锁序为应用准入行→任务行；任务版本／子项版本 CAS，底层撤权或授新、投影意图、子项检查点、命令回执和审计同事务。图调用由既有 ReliableProjection 在事务外独立完成，迁移入口不持有 Token 执行后台业务授权，不新增调度器或中间件。
- 任务自行提交撤权后才接受原 Grant 为 REVOKED／原版本+1；若此前被其他入口撤销或内容变化，子项 FAILED，不能补新授权恢复别人已撤的权限。
- WAIT_REVOKE_CONFIRM 必须有真实 revocationReceipt COMPLETED、精确原撤权版本及操作回执，才进入 READY_TO_GRANT。实际授新前重新核验该回执。WAIT_NEW_CONFIRM 必须有新 Grant ACTIVE／版本1、真实投影操作／回执、策略和目录双 READY 当前水位、成员与期限仍有效，才 COMPLETED。SQL ACTIVE 或普通投影意图不足以完成。

## 状态与恢复

子项：READY_TO_REVOKE → WAIT_REVOKE_CONFIRM → READY_TO_GRANT → WAIT_NEW_CONFIRM → COMPLETED；每个非终态可因业务资格失效转 FAILED，或任务取消转 CANCELLED。FAILED 不自动重建 Grant。投影 PROCESSING／BLOCKED 保留等待阶段和明确原因；修复依赖后管理员使用既有受控严格投影重试，再推进原子项。503／网络未知重放原命令，不能换 UUID 猜测未提交。

任务状态由最多50项聚合：RUNNING、COMPLETED、FINISHED_WITH_FAILURES、CANCELLED。取消验证当前管理权和任务版本；停止尚未提交的后续效果，不恢复已撤旧授权。已提交的新 Grant／投影意图可能继续生效，详情始终展示其当前状态；若需撤它，调用原受控撤权入口，是独立决定。任务完成表示这些来源迁移已核验，不表示成员其他来源消失。

任务／子项／命令及审计保留，无自动过期删除，生产保留期限留给 MG18 的业务决定。旧 API／读取仍兼容，新增 V26 不改已执行 V25；保留前缀是新写栅栏。旧节点不得创建迁移来源，迁移 UI／API仅新节点暴露。

## HTTP 与页面

全部路径 `/api/governance/v1/access/role-migrations`，人类 VerifiedLogin 和当前分区管理权，响应 `Cache-Control: no-store`。GET严格未知／重复参数；POST复用严格 ≤16KiB JSON，无调用方 actor／principal。匿名401、无管理权或外部分区403、非法集合400、版本／同命令异载荷409、依赖503；不回显SQL或凭据。

- POST根：`command_id,tenant_id,application_id,environment,old_role_id,new_role_id,grants:[{grant_id,expected_version,valid_to,scope_hash}]`，202返回固定任务详情；重放已完成原命令只返回该任务当前事实，仍须当前管理权。
- GET根：分区＋可选 `old_role_id,after`，UUID稳定20条分页，提供任务恢复入口。
- GET `/{task_id}`：分区，任务及全部≤50子项；含固定来源、范围、截止、子项状态／原因／版本、原撤权回执ID、新 Grant及当前状态、统计和最后更新时间。
- POST `/{task_id}/advance`：分区＋`command_id,item_id,expected_version`，202当前任务。重放读取原命令的当前事实；不同管理员仍以相同任务检查点续跑，不能改变固定来源或对象。
- POST `/{task_id}/cancel`：分区＋`command_id,expected_version`（任务版本），200当前任务；与推进串行，终态不伪装撤销已生效效果。

角色页复用当前960宽弹层。预览后明确提示“只创建当前条件满足的 n 项，排除 x 项”，管理员勾选接受拒绝窗口后创建；任务持久化后锁定原集合。入口提供历史任务／恢复，关闭再打开可读取已保存任务。详情展示总数、各阶段／失败、原范围／截止／谱系、新 Grant真实状态。明确“推进本轮”在前台逐项有界执行一轮（最多50请求），只处理本次读取的非终态，每项一阶段；停止继续发请求时已提交效果保留，不解释成任务取消。单请求失败／未知停止本轮并保留原命令重试；当前权限失效显示失败，不报整批成功。取消单独确认已撤旧不恢复、已提交新 Grant可能继续生效。输入、迟到响应、Esc／焦点、窄屏和错误恢复沿用 MG12。

## 验收

真实 PG＋现有测试图只操作本轮新建 UUID 分区：精确范围／期限、先撤旧并核验真实拒绝后授新、真实ALLOW及回执、重复命令／唯一来源、数据库约束／注释、并发外部撤权、管理员失权、成员代际改变、部分失败、等待／断连恢复、中断重开 Runtime、取消各检查点。真实隔离 HTTP和页面验证进度／未知原命令／重新打开恢复；当前1440／390／320截图实看。原业务 Grant／目录写入0，不部署生产。
