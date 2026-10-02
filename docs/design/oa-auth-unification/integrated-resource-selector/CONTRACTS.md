# IR-01 实际已发布菜单与资源选择契约

用户已明确授权本片完整实施，隔离工作树分支 feat/integrated-resource-selector，基线4a9057a；并行授权替代草案的串行等待约束，原D2工作树冻结。复用 FRONTEND_ARCHITECTURE 的 GUX 和居中 GovernanceModal，不新增基础设施/schema。

GET /api/governance/v1/access/published-catalog 使用 tenant_id/application_id/environment 及 VerifiedLogin；完整当前已发布目录对当前HUMAN管理委派开放，Owner不能替代委派。响应no-store，最多200能力/100菜单/200资源。精确DTO在PortalDtos.PublishedCatalog：tenant_id/application_id/environment/membership_id/generation/max_duration_seconds/manifest_version/content_hash/view_hash/menus/capabilities/resource_types。menus(code,parent,route,any_of)是浏览上下文；capabilities(code,resource_type,risk_level,disabled,grantable)来自当前snapshot/ceiling/disable；resource_types(code,scope_supported,allowed_scope_kinds)只来自实际cap类型与协议绑定。view_hash包括当前身份/成员/租户版本、清单hash、委派内容与cap-state版本。读取原子PG基准A/C，资格撤销403，可观察版本改变409，依赖异常503；不写命令/审计/角色/Grant。

保留旧management与写JSON。createRole现状不拒绝mixed/disabled；grantScoped检查全角色同资源，但没有独立disabled拒绝；Policy确实拒绝disabled。UI新选项必须全角色匹配、grantable且有绑定；新role单资源、逐项cap明确选择，无菜单级联授予。ScopeFields两消费者只接受真实类型允许kind，unsupported不回退TENANT_ALL。复制旧cap保留并要求明确纠正，旧固定记录不改。新命令前重新读取view_hash确认；unknown命令重放原payload，不做会生成新输入的preflight。真实实例标识暂手输，创建Grant不冒称已校验实例归属。

菜单schema1没有名称字段，本片显示code/route。Owner必须真实发布准确菜单，0menus表示真实缺口，不能硬编码Commerce选项、拿catalog.operate冒充product.read或发布122候选。测试专属应用/菜单持久化于真实PG，不混用当前D2清单。正式业务Owner菜单缺口保留。PKCE/HTTP/PG/SQL及1440/390/320实际UI验收通过前不标DONE。
