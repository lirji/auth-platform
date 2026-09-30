# CE-03-D 商家与门店目录实施契约

基于用户已批准的员工端边界、CE02能力绑定、CE03受限执行者协议。新增业务能力仍限定merchant.read/create、store.directory.read/create，不授予store.read、CATALOG或员工治理权限。

## D0 集合执行范围（本片）

前置CE03-U本地验收完成（库存58项隔离/真实浏览器验收），两仓main auth db96617/commerce8cf2a58。有限执行引用协议增加以下精确组合，引用最长60秒：merchant.read/create→merchant，store.directory.read/create→store；既有catalog.operate及inventory.read/receive保持语义和期限。不扩大到任意能力或客户身份，不更改已发布清单/Owner配置。

新增内部POST `/internal/governance/v1/access/execution-scope`，请求 `{execution_id, check}`，只接受已登记execution caller和commerce应用。校验引用调用服务、应用、环境、租户、能力、资源类型、身份版本/代际、到期；按当前有效Grant与签发原路径完整相等取交集，并绑定原目录epoch，不能因撤权后重授复活旧引用。集合没有对象Facts，禁止伪造待创建对象ID。

响应复用ScopeAccessDtos.Plan v1及有界/SDK严格验证；alternatives仅包含仍有效的完整原路径，不混合多Grant条件。validUntil不超过当前决策或引用到期。空路径DENY，故障503，错误版本/响应SDK失败关闭。新接口仅开放上述4个目录能力，CATALOG/库存原调用继续使用execution-check。原SDK无需升级，不能调用新方法；既有issue/check序列化和响应兼容。

SDK增加executionScope方法，仅服务凭据，无用户Token，不保存或跨请求缓存Plan；复用原scope-plan全部字段/路径校验。实际Owner在SQL分页前使用该范围，写事务前仍执行本地路由/许可准入检查。创建引用签发及范围复核都只保留完整TENANT_ALL路径，若没有则拒绝签发/范围DENY；Owner再次检查完整TENANT_ALL后才创建。指定对象/门店路径不可拼接成全租户。

D0验收：真实PG+graph验证merchant指定对象读、store指定门店读、创建需TENANT_ALL的上层契约边界、跨调用者/租户/类型、引用到期、撤权与新Grant不复活旧引用；SDK测试非法/膨胀路径、响应绑定及服务凭据；HTTP调用者门禁；旧CATALOG/库存回归。D0只提供范围协议，不声称已接管商城目录。

## 后续D1/D2（尚未实施）

D1扩展commerce EmployeeAccess到目录集合许可，V51兼容扩展路由族与审计资源字段（不改已执行V49/V50），merchant/store Owner在原list SQL分页前过滤；create只接受完整TENANT_ALL并沿既有Commands事务锁路由、身份幂等和审计。创建门店仍核对真实父商家属于本地租户且可用，不隐含商家读取授权；跨域仅走MerchantApi。CENTRAL/STOPPED拒绝旧ADMIN与去掉header绕过。requireActive等内部业务协作用例不因目录read被拦截。

D2独立目录SSO页/动作提示，逐能力展示列表/创建，不隐含其他经营权限；真实数据、页内错误/未知结果幂等恢复、跨租户/指定范围、撤权/故障、桌面与窄屏验证。技术接口与具体迁移在D1前补齐，生产映射/Owner仍待定。
