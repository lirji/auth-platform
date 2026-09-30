# CE-03-D 商家与门店目录实施契约

基于用户已批准的员工端边界、CE02能力绑定、CE03受限执行者协议。新增业务能力仍限定merchant.read/create、store.directory.read/create，不授予store.read、CATALOG或员工治理权限。

## D0 集合执行范围（本片）

前置CE03-U本地验收完成（库存58项隔离/真实浏览器验收），两仓main auth db96617/commerce8cf2a58。有限执行引用协议增加以下精确组合，引用最长60秒：merchant.read/create→merchant，store.directory.read/create→store；既有catalog.operate及inventory.read/receive保持语义和期限。不扩大到任意能力或客户身份，不更改已发布清单/Owner配置。

新增内部POST `/internal/governance/v1/access/execution-scope`，请求 `{execution_id, check}`，只接受已登记execution caller和commerce应用。校验引用调用服务、应用、环境、租户、能力、资源类型、身份版本/代际、到期；按当前有效Grant与签发原路径完整相等取交集，并绑定原目录epoch，不能因撤权后重授复活旧引用。集合没有对象Facts，禁止伪造待创建对象ID。

响应复用ScopeAccessDtos.Plan v1及有界/SDK严格验证；alternatives仅包含仍有效的完整原路径，不混合多Grant条件。validUntil不超过当前决策或引用到期。空路径DENY，故障503，错误版本/响应SDK失败关闭。新接口仅开放上述4个目录能力，CATALOG/库存原调用继续使用execution-check。原SDK无需升级，不能调用新方法；既有issue/check序列化和响应兼容。

SDK增加executionScope方法，仅服务凭据，无用户Token，不保存或跨请求缓存Plan；复用原scope-plan全部字段/路径校验。实际Owner在SQL分页前使用该范围，写事务前仍执行本地路由/许可准入检查。创建引用签发及范围复核都只保留完整TENANT_ALL路径，若没有则拒绝签发/范围DENY；Owner再次检查完整TENANT_ALL后才创建。指定对象/门店路径不可拼接成全租户。

D0验收：真实PG+graph验证merchant指定对象读、store指定门店读、创建需TENANT_ALL的上层契约边界、跨调用者/租户/类型、引用到期、撤权与新Grant不复活旧引用；SDK测试非法/膨胀路径、响应绑定及服务凭据；HTTP调用者门禁；旧CATALOG/库存回归。D0只提供范围协议，不声称已接管商城目录。

## D1/D2拆分范围

D1扩展commerce EmployeeAccess到目录集合许可，V51兼容扩展路由族与审计资源字段（不改已执行V49/V50），merchant/store Owner在原list SQL分页前过滤；create只接受完整TENANT_ALL并沿既有Commands事务锁路由、身份幂等和审计。创建门店仍核对真实父商家属于本地租户且可用，不隐含商家读取授权；跨域仅走MerchantApi。CENTRAL/STOPPED拒绝旧ADMIN与去掉header绕过。requireActive等内部业务协作用例不因目录read被拦截。

D2独立目录SSO页/动作提示，逐能力展示列表/创建，不隐含其他经营权限；真实数据、页内错误/未知结果幂等恢复、跨租户/指定范围、撤权/故障、桌面与窄屏验证。技术接口与具体迁移在D1前补齐，生产映射/Owner仍待定。

## D1 商城Owner接口与兼容迁移（本轮实现）

复用GET/POST `/v1/admin/merchants` 和 `/v1/admin/stores` 的现有请求/响应、游标/数量上限及Idempotency-Key，不改变客户门店browse。过滤链按精确method+path签发D0单能力引用；仅X-Tenant-Id中央入口，由持久DIRECTORY能力族覆盖四个用例，去头不能退回旧ADMIN。库存族保持独立。

EmployeeAccess增加ScopePermit（能力/可信本地租户/类型化ScopeQuery.Filter/路由/稳定身份/范围指纹/准入截止）；CentralEmployeeCheck增加scope端口，使用executionScope并核对身份映射、SDK严格Plan。领域不依赖SDK/Token。旧LEGACY/SHADOW仍ADMIN，CENTRAL仅受限OPERATOR，STOPPED拒绝。列表先scope，再Owner SQL分页前完整AND/OR范围过滤，返回前再次scope并比较路由/身份/范围指纹；范围变化拒绝旧结果。merchant只绑定merchant_id，门店只绑定store_id，不拼用户SQL。

创建要求完整TENANT_ALL路径，事务外判权；Commands.runGuarded先锁定族路由并检查5秒准入许可，再处理回执/插入，沿原10秒事务。幂等摘要加入稳定principal/member/generation；中央身份审计与业务效果同事务。创建门店在事务内由MerchantApi确认父商家属于可信本地租户且ACTIVE；无需借用merchant.read权限获取父商家列表。集合/创建没有资源Facts。

V51用单条ALTER替换V49已核实自动命名的family CHECK（employee_authority_route_chk_1），新增DIRECTORY并保留所有状态/不可删除/不可回退触发器。V50审计增resource_type（默认store）及nullable resource_id，store_id对merchant允许空；CHECK确保merchant有resource_id且store_id为空、store有store_id且resource_id为空或相同。旧库存记录/旧版INSERT继续使用store_id并由默认类型解释，不回填或删除历史。新库存/目录写显式写目标类型/ID，记录的是实际已创建/已变更资源，不作为判权事实。原V49/V50不改动。

验收覆盖真实MySQL HTTP与直调：四个能力不串权，指定资源列表SQL分页前过滤，创建局部范围拒绝、全租户成功、父商家跨租户拒绝；旧ADMIN不能绕过，幂等重放/代际冲突、审计类型、撤权/停用/服务故障、旧库存审计兼容。之后真实中央联调；D2页面仍独立切片。
