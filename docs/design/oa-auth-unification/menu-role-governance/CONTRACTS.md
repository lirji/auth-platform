# 菜单发布治理阶段 A 契约（MG00）

状态：阶段 A 冻结，2026-10-02。授权来源：用户已确认计划并要求开始实施。此文件只定义 MG00–MG06，不授权生产部署或修改业务 Grant。

## 1. 兼容与不变量

- 原 Manifest schema_version=1、100 菜单／200 能力／128 KiB 上限及 V20 展示快照规则不变。稳定 menu.code 不因 route／label 变化而重建。
- 原清单 JSON／content_hash 继续不含 label／position；presentation_hash 单独计算。不得改写历史版本／迁移或扩大管理员委派。
- 目录发布不创建角色、Grant、成员、范围或投影生效回执。应用目录仍是应用级，不声称新增环境隔离。
- Owner 发布和分区诊断是两个授权边界。本人查询、普通管理、他人来源诊断不能互相替代。

## 2. MG01：项目声明

Commerce `frontend/src/iam/catalog.json` 保存 schema_version、application、capabilities、menus；字段形状沿用 Manifest，但不包含部署版本。它是路由和能力关联配置，不是业务 Mock 数据或用户授权。

声明同时生成 centralGroups／centralRoutes 和发布候选；有名称的真实经营入口参与导航，遗留无名称条目保留为显式兼容目录，不自动加入经营侧栏。协作入口的名称也取自声明。

导出输入是固定 Git 提交的声明；候选为 `{manifest, source: {commit, artifact_hash}, auto_grants:false, auto_roles:false}`。commit 为 40／64 位小写十六进制，artifact_hash 为声明原始字节 SHA256。发布版本显式指定并大于已发布版本；原能力必须保持完整且语义一致，菜单删改进入差异。生成器不登录、不发布或写授权。

## 3. MG02：只读预览

`POST /api/governance/v1/catalog/preview` 仍由真实 HUMAN Owner 调用，输入为原 Manifest。

原 application/current_version/proposed_version/content_hash/added/retained 保留；兼容增加 presentation_hash、menu_changes、violations、publishable。menu_changes 按稳定 code 排序，含 code、kind（ADDED/REMOVED/CHANGED）、before/after 菜单、fields（label/position/parent/route/any_of）。纯列表排序不产生差异。

MG02实现细化：affected_capabilities 为稳定排序的潜在关联能力并集；retained 只含语义完整保留的原能力。violations 为 `{code, capability, before, after}`，code取 CAPABILITY_REMOVED、CAPABILITY_CHANGED、VERSION_REGRESSION、SAME_VERSION_CHANGED；能力冲突保留旧／新能力事实，版本冲突后三字段为null。新增／删除菜单的fields列全部五个展示／映射字段。

结构损坏、未知字段、重复键、超限仍 INVALID_ARGUMENT（400）。原能力删除／resource_type 或 risk_level 改变、倒退版本、同版内容改变以 violations 展示并 publishable=false；实际发布继续 VERSION_CONFLICT（409）。预览不写快照、命令或授权；非法预览也不能获得可发布资格。

变化的潜在关联能力是变更节点及两版相关子树 any_of 的并集；它表示入口／展示可能受影响，不是资源授权证明。相同编码下业务实现扩大语义无法自动检测，必须在项目变更中评审。

## 4. MG03：分区潜在影响

具体请求／报告字段、组任职日期、联合游标、运算边界及一致性细化见 [MG03_CONTRACT](MG03_CONTRACT.md)，沿用本节独立诊断边界。

新增 `POST /api/governance/v1/access/catalog-impact`，输入 partition（tenant_id/application_id/environment）、manifest 和可选稳定游标。manifest 必须与分区应用一致。

身份必须同时满足当前管理委派和该分区／当前成员代际的 PortalDiagnosticAuthority；访问有独立诊断审计。Owner 仅有发布权不能查询；403 不显示为零影响。

报告返回目录基础版本、候选双摘要、统计时间、basis_hash、完整性、受影响角色、有效候选 Grant 数、PENDING 数、去重成员数、组来源数、相关申请策略及有界来源／成员详情。详细项每页最多 100，统计由服务端完整聚合而非浏览器当前页。

统计排除 REVOKED、已到期、未生效期限、无效企业／应用准入、停用主体／成员及旧代际；组只展开当前有效成员资格。ACTIVE 与 PENDING 分开，策略／目录栅栏状态单独报告；潜在影响不直接标为实际资源 ALLOW。各项基于同一有界数据库读快照，输出前重验调用资格和基础目录；资格或依据变化返回冲突／拒绝，不使用旧数据。

## 5. MG04／MG05：来源记录与受控发布

新发布流程使用独立 envelope，不给旧 Manifest 添加来源字段。来源缺失明确 UNKNOWN，不伪造源码或部署事实。历史列表按固定版本游标返回最多 100，并受 Owner 或已有明确管理读授权保护。

发布记录与权限快照、展示快照、指针和审计同事务追加，包含固定版本、两摘要、已认证发布人、命令、来源和原因。命令按应用＋command_id 唯一，同键同体返回原回执，同键改体 COMMAND_CONFLICT；后续版本存在时重试旧成功命令只读取原回执，不倒退指针。

新增受控 release-preview／release-publish 路径，发布绑定固定候选、来源、基础版本和基础摘要；预览有效期有界。若带有分区影响确认，发布重验诊断资格及报告依据。确认说明不能代替接口独立授权或成为全租户无影响证明。

发布门禁通过同一应用策略保护所有写入口。旧应用默认保持旧契约；成功切入受控发布的应用不能再借旧 HTTP／CLI 入口跳过依据检查。启用和成功发布记录可追溯；旧节点退出前不得启用无法执行门禁的写流量。

仅展示调整不自动增加授权；route／any_of／新增入口复用原能力等变化明确要求业务变更原因和处理决定。依赖不就绪不得伪造业务权限已生效。

## 6. MG06：版本核对

核对固定来源制品与当前发布目录的两摘要及来源。区分 NOT_PUBLISHED、SOURCE_MISMATCH、DISPLAY_MISMATCH、MATCHED_DECLARATION 和 UNKNOWN；MATCHED_DECLARATION 只证明声明一致。

运行一致只有受信实际部署制品核验可证明；未经核验默认 UNKNOWN。输入不接受任意网络 URL 拉取，不自动回退目录、Grant 或部署。核对失败显示原因和重试入口，403 与无记录分开。

## 7. UI／验证

沿用现有治理页面、GovernanceModal、Failure 和 useCommand。覆盖真实预览、差异详情、影响授权拒绝／明细、发布历史、核对结果、修改后预览作废、未知结果原键重试、关闭保护；1440／390／320 视口实际操作和截图查看。

MG01 验证声明／导航一致和非法声明拒绝；MG02验证纯差异与 Owner HTTP；MG03–MG05 使用隔离真实 PG 验证权限、统计、事务、并发及重试。所有业务读写验证只在已授权隔离目标中，不重发当前本机 commerce v2，不更改原业务 Grant。
