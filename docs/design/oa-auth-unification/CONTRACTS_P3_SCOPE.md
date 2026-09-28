# P3 范围契约（2026-09-28）

用户「做P3」授权继续原63节点DAG；本契约细化已批准P3，不改变领域所有权。不进入P4、不部署生产。

## P3-01 固定范围与同一路径

P2 CreateGrant/TENANT_ALL、旧SDK响应保持兼容；新增管理 `POST /api/governance/v1/access/scoped-grants`。请求沿用CreateGrant的身份选择、命令、固定role、来源和UTC时间字段，以 `scope_rule` 替代字符串 `scope`。只接受管理Token和当前管理委派。202仅表示受理。

ScopeRule `{version:1, resource_type, clauses:[{kind, values, include_root}]}` 是不可变快照。kind为TENANT_ALL/SELF/DEPARTMENT/DEPARTMENT_TREE/SPECIFIED_STORES/SPECIFIED_SUPPLIERS/SPECIFIED_RESOURCES。每规则1—4条件，条件AND、不同完整Grant之间OR；每集合1—100个唯一有界ID。TENANT_ALL/SELF必须空values，其他必须非空；include_root仅DEPARTMENT_TREE可设true。TENANT_ALL必须独占规则。不接收字段路径、SQL或脚本。未知kind/version、缺字段、重复条件、重复ID拒绝。

固定资源绑定：商城store → store_record.tenant_id/store_id（SPECIFIED_STORES与SPECIFIED_RESOURCES都绑定store_id）；商品绑定必须在P3-02读取真实表结构后追加。SELF、部门、供应商虽然有封闭协议类型，只有资源Owner提供明确语义和适配器后才能使用；不能默认绑定created_by或merchant_id。未绑定类型返回SCOPE_UNSUPPORTED，绝不退化TENANT_ALL。OA原有org_path适配留在OA，不能复制成MySQL查询。

auth持久化grant_scope，复合FK绑定grant与tenant/app/env；rule_json、hash创建后禁止UPDATE/DELETE，Grant的scope=SCOPED。旧P2读取只匹配TENANT_ALL，因此新范围不会扩大旧接口。管理者的能力上限、最长时间、禁止自授予、源唯一、审计/幂等/可靠意图继续适用；固定RoleVersion中每个能力必须绑定该resource_type。

P3新读路径在同一Grant内检查能力、范围、当前身份/期限和图资格。read/TENANT_ALL与refund/S001不得组合为refund/TENANT_ALL。不同Grant可在同一能力下做完整路径并集；保留grant_id和scope_version用于解释。trusted ResourceFacts的tenant/type/id/version及业务字段只由资源Owner后端读取，浏览器不得直接提供。

## 后续片依赖

P3-02冻结ScopePlan、列表、游标和导出契约；P3-03至06冻结主库A/C新快照、双水位、CAS持久化操作和receipt。当前P3-01不把范围落库当成已完成业务授权。P3-04a依据官方WriteRelationships的原子前置条件，必须在锁定SpiceDB与真实PG上验证；SQL租约不替代远端栅栏。

资料：[官方API定义](https://raw.githubusercontent.com/authzed/api/main/authzed/api/v1/permission_service.proto)。已于2026-09-28核对；实际证据以阶段测试报告为准。

## Allowed Change Set / 验收

P3-01限定protocol范围DTO、governance范围模型/管理/Mapper/增量V9、admin新增管理入口、同Grant单测与PG事务测试。保留已执行V1—V8。预置后续故障验收场景，不提前宣称通过。

必须验证：跨Grant能力与范围不能交叉；租户/资源绑定不能替换；缺事实/未知范围拒绝；集合有界且参数化；固定快照不可变；幂等冲突与审计失败原子回滚；P2回归通过。多执行者、图成功SQL失败、bulk缺项为后续必需真实回归，不以当前单测替代。

## P3-02 冻结接口与试点适配

沿P2显式身份桥接接入commerce后端，默认开关关闭。商品资源绑定已核对真实V18/catalog ProductMapper：resource_type=product，resource_id=product_id，store_id=所属门店，resource_version=version；SPECIFIED_RESOURCES按product_id，SPECIFIED_STORES按store_id，仍不把merchant_id当supplier。只读试点不接管订单退款等写入口；将来接入的高风险写必须同时满足新权限检查与资源版本条件更新。

新增 `/internal/governance/v1/access/scope-plan` 使用P2 Check输入；`check-resource` 接收 `{check,facts}`。独立scope开关、scope.check.callers及scope.owner.<application>=store,product明确资源Owner；服务与最终用户分别验证，应用/环境从服务绑定取得。浏览器不得提供ScopePlan或Facts，商城只从当前租户真实资源行构造Facts（本地tenant通过P2显式身份桥对应auth tenant）。返回资源ID/版本用于关联，不能视作长期凭证。

ScopePlan包含schema_version=1、request_id、capability/resource_type、decision、decision_id、完整AccessContext、policy_partition_id/policy_epoch、directory_partition_id/directory_epoch、manifest_version/tenant_version、valid_until及完整Grant alternatives。无路径明确DENY；最大100路径，每路径沿P3-01规则限制；响应256KiB上限，超过明确503而非截断。有效期限仍最多30秒，且不超过候选Grant/成员截止和组业务午夜。水位Token留在auth内部，不对浏览器/业务前端发送。

SDK每次调用独立验证关联、app/env、成员代际、期限、范围类型和有界性，不缓存ALLOW。业务用例每次获取新ScopePlan；SQL前使用预注册列绑定把每Grant内部AND、Grant之间OR参数化，列表/搜索/count/统计/导出共用谓词。详情先按本地租户读取资源事实，再由资源Owner提交check-resource；返回前再次验证资源版本和权限。旧P2全范围接口在严格分区保持503，不做中央/旧权限OR回退。

商城分页使用服务端持久化随机游标，绑定本人/代际、tenant/app/env、capability/resource、policy/directory/manifest/身份版本及检索条件；新请求先获取新plan，版本或检索条件不一致拒绝旧游标。导出job和行快照持久化，限定申请人、租户、资源类型、授权版本与检索条件；提交、开始、每批和下载都重新请求当前权限。每批最多50行，总量有界，状态与检查点同事务；无Token落库、无公开永久链接。权限上下文改变不能继续旧job；下载还验证当前资源归属/版本，资源迁移或变化不能借历史快照泄露。
