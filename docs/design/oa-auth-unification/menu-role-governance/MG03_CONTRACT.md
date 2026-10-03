# MG03 潜在授权影响契约

状态：依据已批准MG00阶段A规则细化，可实施。不是资源ALLOW证明，不改变角色／Grant或目录。角色历史引用数不作为有效授权数。

## 输入与身份

`POST /api/governance/v1/access/catalog-impact`：`{tenant_id, application_id, environment, manifest, cursor?}`；Manifest沿用v1。请求最大141312字节，清单紧凑编码最大131072字节；严格拒绝重复／未知字段、标量宽松转换、超限与结构损坏。应用必须一致，候选语义冲突仍可诊断，不能因此发布。

cursor为`{basis_hash, after_role, after_grant, after_member, after_policy}`；位置为空字符串或合法UUID，member非空要求grant非空；basis_hash为64位小写SHA256。不是权限凭据。输入不接受发布人、主体、统计时间或任意SQL／网络地址。

当前HUMAN成员／管理委派与相同分区、当前成员代际的PortalDiagnosticAuthority同时成立；Owner发布权、普通管理权、业务能力均不替代诊断资格。拒绝追加独立DENIED诊断审计；成功追加ALLOWED诊断审计。403不能转成零影响。查询结束重新校验当前资格。

## 报告

返回分区、current_version、候选content_hash／presentation_hash、observed_at、basis_hash、completeness、diff、stats、fences、roles、sources、policies和next_cursor。

- stats含role_count、active_grant_count、pending_grant_count、active_people_count、pending_people_count、people_count、group_grant_count、request_policy_count。全部由PG全量聚合，与当前返回页大小无关；人员按有效当前成员关联的稳定主体去重，直接／组与多角色重叠不重复。
- roles：id、role_code、version、capabilities及该角色的有效候选ACTIVE／PENDING来源数。未授予角色仍作为目录依赖展示。
- sources：grant_id、role_id、member_id／generation（组无当前人员时为null）、group_id、source_type／source_id、valid_from／valid_to、grant_state、scope／scope_rule。每个当前组成员单独列一条来源关联，保留不同来源，不把多条来源合并为一个Grant。
- policies：id、role_id、policy_version、enabled；包括停用策略以完整报告配置依赖。它不代表审批通过或未来授予成功。
- fences：policy_state、directory_state、desired／applied epoch、source_quarantined和disabled_capabilities。SQL候选记录与投影／图就绪分开显示，ACTIVE记录也不能宣称实际资源可访问。
- roles／sources／policies各页最多100，服务端取101识别续页。联合cursor携带三组最后位置和固定basis_hash，已遍历列表不重回第一页；任何依据变化409，重新统计后从首页开始。

## 统计资格与时间

使用MG02变更节点及两版子树的关联能力并集；新增入口复用旧能力会命中已有角色。新能力没有角色引用时显示尚未授予。固定角色可关联多种资源，不能由报告自动拼接新的混合范围Grant。

排除REVOKED、已过期／尚未到Grant有效期、停用企业／应用、停用主体／成员、成员期限无效、旧代际直接来源。PENDING与ACTIVE记录分开统计；PENDING不承诺业务生效。

组仅接受当前应用环境中的活动目录组、非隔离来源及明确business_zone；人员展开复用ScopeMapper的当前代际、gm.current与任职periods日期资格，按已登记业务时区判断有效日期。缺时区、错误环境、停用组、已过期或未来任职不猜测资格。目录隔离和能力紧急停用显式报告；统计是目录变更的潜在关系，不能替代请求级栅栏、能力和范围检查。

所有统计与当前页来自同一SQL MVCC快照，observed_at用该语句的PG时间。SQL内同时核验当前管理资格、当前目录及显示基准；不在事务外拼接不同时间的个人列表。输出前用新的SQL重验同一资格、目录和事实摘要，变化409／403，不返回旧的“完整”结论。

## 有界依据与失败

basis_hash覆盖候选双摘要、分区、调用方管理／成员基准、当前目录双摘要、角色／候选来源／人员资格和范围、组／时区事实、策略依赖及栅栏／紧急状态。统计时间本身不使每次重读摘要必然变化；资格跨越有效期会改变参与统计的集合／摘要。

依据明细最多10000条规范化事实，不无界加载整个企业到应用内存。这是初始运算边界，不是容量达标承诺；超过时completeness=BASIS_LIMIT_EXCEEDED、basis_hash=null，仍可明确展示同语句全量计数及有界当前页，但不生成可用于发布确认的完整依据、不发续页凭据。正常为COMPLETE。

复用治理连接池和4秒statement_timeout；超时／损坏数据／依赖失败503，不返回零值或旧缓存。必要索引／查询计划用隔离PG验证，未达预算先记录实测并优化当前查询，不引入MQ、缓存或新数据库。

## 验证顺序

先验证多角色同人、两直接来源、组与直接来源重叠、组空成员、PENDING／ACTIVE分离；再逐项验证到期、撤销、停用、旧代际、环境和任职日期／时区。覆盖100条以上分页、角色／策略完整计数、依据变更及无诊断资格／跨分区／Owner仅发布的拒绝审计。最后绑定真实HTTP与差异弹层，检查三种宽度；不修改原Commerce目录或业务授权。
