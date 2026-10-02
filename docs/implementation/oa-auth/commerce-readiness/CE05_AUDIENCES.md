# CE05-A 人群权限验证与交付

当前切片 CE05-A1，状态 DONE（本地），Git/CI 待交付。范围以 [人群契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_AUDIENCES.md) 为准，A2 页面及其余 CE05—08 仍未完成。本记录不替代原执行计划。

## 当前验证（2026-10-01）

| 验收 | 方法与结果 | 证据 |
|---|---|---|
| 独立读取/创建、最新版本摘要和稳定游标 | PASS：真实 MySQL/HTTP 专项；创建无读取、其他能力族与旧 ADMIN 拒绝；目录不返回 memberIds | commerce `.local/central-audiences/owner-mysql-fixed.log`，7 项全部通过 |
| 原输入边界及不可变版本 | PASS：空/过去快照、500 成员和 64 字标识；重复、501、未来、超过 24 小时和 dyn- 拒绝 | `CentralAudienceMySqlTest` 原边界测试 |
| 主/成员/命令/身份同事务 | PASS：成员插入失败及身份审计失败均不留下四类行；成功记录实际 audienceId | 同专项真实数据库断言 |
| 原回执前当前权限和期限 | PASS：原键/体、稳定代际、撤权、部分范围、STOPPED、过期许可及 401/503 | 同专项；中央协议桩不作为跨进程证明 |
| 可信内部固定版本引用 | PASS：HIT/MISS/UNKNOWN、新鲜度、去重/100 上限、缺失版本、游标及跨租户；员工停止不删除快照 | 同专项 |
| 完整回归和当前源码 | PASS：477 项，472 通过/5 既有跳过；BUILD SUCCESS；7 个源码/测试/迁移/SDK 来源 SHA256 一致 | `owner-verify-corrected.log`、`full-test-result.json`、`source-sha256.json` |
| 正式路由清单与工具 | PASS：260 入口/122 能力/34 角色，修正精确计数基线后 9 契约工具测试和 28 演练工具测试全部通过 | auth `.local/governance/commerce-contracts/audiences-owner-{contract.json,contract-tests-fixed.log,tools-fixed.log}` |
| 真实授权链路及精确 SQL 行 | PASS：549 检查点；创建无 read、原键、最新版本、跨租户、撤权/STOPPED/实际中央停机拒绝；恰 3 身份审计，A 的两个版本为 1:2、2:0，真实两成员且不创建客户 | `p6/rehearsal-edc3a5f8c7d0/result.json`，子网 10.254.115.0/24 |
| 质量 | PASS_WITH_LIMITATIONS：两仓 hygiene 无阻断；Java formatter/静态分析未配置；事务另由真实故障测试证明 | 两仓人群 hygiene JSON |
| 可见页面 | N/A：A1 为后端；A2 必须另行真实 PKCE、状态恢复及 1440/390 截图验收 | 不以后端通过代替页面验收 |

专项初次两轮失败来自兼容夹具：已接管租户禁止删除路由，以及独立租户缺少绑定所需 OPERATOR 凭据。改为未接管独立租户并补齐明确绑定，未放宽业务、SQL 保护或测试断言。失败日志保留。

SDK 固定 auth 4747ac49；A0 精确 CI36707598359 SUCCESS。运行 JAR 与演练复制品 SHA256 相同，152 个类/迁移及两个模块归档一致。V63 已应用在专用测试 MySQL，V49—V63 不可修改。自有进程 finally 停止，数据库/证据保留；原 8602/OA 未切换。生产目标、映射、Owner、部署授权沿原 HOLD。

## Git 与 CI

A1 auth b5a6c64/commerce 1987062 已正常合并推送 main。Auth CI36952116177 的接入脚本步骤 FAIL：清单已补入既有独立平台 `/v1/platform/me`，总数 260，而测试基线仍为 259。原本地 9 契约测试日志也有该失败，汇总遗漏，不应记为当时全 PASS。已同步精确基线，漏接口、通配能力、混合范围及客户接口越权等拒绝断言保持；失败日志保留。修正后完整 9 契约/28 工具测试、py_compile 及 hygiene 全部通过，新增计数测试/清单/源脚本/入口摘要；只修改精确计数和记录，不改变已通过 477/549 的业务实现与输入。修正版本精确远程 CI 待核验。商城 CI36952128923 尚在运行；A2 已开始实现但未验收，整个目标未完成。
