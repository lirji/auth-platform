# WMS 权限接入切片

本任务总目标与顺序见 [BRIEF](BRIEF.md)，规范状态见 [PROGRESS_STATE](PROGRESS_STATE.md)。用户已批准开始实施，不需要每片再次询问“继续”。必需组织输入未答复时保留阻塞。

| ID | 可观察结果 | needs | Owner / 路径 | 必需验收 | 状态 |
|---|---|---|---|---|---|
| W00 | 固定源基线、保护旧工作、电商运行权限事实 | — | Auth 文档/只读 Docker | 8602 开关与现有治理分区边界；工作树归属 | DONE |
| W01 | Auth 接受精确仓范围，拒绝伪门店/全仓与交叉放大 | W00 | Auth protocol/governance/sdk 测试 | 有效 WH-A；WH-B/跨租户拒绝；能力范围同 Grant；旧电商回归 | DONE |
| W02 | 从真实公开操作与菜单生成 WMS 应用目录 | W01 | WMS scripts/docs/iam | 94 操作/43 源 scope 完整覆盖；未知/重复/变更拒绝；可重复生成 | DONE |
| W03 | 冻结组织/企业与身份/运行映射，准备真实隔离成员 | W02 + Q-ORG | Auth/WMS 接入配置与契约 | 已确认组织；固定身份/服务配置；不扩展旧成员权限 | DONE |
| W04 | 主数据与库存只读中央授权和本人菜单闭环 | W03 | WMS security/inventory/console | 真数据库/身份；精准仓范围；旧令牌撤权；403/503；桌面/移动菜单和深链 | DONE |
| W05 | 入出库与 PDA 作业逐动作接入 | W04 | WMS inbound/outbound/security/console | 原幂等/状态与新动作权限；非法仓/撤权拒绝；真实隔离作业 | DONE |
| W06 | 盘点调整与调拨接入 | W05 | WMS inventory/fulfillment/console | 审批/应用独立；源/目标仓语义；列表/详情/单据直接请求范围 | DONE |
| W07 | 真实兼容、故障与本机运行验收，按验证制品交付 | W06 | 两仓 CI/runtime/docs | 当前制品测试、必要 Git/CI；实际本机目标与回退证据 | READY |

全部串行：共享协议、身份配置、安全过滤器与迁移 Owner 不并行修改。用户已确认 local-wms → ENT-DEMO，W03–W07 契约已冻结，当前架构/影响 Gate PASS；实际实现与验证按片继续。W01/W02 只改协议/工具，无可见 UI，视觉验收 N/A；W04 以后需当前版本真实浏览器与截图。
