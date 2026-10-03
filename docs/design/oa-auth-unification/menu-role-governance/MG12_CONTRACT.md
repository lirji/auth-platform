# MG12：固定角色版本迁移预览 v1

状态：FROZEN，2026-10-03。用户在“先撤旧、确认撤权、再授新，允许短暂保守拒绝”的推荐方案后回复“继续”，本次按推荐顺序推进。该解释已在执行前告知；不承诺访问不中断。实际迁移是MG13，不向原业务授权写入。

## 不变量和来源

同企业、应用、环境和role_code，明确old_role_id／new_role_id且新版本更高；角色快照不可变。每条Grant保留原来源、成员代际、固定范围和valid_to。新授权起点不早于实际切换时刻／原valid_from；仅继承剩余期限，不延长。不能原位修改旧Grant的role_id、source_id、范围或期限。

MG13首先只支持ACTIVE、当前有效的DIRECT来源，且新角色不增加能力。增加能力返回REQUIRES_SEPARATE_AUTHORIZATION，进入既有独立授权／审批用例，不能从迁移顺带批准。GROUP与OA_REQUEST返回UNSUPPORTED_SOURCE，交MG14分别处理；不变成DIRECT、不改历史审批快照。新DIRECT来源拟用role-migration:<旧Grant UUID>:<新角色 UUID>（88字符），MG13另持久化旧／新Grant谱系及唯一约束，本片不插入来源或任务。

切换顺序固定为：重验当前管理权／旧Grant版本／受益人代际 → 撤旧 → 核验真实撤权回执 → 新Grant入库 → 核验真实投影。中断按检查点恢复，撤旧后失败不自动恢复旧来源。预览中的eligible仅表示当前SQL迁移条件满足，不能代表图回执、授权票据或实际访问允许。

## API

沿用HUMAN管理委派，机器发布身份不能访问。两个入口Cache-Control:no-store，身份来自认证，不接受operator／principal／decision。

- GET /api/governance/v1/access/role-migration-grants：tenant_id、application_id、environment、old_role_id及可选after。严格允许字段、单值参数。当前分区内旧角色引用，按Grant UUID稳定排序，每页20，真实next_cursor；包含所有来源／状态供核对，不伪造总数。响应Page<AccessDtos.GrantView>，group_id可空。
- POST /api/governance/v1/access/role-migration-preview：严格JSON ≤16384字节，字段tenant_id、application_id、environment、old_role_id、new_role_id、grant_ids。grant_ids为1至50个互异UUID，只处理明确选中对象，不自动选择全部或跨页对象。跨分区／不存在的角色或任一Grant整次ACCESS_DENIED，不返回部分探测结果；同分区但非旧角色的Grant明确排除。

响应字段：tenant_id、application_id、environment、old_role、new_role（RoleView）；added／removed／retained能力数组；assessed_at（PG UTC）、view_hash（本次读取摘要）；continuity=REVOKE_CONFIRM_THEN_GRANT、projection_status=UNKNOWN；items（精确选中集合、UUID排序）、eligible_count、excluded_count。

每项字段：grant（GrantView）、scope_rule（SCOPED真实固定规则，TENANT_ALL为空）、scope_hash（原范围摘要或空）、remaining_seconds（基于assessed_at，最低0）、proposed_source_id、eligible、reasons（稳定排除码）。范围损坏／摘要不一致整体DEPENDENCY_UNAVAILABLE，不将损坏范围降级为全企业。

排除码：UNSUPPORTED_SOURCE、GRANT_ROLE_MISMATCH、GRANT_NOT_ACTIVE、GRANT_NOT_CURRENT、GRANT_EXPIRED、MEMBERSHIP_UNAVAILABLE、SELF_GRANT_DENIED、MANAGEMENT_CEILING、CAPABILITY_UNAVAILABLE、SCOPE_UNSUPPORTED、DURATION_EXCEEDS_CEILING、REQUIRES_SEPARATE_AUTHORIZATION、STRICT_PARTITION_REQUIRED。旧／新能力均须在当前管理上限内；新能力须属于当前目录且未停用；SCOPED的全部新能力须兼容原resource_type及原规则；严格分区为后续真实撤权前提。

格式／重复／越界输入400 INVALID_ARGUMENT；未知身份401；管理资格／分区失败403；角色编码或版本方向错误400；同次读取中基准变更409 VERSION_CONFLICT；依赖／数据损坏503，统一trace_id，不泄露内部异常。

## 读取和页面

复用主库、当前published-catalog管理基准、真实角色及Grant表。使用只读READ_COMMITTED事务与既有5秒上限；一次有界IN查询读取选中Grant／固定范围／当前成员，不做逐行查询。返回前再读管理／目录基准和全部选中快照，变化返回409；期限按最后PG时间计算。该摘要不是持久化预览票据，MG13执行前须重新固定输入并重验，不能持旧报告授权。

角色页每行“迁移预览”打开既有960px模态，明确选择更高的同编码目标版本；按真实20条游标页选择最多50条，跨页只保留明确UUID。目标、来源角色或分区变化清空报告；选择变化使旧报告失效，迟到请求不能覆盖新上下文。结果显示能力变化、当前选中范围／期限／来源及逐项排除原因；GROUP／OA显示具体来源，不伪称所有人迁移。只读阶段没有执行按钮，正常关闭无确认，关闭后保留原角色列表上下文。

必要验收：真实PG读取无Grant／角色／范围／命令／审计写入；越管理上限、自授予、跨分区、混合资源范围、到期／撤销／旧代际、组／审批、扩权及损坏范围；有界分页／精确集合／并发资格变化；严格HTTP和当前页面真实交互、三宽度截图实看。不以fixture ACTIVE行证明真实图ALLOW；投影状态保持UNKNOWN。
