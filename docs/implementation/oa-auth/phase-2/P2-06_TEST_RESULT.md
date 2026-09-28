# P2-06 商城内部只读接入

真实链路 PASS：`scripts/iam-store-smoke.py --auth-run <auth私密夹具目录>`，13项HTTP检查，真实Casdoor用户→治理SQL/SpiceDB→新SDK→Boot4商城→真实MySQL门店数据。证据 `.local/oa-auth-p2/smoke-9f0ae19efd75/result.json`。独立库 commerce_iam_p2_dbb040a540dd，无共享业务数据清理。

GET `/v1/operations/stores` 只在 `commerce.iam.store-read.enabled=true` 后使用中央权威，配置文件通过 `commerce.iam.store-read.configuration` 指定0600文件，字段central.url/credential/application/environment。默认关闭保持旧行为。新路径不接受旧管理员Token作为回退，其他旧路由不变。

中央身份与本地OPERATOR须显式登记 central_store_identity_binding；同时验证当前本地有效运营身份，保留Actor/memberId及历史回执。中央Grant TENANT_ALL只映射当前商城tenant；StoreApi在SQL分页前按租户过滤。不能把外部Token转换为ADMIN。

证据覆盖：无映射403、真实授权读取两门店且隔离第三门店、分页、未认证401、旧管理员不能绕过新路由、中央用户没有旧管理员权、旧管理接口仍可读、本地停用403、错代际403、撤权待清理503、清理后403、中央宕机503。业务用例再次显式授权，不依赖Controller或代理。

依赖源码固定于 scripts/auth-sdk-source.ref，构建与商城使用相同Maven本地仓库。保持旧AuthzEngine/AOP二进制兼容，新增中央客户端独立Jackson2。完整回归结果见最终P2交付报告。

P2只认证单投影执行者、直接成员与当前企业全部范围；不宣称P3细粒度范围、生产迁移、共享Casdoor升级或正式登录切换完成。
