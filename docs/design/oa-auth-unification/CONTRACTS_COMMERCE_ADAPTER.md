# CE-02 中央能力绑定与商城适配契约

2026-09-29。基于用户明确批准的员工端范围和“先拆权限，沿用现有审批流程”。本契约冻结增量实现边界；不代表新模块已经接管。基线 auth 11d3776、commerce f7cb512。使用 backend-architecture-design 核对跨模块边界，implementation-slicing 排定下列增量切片，不重做原63节点DAG。

## 可审查产物与发布约束

[机器可读绑定](COMMERCE_PERMISSION_BINDINGS.json)包含122个能力、218条HTTP入口、34个固定同资源角色快照。每条入口有方法、路径、Controller源码、当前/计划身份模式；动态action显式枚举。`EXISTING_*`沿原P3/P5/P6行为，`EMPLOYEE_CENTRAL_PLANNED`是实施目标；其余入口明确保留客户、平台、旧治理或本地沙箱身份。`/v1/me`和旧runtime-capabilities不因该契约变成中央入口。

以 `python3 deploy/verify-commerce-contract.py --commerce ../commerce-platform` 对照真实源码核对。检查器不发布能力、不产生人员映射。正式清单仍使用既有CatalogManifest；必须合并目标应用已有能力/菜单、提高版本并经过同义校验，不能拿此设计JSON覆盖已发布清单。只发布已完成垂直切片；其余能力不提前出现在申请目录。200能力上限包含目标已有能力，超限拒绝，不截断。岗位模板不是授权：Owner审核人员、范围、有效期后才创建P2固定角色和P4申请；权限管理员由原治理契约管理，不放进商城业务角色。

## 现有适配缺口与兼容策略

1. `ScopeRules`、SDK `scopePlan`、server `scope.owner`只认识store/product；resource-check强制storeId。新增资源必须在三处采用同一有限绑定规则，不能只放开清单字符串校验。
2. `ExecutionAuthorization.issue/check`只支持CATALOG/store。同步库存初片不借CATALOG引用：扩展到明确的inventory.read/receive/store，后台新类型留到其所属切片，禁止通配符签发。
3. V46身份桥仅返回OPERATOR，且要求本地同actor有效OPERATOR凭据；该本地有效期继续作为额外拒绝条件。Owner后续若需脱离旧凭据生命周期，应另做显式身份迁移；此处不隐式放宽。
4. V48接管表只代表CATALOG。新增`employee_authority_route`属于platform-runtime持久认证边界，主键(local tenant, capability family)，唯一(auth tenant, family)，不跨业务域读写表。CATALOG保留原表/协议，不能用其CENTRAL状态放行库存。
5. Actor.requireAdmin及旧capabilities()不全局改义。不让中央员工获得ADMIN。扩展Actor仅可携带无Token的、由中央验证的执行引用/身份摘要；任何用例仍调用能力端口实时核对，不信任构造Actor时的角色或capability数组。

旧服务/SDK只支持旧资源，应拒绝新类型；新服务/SDK保持store/product既有JSON、版本、范围语义。新Owner配置只能逐类型显式登记，空或未知类型启动失败。升级顺序：auth协议/SDK/server兼容扩展 → 业务Owner适配与验证 → 清单角色申请发布 → 单租户/单能力族SHADOW → CENTRAL。原应用无需同时升级；未完成的Owner不得登记或授予新资源。

## 资源事实与范围

|类型|合法范围|Owner与事实来源|
|---|---|---|
|store/product|保留TENANT_ALL、SPECIFIED_STORES、SPECIFIED_RESOURCES|store/catalog API；真实门店、产品ID/version，store事实resourceId必须等于storeId|
|merchant|TENANT_ALL、SPECIFIED_RESOURCES；创建仅TENANT_ALL|merchant API从当前本地租户读取真实商家；storeId必须为空，不用商家权限推导未来门店权限|
|commerce_member|仅TENANT_ALL|member API及所属成长/积分/标签/行为/周期用例；员工身份不是客户身份；目标会员真实存在且属于租户|
|commerce_member_policy|仅TENANT_ALL|member政策/周期权益Owner；使用真实政策标识/版本，发放用目标会员事实而非政策权限替代|
|campaign/marketing_rule/segment/audience|仅TENANT_ALL|marketing/marketing-automation所属Owner；引用对象均在可信租户校验|
|coupon_definition/coupon_delivery/entitlement_definition/entitlement/point_offer|仅TENANT_ALL|benefit及发券Owner；定义、实例、发放分离|
|journey/journey_instance/journey_scan/ops_page|仅TENANT_ALL|marketing-automation Owner；版本与实例分离，页面动作查发布版本声明再检查目标能力|
|marketing_report/commerce_runtime/commerce_tenant|仅TENANT_ALL|报表/运行时/总览Owner，聚合前逐源判权；运行时能力不授权底层资金/外部副作用|

新增TENANT_ALL类型的Facts只允许tenant/resourceType/resourceId/resourceVersion；storeId、ownerPrincipalId、departmentId、supplierId为空、ancestors为空，防伪门店/部门含义。已验证租户来自中央context与显式本地映射。单对象事实来自Owner；租户集合和创建操作用scope-plan，不能捏造一个对象ID来做resource-check。创建商家/门店只能完整TENANT_ALL路径，不能混合另一个Grant的范围。

读列表的范围在SQL分页/count/聚合前执行。库存初片必填一个storeId，验证该门店后SQL限定tenant+store；不存在“先取全租户再过滤”。订单/售后/退款详情由Owner沿订单关系取store，不能用请求体补门店；跨店列表需要Owner SQL映射，没做完时不接管该族。

## 受限执行者、命令和事务

中央HTTP链只接管登记的方法+路径与X-Tenant-Id，要求单个Bearer及单个租户头。Actor保持OPERATOR，执行引用由中央issue端点按单一capability/resource_type签发；同步引用最长60秒，资源判权有效窗口最多5秒（取中央validUntil与引用到期最小值），不把引用TTL当作缓存授权TTL。

用例通过EmployeeAccess端口核对租户+能力族路由，再用真实Owner事实checkExecution；中央返回principal/member/generation必须仍映射为原本地actor。端口返回短期Permit（能力、租户、身份摘要、路由版本、事实版本、有效期限），不返回Token。domain不依赖SDK、HTTP或治理库。旧ADMIN仅在LEGACY/SHADOW由原规则放行；CENTRAL/STOPPED不允许旧入口，不以header缺失或中央故障回退。非HTTP调用同样经过端口。

首次实现仅改库存receive/list的员工用例门禁；reserve/confirm/release/returnItems仍属于订单和售后既有系统事务。退款已承诺义务不会因员工撤权丢失。未迁移其他用例的requireAdmin不删除、不放宽。

网络判权先于本地事务。库存命令内再次锁定/核对接管路由版本、门店归属/version及Permit有效期，然后验证SKU在同一门店发布状态并执行增量写入；锁顺序路由→门店→库存。查询结束前再次检查路由/授权，拒绝后不返回数据。中央命令摘要加入稳定principal/member/generation（不含每次变化的executionId），本地actor命令键仍复用。不同代际重用旧Idempotency-Key返回冲突，不泄露旧回执。幂等命令、库存效果和审计使用原Commands事务。新增employee_command_identity按命令主键关联platform_command，同事务保存principal/member/generation、执行引用、能力、真实门店及路由版本；不保存Token，随既有命令生命周期保留，不新增自动清理。

中央许可最多在5秒内准入本地事务，取得Owner锁后再次核对；已准入事务沿用Commands的10秒事务上限，不宣称跨库瞬时撤销。已落库库存/积分/资金效果由业务补偿处理。

## 分族权威路由

族代码由已实现能力枚举固定；首片只有INVENTORY（read/receive一起迁移，不按HTTP头选择写权威）。路由state为LEGACY、SHADOW、CENTRAL、STOPPED，附auth_tenant_id、ever_central、version及UTC更新时间。无记录视为未迁移的旧规则，携中央引用却无路由则拒绝。

允许LEGACY→SHADOW→CENTRAL→STOPPED及STOPPED→CENTRAL；CENTRAL后ever_central不可清除，不能更新租户/族、不能删除记录、不能回到LEGACY/SHADOW。DDL CHECK和触发器承担最终约束，CAS版本递增。停用中央配置也不恢复旧入口。旧platform_credential可继续服务未迁移能力族，故不全局冻结凭据表；迁移族每个业务入口拒绝旧ADMIN，旧凭据重签也无法越过该门禁。此处没有新增业务发权API。

切换工具只处理获批准本地演练单元，先冻结映射输入、完成差异审查和影子验证，再CAS切换。未接管应用实例不得与已接管实例共同承接该迁移单元流量；旧二进制不认识新表，SQL触发器不能代替应用部署隔离。代码回退保留路由STOPPED，由知晓路由的版本拒绝新请求；不能回滚为忽略路由的旧版本。

## 有界切片与验收

|ID|依赖|可观察交付/允许改动|验证|
|---|---|---|---|
|CE-02-D|CE-01|本契约、逐入口JSON、岗位快照、离线校验器；不发布能力|真实218入口完备；动态动作、同资源角色、超限与未知范围失败|
|CE-02-A|CE-02-D|auth protocol/governance/sdk/server有限资源绑定；无运行配置修改|旧store/product不变；19新类型范围限制、跨租户/伪门店事实拒绝；旧SDK拒绝新响应|
|CE-03-I|CE-02-D|commerce库存read/receive、受限身份端口、V49分族路由/V50身份审计；auth执行引用精确扩展|真实隔离MySQL：门店隔离、旧ADMIN/直调拒绝、并发切换、回执隔离；中央403/503、到期/撤权|
|CE-03-U|CE-03-I|真实库存员工页面，复用SSO、RequestContext和已有库存组件|真实登录/读/入库、只读能力不显示写动作、401/403/503、窄屏、旧客户/交易回归|
|CE-03-D|CE-02-A, CE-03-U|商家/门店目录及创建逐用例接管|无隐含store.read或catalog.operate；创建仅全租户；列表SQL过滤|
|CE-04—08|CE-03-D|按原增量序列逐业务拆片，切片时绑定本表实际接口|每片独立数据/页面/任务/撤权与故障验证，未验收不发布清单|

没有生产目标、真实岗位人员Owner、永久源授权截止等输入不妨碍本地代码/隔离验证；仅阻塞真实导入、切换和生产接受。当前文档不授权生产部署或删除数据。

## CE-03-U 库存动作提示与页面绑定（2026-09-29）

在已批准读写拆分内补齐 UI 所需技术契约，不增加能力或审批流程：

- 静态入口 GET `/operations/inventory`，SSO safeReturn 仅保留原组织、环境及 store_id；沿用现有 Ant Design 员工壳、库存列与表单原语。旧控制台不取得中央 Token。
- GET `/v1/operations/inventory/actions?storeId=...` 要求中央库存 read、CENTRAL 路由及真实门店；返回 `{"receive":boolean}`。用同一用户 Token 对 receive 独立 check-resource；明确无权限返回 false，401/503 不能伪装 false。返回前复核 read、路由、Owner事实及主体/成员/代际。提示不签发写权限、不替代实际 POST 再次判权；只读引用不能用于入库。
- 库存查询/入库沿用既有 GET `/v1/admin/inventory` 和 POST `/v1/admin/inventory/receipts`。列表必填门店、以 skuId 游标每页50条；进入页面需要 read，只有 receive 的主体仍可调用原 API，但此只读主页面不开放。SKU由员工输入真实标识，不以库存权限读取商品目录。
- 只读隐藏入库；403清除旧库存/写提示，503显式不可用；401撤下业务内容。入库数量1—1000000整数，同一未知结果固定输入及幂等键原样重试，页面离开前提醒未确认结果；成功后刷新库存。门店改变清空旧结果，窄屏表格横向滚动。
- 新增两个入口登记到机器契约/HTTP清单，共220条；能力122/角色34不变。仍为设计清单，未向真实人员发布授权。
