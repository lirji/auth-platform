# CE05-A 人群权限验证与交付

当前切片 CE05-A2，完整交付DONE；A1 的 Git/CI 已交付。范围以 [人群契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_AUDIENCES.md) 为准，A2 的本地必需验证已通过；其余 CE05—08 仍未完成。本记录不替代原执行计划。

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

A1 auth b5a6c64/commerce 1987062 已正常合并推送 main。Auth CI36952116177 的接入脚本步骤 FAIL：清单已补入既有独立平台 `/v1/platform/me`，总数 260，而测试基线仍为 259。原本地 9 契约测试日志也有该失败，汇总遗漏，不应记为当时全 PASS。已同步精确基线，漏接口、通配能力、混合范围及客户接口越权等拒绝断言保持；失败日志保留。修正后完整 9 契约/28 工具测试、py_compile 及 hygiene 全部通过，新增计数测试/清单/源脚本/入口摘要；只修改精确计数和记录，不改变已通过 477/549 的业务实现与输入。该段首次交付时尚待远程结果；最终 auth2445da5 CI36952452483 / commerce1987062 CI36952128923 已核验 SUCCESS。A2 尚在验证，整个目标未完成。


### A1 最终交付与 A2 当前验证

A1最终修正auth2445da5精确CI36952452483 SUCCESS，商城1987062精确CI36952128923 SUCCESS；均已包含于各自远程main。首次失败保留，不把原b5a6c64的FAIL改写为成功。A1完整交付DONE。

A2在commerce feat/central-audience-entry/auth feat/commerce-audience-browser实施：固定人群入口、导航/lazy页面、独立创建hint及精确客户端、实际摘要/两Tab、有界成员/时间/重复纠错、409/未知原键体/退出保护/401卸载/403/503及重新核验。8项MySQL专项全PASS，完整478项473PASS/5既有skip，最终build/forceCreation package、262入口/9契约/28工具、两仓hygiene无阻断；155类/迁移及45前端资源、演练复制JAR摘要一致。完整回归后仅前端补防重复点击重新核验，已重新构建并强制打包，后端摘要不变。

真实--audiences --browser首轮569aae3f8c33（子网116）已失败并停止，详见下方失败原因及修正。该时点最终a5fd3867e690（子网117）尚在运行，不能提前标DONE；最终结果及当前状态以下方验收为准。脚本阶段字符串改为有限Phase集合，语法与hygiene已复核；改动在实际人群浏览器调用前完成，不更改业务输入、预算或断言。原生产HOLD及其他CE05—08未完成。


A2首轮569aae3f8c33在594PASS后停止：人群write-only/read结果JSON已PASS，401验证失败来自浏览器初始化每次导航覆盖刻意设置的无效凭据。改为保留已有session，401原断言不变；截图归零滚动并对弹层采用真实视口，补390确认弹层。仅脚本修正，商城8源码/478回归和最终JAR不变；9契约/28工具/语法/hygiene复核通过。已查看首轮1440/390表单/目录/未知/退出确认，字段、操作和表格内部横滚符合现有Craft；fixed头部/遮罩的整页捕获伪影须以修正后的截图再核对。该时点最终a5fd3867e690/子网117（session18603）尚在运行；现在已结束，以下方最终验收为准。


## CE05-A2 最终独立验收

CE05-A2本地DONE：固定人群目录/创建两Tab与独立创建提示；8项真实MySQL和完整478项（473PASS/5既有skip）、最终前端build/forceCreation package通过。最终真实a5fd3867e690（10.254.117.0/24）647检查点PASS，人群11条浏览器检查及全部既有员工页回归通过；恰5条实际身份审计，UI两个快照为1:2和1:0，原键不重复、导入不创建客户。1440/390表单/目录、409/未知/退出确认/成功/401/503共11张截图已实际查看，正文390且表格内部横滚。两仓源码摘要、17嵌套模块/661类/45资源及复制JAR一致；262入口/122能力/34角色、9契约/28工具及两仓hygiene无阻断。首轮594后401夹具覆盖凭据失败已修正并保留。自有进程已停止；无新迁移，V49—V63不可改、SDK固定4747ac49，原8602/OA不切换。Git/CI待交付；下一CE05-CAM活动/审批/预算细化，其余CE05—08及auth资源展示未完成，生产2HOLD不变。

| 验收 | 方法 / 结果 | 当前证据 |
|---|---|---|
| 独立创建与目录、静态壳无授权 | PASS：8项真实 MySQL/HTTP；没有read仍可取得hint和创建；hint不返回引用；撤权/STOPPED/401/部分范围/代际变化/503拒绝 | commerce ui-mysql-selected.log / CentralAudienceMySqlTest |
| 真正登录、输入与409 | PASS：实际专用IdP PKCE回固定路径，必填/重复/501/未来/24小时窗口；真实重复版本409，保留可修改输入 | audiences-write-only-result.json，6条 |
| 未知提交恢复及空快照 | PASS：仅丢弃实际已成功响应，原键/体两次相同；切Tab和取消退出保留；实际SQL各一次，空成员数0 | 同浏览器结果 / 最终演练精确SQL断言 |
| 目录、当前版本与游标 | PASS：A最新v2、UI两个实际摘要，terminal无下一页；后端真实首/次游标逐行核对；目录不返回memberIds | audiences-read-result.json，1条 / 最终result.json |
| 撤权、租户切换与401 | PASS：读写独立，撤权创建隐藏；跨租户无旧摘要；实际logout后注入无效token，401卸载全部Tab | audiences-revoked-result.json，3条 |
| 真实停机 | PASS：实际中央服务停止，目录/hint各503，页面隐藏写操作并提供重新核验 | audiences-outage-result.json，1条 |
| 数据与身份审计 | PASS：恰5审计；原快照A为1:2、2:0；UI c/d为1:2、1:0且source为isolated-ui-import，c真实两成员；UI未创建member_record；停止不删快照 | a5fd3867e690/result.json，647项全PASS |
| 完整回归及当前制品 | PASS：68份Surefire XML合计478/0失败/0错误/5既有skip；8源码/5接入文件SHA256；17模块、661类、45资源/复制JAR一致 | ui-full-test-result.json / ui-source-sha256.json / audiences-ui-source-sha256.json / audiences-ui-runtime-fence-final.json |
| 可见展示 | PASS：Codex实际查看11张1440/390产品截图，表单字段/单主按钮、实际摘要/内部横滚、409/未知/成功/401/503反馈、退出确认尺寸/按钮/遮罩清楚；首轮fixed截图伪影已消除 | audiences-ui-visual-review.json及最终run下audiences-*.png |
| 质量与边界 | PASS_WITH_LIMITATIONS：前端Prettier通过；两仓无阻断，Java formatter/静态分析未配置，auth脚本有既有风格180秒timeout advisory | 两仓最终hygiene JSON；非必需完整辅助技术/原生软键盘/beforeunload实操未验证 |

本片状态转换：implementation-validation COMPLETED/PASS；必需验收全部PASS，A2 DONE（本地），远程Git/CI另行记录。原运行实例/授权数据不切换；证据、数据库、私密IdP和失败记录保留，不执行清理。全目标不缩为人群一片。


## A2 最终Git/CI

CE05-A2完整交付DONE：auth aa117462c5eed9242c86bb24dc1b81c4feb556df / commerce51c771b336adc348007b1fad122f7632b4f576d8均正常推任务分支及main，精确CI36954769355/36954777665 SUCCESS已核验。647实际检查点/11人群浏览器/11已查看截图与478回归证据保持；首轮594后401夹具失败记录保留。下一CE05-CAM0已在auth提交b311e4c并正常推main，精确CI36955364612 SUCCESS已核验；商城CAM1仅完成源影响分析，未改产品代码。
