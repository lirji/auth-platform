# CE04-O 积分兑换商品员工权限契约

上游为已批准 commerce.point_offer.read（NORMAL）、define/status.update（HIGH），资源 point_offer、TENANT_ALL。商品的storeId是实际业务归属与目录过滤条件，不能伪装SPECIFIED_STORES权限；客户身份契约与会员兑换沿现状。

O0：中央登记point_offer稳定资源常量，有限三能力/HUMAN/最长60秒引用；read允许集合与真实商品事实，define只集合，status.update可真实商品事实；事实必须真实租户/offerId/version，不接受storeId/部门/供应商附加字段。有限执行组合、独立权限/范围/撤权后新grant不复活旧引用、60秒/代际/到期、真实PG与SpiceDB验证；无新协议JSON字段。

O1：POINT_OFFER接管族，V58扩族而不自动切换；三个员工HTTPGET/POST /v1/admin/point-offers及POST/{id}/status。employee身份集中在精确路径；旧共享GET /v1/point-offers的非MEMBER调用也必须同一service权限门禁，不能绕过；MEMBER只见有效目录与原本人兑换。

create在现有Commands事务中route/期限guard先于旧回执，授权使用集合许可，实际新offerId审计；真实门店及merchant ACTIVE，真实coupon/entitlement指定version绑定验证仍保留，不附赠这些资产管理权限。精确积分/额度/单会员限额和时间窗口沿原Offer。status先读取实际offer归属和version做资源判权，事务中锁route/offer复核授权事实，guard之后旧回执，业务expectedVersion CAS；稳定身份摘要与幂等键保持，审计失败整笔回滚。read按tenant/store/after/limit在SQL分页，Owner复核scope，不把未授权页面空集当成功。

顾客redeem保留MEMBER本人与member-before-offer锁序；积分扣减、总额/个人限额、券/权益发放、兑换回执同事务，失败不扣分；员工撤权/STOPPED不能停掉客户已授权自助。状态停用只阻止新兑换，不删除已发资产。旧模式命令摘要保持；中央身份代际稳定进入摘要，动态offer版本不混入幂等摘要。

O2：沿SSO壳，目录指定已知门店分页、独立定义和状态表单；不强制授予store.read或资产目录read，已知资产id/version；两个独立hint；数据DTO使用真实Kind/points/quota/perMemberLimit/validFrom/To，状态expectedVersion/active/reason。未知结果原键/体/路径重试，409保留可改，401卸载/403独立/503关闭；1440/390真实截图。真实客户兑换兼容、员工撤权、实际审计与回执一致。

不新增基础设施或OA审批、不修改已执行V57。需要兼容认识POINT_OFFER族的版本与STOPPED回退；已兑换效果走既有业务补偿，不以代码回退撤销扣分/资产。

## 实施顺序

|ID|前置|Owner与影响|可观察验收|状态|
|---|---|---|---|---|
|CE04-O0|PTS2 DONE|auth ScopeDtos/ScopeResourceBindings/ExecutionAuthorization；SDK同格式|三能力有限组合、租户范围/真实资源事实、代际/撤权/到期，真实PG+图和SDK共存|DONE（本地）|
|CE04-O1|O0 DONE|commerce PointOfferService/EmployeeAccess/Authority/三员工HTTP，V58|真实MySQL Owner/审计回滚/幂等/客户兑换并发；真实中央403/503和STOPPED|TODO|
|CE04-O2|O1 DONE|固定SSO员工积分兑换商品页、两个独立hint|真实目录/定义/状态、未知重试和错误状态、1440/390截图与实际审计|TODO|

串行复用现有本地基础设施，不新增共享中间件。O1按仓库现有Mapper XML集中SQL，不改变customer DTO/事件/表权威。O0不宣称商城Owner已经接管；正式生产映射、人员和环境仍待定。
