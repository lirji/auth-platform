# CE05-D 定向发券员工权限技术细化

消费已批准的[完整扩展契约](CONTRACTS_COMMERCE_EXPANSION.md)：`coupon_delivery.create/read/control/pump` 四项独立HIGH能力，资源 `coupon_delivery`，首批TENANT_ALL。沿用现有CouponDeliveryApi/Service/Mapper、Commands、CouponService与TenantRotation；不新增业务动作、审批、服务或调度平台。用户已授权连续实施完整CE00—CE08，本文件细化现有用例的授权来源与有限协议。

## 稳定切片与Gate

| ID | 可观察结果 | Needs / Owner | 验收与状态 |
|---|---|---|---|
| CE05-D0 | 四项独立有限执行能力、真实正内容事实与明确引用上限 | S2正式Validation/Git/精确CI已DONE；Auth protocol/governance/SDK | 真实PG/SpiceDB独立Grant、scope/object、错类型/版本/能力/租户、HUMAN、期限、撤权重授；本地DONE（产品Git/精确CI待交付） |
| CE05-D1 | 中央发券Owner、同事务身份审计、独立原发放/撤回来源 | D0验证/Git/精确CI；Commerce runtime/marketing/app/persistence | 实际MySQL/HTTP、命令原键、CAS、收件人/券/频控/检查点回滚、重启/撤权/到期/503与已提交效果保留；TODO |
| CE05-D2 | 固定SSO批次/收件人目录、创建/控制/推进独立反馈 | D1验证/Git/精确CI；Commerce frontend/app | 真实DTO、无额外read依赖、unknown原键/pump显式新调用、401/403/503、1440/390/320关联弹层/键盘及实际SQL；TODO |

三片原目录串行，无子Agent/新工作树。S2产品Auth d35e6d5/CI36989740786及Commerce2946279/CI36989725617均精确SUCCESS；S2纯状态元数据Auth b3000ad/Commerce33e4d23已正常推main，产品源未变。完整剩余CE05—08与Auth实际发布菜单/资源选择继续active，122候选/34角色设计不是已经批量发布。

## Owner事实与既有业务语义

权威路径：Commerce `marketing-automation/.../journey/delivery/{api/CouponDeliveryApi,application/CouponDeliveryService,infrastructure/persistence/CouponDeliveryMapper}.java`、对应Mapper XML、`commerce-app/.../http/marketing/delivery/CouponDeliveryController.java`、不可变V31迁移与`benefit/.../coupon/application/CouponService.java`。

- Create固定batchId/storeId、券定义版本、人群快照引用、deadline和频控；deadline未来且至多七天，minIntervalHours为1—720。门店是真实业务输入，首批权限仍是完整租户范围，不隐含store/definition/audience/member.read。
- 现有公开View.version从0开始，每次进度/失败/控制递增；Control.expectedVersion是此CAS锁，不能将锁+1或券定义版本当批次授权版本。D1为实际不可变content_json持久化独立正content_version，初始为1，后续Owner不能更改此事实。原公开DTO及expectedVersion语义保持。
- list按真实storeId过滤并稳定batchId游标；recipients按真实批次及memberId游标。读取在SQL分页前取得当前范围，返回前再检查；目录不存在才为空态，403不伪装成空目录。
- CANCEL只停止RUNNING/ISOLATED的未提交发放，不撤销已有券。RETRY只恢复ISOLATED并保留原mode；ISSUE还须未过原业务deadline。
- REVOKE只能在COMPLETED/CANCELLED/EXPIRED/ISOLATED发起，切换到REVOKING；已有HELD/USED/过期券按原CouponService保留为KEPT，不宣称全部已撤回。REVOKE原实现没有业务deadline，不能套用ISSUE的七天截止或券有效期作为补偿完成期限。
- 原单轮手动20个收件人、后台200步/500ms租户轮转保持。每位收件人的券效果、频控和检查点沿原事务，已提交效果不因后续撤权/停服回滚；无新增MQ、分布式锁或通用命令框架。

## 四项独立入口与事实

| 方法/路径 | 能力 | 授权事实 |
|---|---|---|
| POST /v1/admin/coupon-deliveries | coupon_delivery.create | 新批次仅集合；原命令/实际内容事实/发放来源/身份审计同事务 |
| GET /v1/admin/coupon-deliveries | coupon_delivery.read | 完整租户集合；storeId是实际业务过滤，不是新细粒度权限 |
| GET /v1/admin/coupon-deliveries/{id}/recipients | coupon_delivery.read | Owner读取真实父批次及正content_version，不把memberId当批次 |
| POST /v1/admin/coupon-deliveries/{id}/control | coupon_delivery.control | 真实批次/正content_version；expectedVersion继续独立CAS |
| POST /v1/admin/coupon-deliveries/pump | coupon_delivery.pump | 集合人工推进资格；每位收件人仍核验任务自身原来源 |

不以动态动作字符串拼能力，不用coupon_definition.create或segment/campaign能力替代本族。旧ADMIN只在未接管且从未CENTRAL的路由沿用既有行为；CENTRAL/STOPPED不得回退。客户与平台接口保持各自身份边界。

## D0有限协议与生命周期

稳定 `COUPON_DELIVERY_RESOURCE_TYPE=coupon_delivery`，复用现有Issue/Reference/Check/ScopeCheck，不改变JSON字段、执行表结构或接口。只有HUMAN可签发本应用精确前缀的四能力；create/pump只集合，read/control对象检查使用Owner实际正内容版本，禁止伪store/department/member归属。

create与control的执行引用最长604860秒（七天加60秒请求余量）；read/pump仍最多60秒。此上限只限定授权引用，不延长Grant、当前判权结果或业务券有效期。每次实际检查取签发原路径与当前Grant交集，保持身份/成员代际、调用应用/环境/服务和路由；到期或撤权后重授不复活原引用。

- ISSUE使用原Create.deadline加60秒申请，且不超过协议上限，完整来源在提交时固定。原deadline仍单独限制发放，不延后实际发券截止。
- 首次明确REVOKE是新的补偿意图，采用这次control的独立有限来源，最长七天加60秒；与原ISSUE来源分开保存。它可在原发放截止之后发起，不重新允许ISSUE，也不将旧发放来源伪造为当前操作者。
- 七天是单次撤回执行授权的有限窗口，**不是补偿业务完成期限**。引用到期停止未提交补偿，保留真实撤回/保留回执和检查点，显式报告未完成的授权失败；不得伪造REVOCATION_DONE、清除待处理记录或自动续权。
- 若当前mode已为REVOKE，之后的RETRY或重复REVOKE仍复核首次REVOKE来源，不保存新的control/pump来源覆盖它。新授予或同人重新登录不能复活旧引用；新的补偿任务/对账意图属于显式业务处理，不能在本片偷加自动续期动作。

D0须在真实PG/SpiceDB独立证明create/control允许持久引用且超上限拒绝，read/pump拒绝120秒；集合与对象权限互不混用、内容版本0拒绝、其他营销能力/错误分区不能借用。测试的有限Grant覆盖实际观察时间；短引用到期与撤权重授分别证明，不假称已等待七天。

## D1来源、事务与兼容边界

持久来源只保存受限Actor/执行引用/分区、主体及成员代际、原路由与准确期限，无Token、密码或已缓存ALLOW。ISSUE与首次REVOKE分别不可变，当前mode决定核验哪项原来源；开始、每位收件人、恢复及提交前重新判权。新的pump/control资格不能替代已有方向的原来源。

首次REVOKE不依赖已经到期或撤销的ISSUE授权继续有效：它是当前已授权的独立补偿意图，只允许原合法状态转换及撤回效果；仍保留原发放来源供审计，不能将旧源恢复为可发放。对CENTRAL下旧UNKNOWN/LEGACY发放来源不得猜测许可；历史任务的发放恢复须CE08显式对账，不通过接管或人工pump自动洗成中央任务。

原命令回执仍核验当前主体、实际资源事实及相应原方向来源，禁止同键换主体或更换来源。远程检查沿既有短准入窗口；业务提交前核对期限/路由并与停用串行。身份审计与创建/控制的命令效果同Owner事务，资源版本采用content_version，非CAS锁或券定义版本；取实际租户关系，不构造伪门店授权。

需要持久化事实与来源时只追加V65之后新迁移，表/字段均有中文注释及实际一致性约束。V31、V49—V65及既有业务数据不可改；不清空、猜测旧来源或自动接管租户。原8602/OA、dev_infra与其他工作树/测试卷不切换。

## D2与完整验收

复用Craft、中央SSO、现有RequestContext和居中Modal；真实批次/收件人目录与创建、控制、pump独立入口。写岗位可直接输入实际目标，不强迫额外读权限；batch内容授权版本不在公开DTO中编造，CAS version及issued/revoked/kept/processed等实际结果分开呈现。

有键创建/控制unknown冻结原key/body/path并先复核当前资格；确定409可核对实际CAS后调整；pump无幂等键，未知后禁止自动重复，必须核对并确认下一次新有界调用。401卸载敏感内容，403显示拒绝，503不回退旧ADMIN；分页/返回上下文、关闭保护、Esc焦点、1440/390/320长编号/正文滚动与底部动作纳入实际编译浏览器验收。

每片需要正式Validation、进度、显式路径Git与精确产品CI。D1实际MySQL原键/CAS/SQL故障回滚/原方向/重启/撤权/到期/503，D2实际Auth发布/独立岗位PKCE最终JAR与终态SQL、当前截图实看均不可用前端fixture代替。S2已完成不代表其余发券/旅程/效果、CE06/07/08或Auth实际菜单资源选择已完成。
