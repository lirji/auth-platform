# MG14 组与审批来源迁移契约

2026-10-03。用户对上方两个推荐业务方案回复“确认”，按已告知解释落实：OA 存量授权升级必须重新审批，批准后沿用原截止切换；D-HR 首版人工核对和负责人复核，不自动按岗位授予。复用 MG13 同分区、同编码更高版本、非扩权和真实撤权后授新的规则，不增加设施或部署目标。

## 实施 pass

父切片 MG14 保留，串行分为 MG14-A（GROUP）和 MG14-B（OA_REQUEST）。MG14-A 下列契约 FROZEN，可先实施和独立验证；MG14-B 的业务规则已确定，接口具体化在其实施前冻结。两 pass 全部完成才标 MG14 DONE，后续依赖不提前放行。

## MG14-A：组来源（FROZEN）

沿用 `/api/governance/v1/access/role-migration-preview` 和 `/role-migrations` 的原命令、分区、集合上限及版本字段，增加 GROUP 支持。创建和每个检查点重验当前组有效、来源未隔离、环境与业务时区、当前管理员上限和组自授限制；组不可用返回稳定 GROUP_UNAVAILABLE 排除／失败原因。不是通过诊断资格取得写权限。

每项持久保存 source_type=GROUP、原 group_id，member_id=null、generation=0。原角色、原范围字节与摘要、原排他截止、旧版本和组身份均固定，禁止展开成员为个人 DIRECT。新来源仍为 `role-migration:<old_grant_uuid>:<new_role_uuid>`，预分配 Grant 和命令不变；新 Grant 必须 GROUP、同组、同截止，开始不早于授新时刻与原开始。DIRECT 原计划、摘要和接口保持兼容。

追加 V29／V30，保留已执行 V26–V28。持久化守卫限制来源、组／个人形状、固定计划及连续检查点；旧写节点不能用保留前缀伪造 DIRECT 或换组。旧范围、历史 Grant 不回填；旧任务 source_type 默认 DIRECT 是已有任务的真实类型。

继续先撤旧、真实旧回执确认、授新 GROUP、真实新回执确认。退组者不会被任务补回；新加入成员遵循当前目录组资格。任务完成只说明该来源投影已核验，不说明全组每个资源 ALLOW，也不说明其他来源已撤销。取消／失败不自动恢复原源；已提交新效果保留并可独立撤权。

详情兼容增加 source_type、group_id，member_id 可空；页面展示“组授权”和固定组，不显示“第0代成员”，明确动态成员和其他来源独立。实际资格、失败／未知、关闭恢复及三宽度仍用现有弹层和原命令机制。

必要验证：真实 PG 形状／注释／旧 DIRECT 回归、同命令及来源唯一、组停用／隔离／自授／外部撤权、当前管理失权、固定范围与期限；真实图下组成员变动、多来源保留、撤旧回执及新来源确认。HTTP／页面在独立目标验证，原业务 Grant 写入0。

## MG14-B：OA 已决定的业务边界

申请由原受益人本人发起，固定旧 OA Grant／版本、目标新策略、原范围和原截止；旧批准和策略快照不修改。新策略须同角色编码更高版本，迁移不新增能力、不扩大范围、不延长截止；新增能力仍走独立新申请。

可信新审批批准后只保存批准事实和待切换关联，不直接生成新 Grant。管理员明确绑定新批准与原来源创建／推进迁移；原来源在新批准前不撤销。新来源保留 OA_REQUEST 和新申请谱系，不转换 DIRECT。授新前重验批准、成员代际、策略／审批人资格、原源、范围和真实撤权证明。

新申请取消／拒绝／到期阻止尚未提交的新授权；批准／取消与迁移按同分区锁串行。原来源外部撤销不能由任务补回。本人取消原申请时，尚未产生新 Grant 的关联替代申请停止；已经产生的新来源有独立新批准，撤回需取消新申请，不把撤旧等同所有来源消失。

MG14-B 正式接口、追加迁移及页面字段在实现前具体化，沿用原严格 JSON、Bearer、分区、审计与有界任务。未实施／未验收的 pass 不标 DONE。

## MG14-B 接口与持久化（FROZEN，2026-10-03）

复用原申请、可信审批Inbox、分区锁与可靠投影，不增加中间件或OA数据库直写。

- 新增 `POST /api/governance/v1/requests/role-migration`，严格JSON：tenant_id、application_id、environment、command_id、policy_id、old_grant_id、expected_version、reason；202返回本人申请View。受益人来自当前登录。客户端不能提供能力、成员、审批结果、范围或有效期。原Grant必须ACTIVE、当前有效、OA_REQUEST且绑定本人当前代际的APPROVED未撤回旧申请。目标策略同角色编码更高版本且非扩权，范围JSON与原范围逐字相同。服务端固定from=max(数据库now,原from)，to=原to，策略／成员期限足够。同命令返回原申请／固定时间，不重新计算；同原Grant／目标角色只允许一个尚未撤回或拒绝的替代申请，最多20项待处理限制复用。
- Request View兼容增加migration_old_grant_id、migration_old_version、withdrawn；普通旧申请两关联字段null、withdrawn=false。新批准回执为MIGRATION_APPROVED_PENDING_SWITCH，保存APPROVED和新审批实例而无Grant。Execution显示MIGRATION_WAITING，已撤回未授新显示CANCELLED，到期显示EXPIRED；批准事实和执行事实分开。
- Preview Item兼容增加replacement_request_id。OA当前无可信有效新批准返回OA_APPROVAL_REQUIRED；资格读取同分区固定关联、当前策略／审批人／成员资格，仍非授权票据。proposed_source_id对OA为`<replacement_request_uuid>:single:1`。管理员Create的GrantSelection兼容增加可空replacement_request_id；OA必须明确与预览批准一致，DIRECT／GROUP必须为空。创建与每个检查点再次核对，不能拿普通批准或他人申请代替。
- Task Item增加replacement_request_id；来源OA_REQUEST／同个人代际，group为空。新Grant源仍为新申请标识`:single:1`，固定计划与新申请原子关联，原截止不延长。WAIT_NEW_CONFIRM核验实际新源与原批准，不能以APPROVED或ACTIVE行代替图回执。原迁移前缀只用于DIRECT／GROUP，旧来源和旧审批不改写。
- 追加V31或后续版本，access_request增加关联原Grant／原版本和不可逆withdrawn事实，role_migration_item增加固定批准ID；所有新增字段写中文注释。现有to_jsonb快照守卫自动保护新关联字段，显式允许withdrawn单向前进；唯一约束阻止重复活动替代申请；数据库拒绝旧节点为关联申请直接Grant、换批准、错源／期限／范围和无真实旧分区回执授新。已执行V26–V30不改。
- 本人取消原申请即记录withdrawn（旧APPROVED仍保留）。同分区先取消所有关联且尚未产生Grant的替代申请，再回收原Grant；原Grant已经REVOKED也不能跳过取消关联意图。新申请取消／生命周期到期同样阻止授新；已产生的新批准来源独立，通过取消新申请回收。拒绝或取消的未授新申请可以重新提交新申请，但不能覆盖已创建迁移任务的终态或谱系。

页面入口：本人“申请与通知”的旧APPROVED详情中选择“申请升级”，只选真实可申请策略，展示原授权、固定范围和原截止，无期限编辑；新详情展示旧关联与等待切换。管理员迁移预览展示新的批准ID，创建提交明确ID；任务详情展示OA类型与新批准，不把来源未知当DIRECT。未知结果保留原命令，401／403／503、关闭恢复及三宽度沿用现有组件。

必要验证：真实PG可信审批前不撤旧／新批准不Grant、原／新取消与批准／授新竞态、旧节点和假批准拒绝、同命令／唯一及重新提交、期限／范围不扩、分区／代际／审批人失权、DIRECT／GROUP回归；真实图真实旧撤权确认后新OA源、其他来源独立保留和撤回；隔离HTTP及本人／管理员两页面和当前三宽度。未跑项不标DONE。

MG14-B策略选择补充：本人GET `/requests/{id}/role-migration-policies`沿用分区／after有界扫描游标，服务端过滤同编码更高版本、非扩权、原范围字节及剩余期限可容纳的真实策略；原申请不是本人当前可用OA来源则拒绝。Execution兼容增加grant_version，表单从真实旧Grant版本固定expected_version，不猜测版本1。接口在页面实施前冻结。
