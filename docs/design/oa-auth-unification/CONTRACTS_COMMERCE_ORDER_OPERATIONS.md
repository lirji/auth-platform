# CE06—CE08 订单与运营授权契约细化

状态：已批准扩展契约的实施细化，FULL_SCOPE；依赖共享 IAM 注册，由 journey_permission_plan 唯一维护。基线 Auth 4a9057a / Commerce b214914。

## CE06 可信门店与原业务责任

14 个闭集能力：order.read/expire/expiry.retry、payment.read/reconcile、fulfillment.read/ship/deliver、aftersale.read/approve/reject/receive_return、refund.read/reconcile（均 commerce. 前缀）。实际订单 Owner 提供订单→实际门店事实，版本为当前门店版本；列表在 SQL LIMIT 前按实际门店过滤。资金与售后端口使用自身能力，禁止隐含要求 order.read。写入授权在事务外取得，事务内核对原事实、授权路由、短资格及 CAS；命令指纹绑定员工身份，成功身份审计与原命令同事务。员工离职不取消已承诺支付、退款、关单、事件及履约责任。原会员、沙箱与平台接口边界保持。

## CE07 页面与运行台

OpsPage 十能力独立，正内容事实绑定页 ID/内容版本，render 的五类内嵌数据分别要求各自 read，内嵌 action 使用发布事实中的固定 action 与目标能力。event.read/pump/retry 与 runtime.read/recover/replay.preview/create/control 分别校验。短引用 60 秒；replay.create 最大 86460 秒仅为授权窗口且取原 Grant 交集。保留原任务来源、不续期；ReplayGate 高危资金及外部副作用限制保持。

## CE07 经营聚合独立来源

commerce.dashboard.read 使用 commerce_tenant 且仅 TENANT_ALL；短引用60秒，不产生持久Source。原Dashboard JSON保持。真实商品范围SQL、会员统计及效果日聚合分别要求 commerce.product.read、commerce.member.read、commerce.marketing_effect.read，同一 HUMAN/tenant/principal/membership/generation。父资格不包含任何子读取。查询前冻结各原资格，聚合返回前逐个重新核验原证明，后续来源读取期间撤销先前来源必须拒绝整个响应。OpsPage按实际声明的五类数据源做同样出口核验，独立action引用不可由render/execute原引用替代。

## CE08 后台责任

按既有 12 条 WorkLanes 逐条确认：手工有限员工引用与系统独立政策区分，禁止把 root ADMIN 复制或重新授予系统。资金既有承诺按原 Owner 系统职责继续；员工来源持久化、到期停止未提交效果，已提交效果不回滚。保留原租户公平性、并发 CAS、未知结果、同键幂等与对账收缩。

## 验证与依赖

迁移旅程域 V67—70，本域 V71+；不修改已应用迁移。隔离端口 PG20532 / Spice20544 / MySQL50308 / Auth20661—20662 / Commerce20665；Maven 私密仓库。需要真实 MySQL、PG/SpiceDB、HTTP、撤权/跨租户/停服/原会员回归、并发审计与真实编译 UI 1440/390/320。纯夹具不得声明数据库或 Auth 语义证明。本域只做本地逻辑提交，主 Agent 统一集成，不 merge/push。
