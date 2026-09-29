# P6 本地商城迁移契约

本片依据已批准 v0.2 P6 计划及用户确认的 OA 目录、commerce-platform 首批、本地既有运营租户隔离演练。首批固定记录见 phase-6/P6_SELECTED_UNIT.json。生产环境不在范围。

## P6-01/02 输入与只读 dry-run

- 来源为商城自己导出的只读一致快照，不由 auth 业务服务跨库读取。运维提取器只读取被指定本地商城的授权、必要身份状态和资源键，不读取 token/token_hash/姓名/联系方式。
- 格式 `p6-commerce-source-observation/v1` 包含 source、tables、consistency；外部配置必须固定预期文件 SHA-256。快照不等于管理冻结或增量追平证据。
- 表白名单为 store_operator_grant、platform_credential（仅状态、角色、期限、旧actor）、store_record、merchant_record。每表最多1000行、每文件最多4MiB；超限拒绝，不静默截断。源表须为InnoDB；一条REPEATABLE READ READ ONLY事务生成快照。
- `dry-run --snapshot --sha256 --mapping --output` 只读取本地文件，不创建Grant、不写数据库或图。相同输入与显式evaluation_at输出相同，不用墙钟改变重放结果。报告始终包含snapshot_hash/mapping_hash/source_tenant/source_record/source_version/原状态与来源指纹。
- mapping固定source_tenant、target(tenant_id/application_id=commerce/environment)、evaluation_at，显式actor绑定(principal_id/membership_id/generation/identity_evidence)与permission映射(capabilities/approved_by/decision_ref)。缺映射一律QUARANTINED_MAPPING，不按姓名、邮箱或旧角色名称猜测。
- STORE映射SPECIFIED_STORES；MERCHANT的动态后代语义当前不能等价转换，标QUARANTINED_MAPPING，不偷偷展开静态门店集合。未知permission/capability/resource同样隔离。TENANT_ALL不作为兜底。
- 旧grant.active=false保留撤销；过期/停用本地运营凭据保留访问拒绝，绝不因迁移续期。凭据到期与Grant到期分开记录，不伪造不存在的grant.valid_to。目标有权限不代表本地身份绑定可绕过这些拒绝。
- 即使无未知映射，全部记录均为拒绝/撤销时也不能认为已验证正向接管。退出0只表示存在可导入候选且没有隔离项；退出2为需要处置，退出1为损坏/不合规输入或I/O失败。报告不是执行凭据。
- 输出使用0600新文件，拒绝覆盖已有文件；完整快照/映射/报告留.local私有目录，仓库只保存摘要和脱敏结果。

## 后续片边界

P6-03必须再校验当前来源版本、映射证据和撤销墓碑；dry-run的IMPORT_CANDIDATE不授权跨过真实身份校验。用户已选择2：先补齐完整中央经营能力，再迁移；原旧永久授权不伪造期限，映射保留原始期限语义及待处置状态。

P6-04影子比较不OR放行；P6-05需持久化单元版本、服务端路由并覆盖已有商品/价格/任务/脚本旧入口；P6-06使用独立库和安全停止/保留中央的回退，不能回退到忽略新撤权的旧判权。后续接口和迁移脚本在各片前细化，不由本dry-run宣称已实现。

## P6-02 完整 CATALOG 前置能力（用户选项2）

- 明确增加 `commerce.catalog.operate`，资源类型 `store`，语义等价于旧 STORE/CATALOG：商品/变体/SKU、渠道价格、上下架、类目/规格模板、图文/条码、商品任务创建/控制/执行。商品导出仍是独立能力。原 P5 product.update 元资料权限不隐式升级。
- 复用商城已有业务用例和 `requireCatalog` 守卫；当前租户的权威路由由服务端持久状态决定，中央模式下 ADMIN、脚本和后台任务都不能走旧授权兜底。旧令牌失效、停用、中央身份/成员代际、商家/门店失效都继续拒绝。
- auth 增加显式默认关闭的 `/internal/governance/v1/access/executions`（服务凭据+用户Token签发）和 `/execution-check`（服务凭据+执行引用+资源Owner事实复核）。服务必须列入独立 execution.callers；不能由浏览器指定主体或app/env。
- 执行引用持久绑定签发时可信身份版本、调用服务、能力及完整同Grant范围路径。检查时重新执行主库A→双图→主库C，仅签发时与当前均存在的相同Grant和范围路径可允许；旧授权撤销再授予、成员重建不复活旧引用。引用还绑定目录分区与epoch；任何目录版本变化保守地停止旧引用，需当前用户重新提交任务，不自动升级旧任务身份。引用不保存Token、不绕过本地身份状态、不作为长期ALLOW缓存。
- 期限采用RFC3339 UTC微秒精度，SDK签发前规范化，服务端拒绝不可持久化的更高精度，避免幂等重放精度漂移。引用有效期为同步请求最长60秒；创建持久任务时使用请求中明确的任务deadline，最长37天加60秒（沿用已有30天预约+7天执行窗口）。到期即拒绝；控制/重试仍受当前用户和任务原签发身份两重约束。网络故障停止推进，不能降级旧判权。
- 签发按服务/请求ID幂等并核对完整语义，拒绝换主体/范围重放；每成员最多10000条未到期引用，主库短事务串行容量检查。过期引用只保留审计意义，后续清理由运维按到期保留策略执行，不自动清理用户数据。
- 授权复核与商城事务提交不是跨库原子事务；每个有限步骤在业务写前重新复核，远程时限8秒、数据库命令既有10秒。已提交业务效果通过原业务补偿，撤权不宣称撤销已提交效果。

## P6-03 导入与增量

- 受控离线CLI读取0600配置和有界批次；必须固定commerce/tenant/environment和已登记管理员登录，复用当前委派、范围及Grant管理用例，不直接写Grant表。
- 每批最多100来源，每来源一个短事务，检查点和授权/撤销及既有审计、投影意图原子提交。以unit/source/sequence及完整输入摘要重放；不同输入重用序号、旧序号和来源版本倒退均拒绝。增量序号由已冻结来源导出器分配，不能靠updated_at推断遗漏删除。
- 来源删除必须提交显式deny墓碑；已有墓碑永久拒绝自动放行，恢复需新来源与重新审批。实际首批旧运营凭据到期，导入保留拒绝，不创建ALLOW Grant。正向有限时授权使用独立合成演练来源，与真实旧记录分开计数。
- 活跃永久来源没有中央期限决定时仍隔离，不能擅自赋予一年或无限期限；这不阻塞当前全为到期拒绝的真实单元，但必须进入生产候选限制。
- 来源、映射摘要与原版本进入持久ledger；完整来源仍留私有快照，序号/快照并不替代旧写冻结、完整增量对账和影子比较证据。
