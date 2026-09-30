# CE05-R 营销规则员工权限契约

沿已批准扩展边界，复用 marketing-runtime 的 MarketingAssets/MarketingAssetService、AssetMapper、RuleNode 和 MarketingAssetController。`marketing_rule` 首批 TENANT_ALL；`rule.read` NORMAL，`rule.create/publish` HIGH，三个能力独立，不新增OA审批或业务状态。

|ID|Owner与结果|Needs|验收|状态|
|---|---|---|---|---|
|CE05-R0|auth稳定资源类型、三有限执行能力|E2本地DONE且Git交付|HUMAN/60秒、精确类型/能力、创建只集合、读取集合或实际规则、发布实际规则，真实PG/图/SDK兼容|DONE（本地）|
|CE05-R1|commerce独立RULE接管族、实际版本Owner、事务身份审计、追加迁移|R0验证交付|独立读/创建/发布，原回执前路由/事实/期限检查，审计回滚、原键/撤权/STOPPED/503；原规则树和版本语义兼容|READY|
|CE05-R2|固定规则员工页、两独立hint、真实浏览器|R1验证交付|目录/字段、独立创建/按已知规则版本发布，409/未知/401/403/503、1440/390截图查看和精确SQL审计|TODO|

## R0 有限执行协议

沿 Reference/ScopeCheck/Check，不新增JSON字段。稳定类型 marketing_rule；创建不能构造未来对象事实，读取/发布可绑定实际 ruleId 与规则资产 version，完整租户集合仅作为动作资格提示。TENANT_ONLY 禁止伪门店/部门字段，身份代际、应用/环境、期限、目录版本及原授权路径交集均沿原协议，撤权重授不复活旧引用。

## R1 业务与事务

精确入口 GET/POST `/v1/admin/rules`、GET `/v1/admin/rule-fields`、POST `/v1/admin/rules/{id}/{version}/publish`。目录和可信字段均需要 rule.read；把 Controller 中直接 requireAdmin 的字段目录收归同一业务权限入口，返回前范围复核。目录仍按租户+ruleId全局最新版本及稳定游标，不返回全部历史版本；查询无副作用。

创建先集合许可，再沿原 ruleId64/name128/正版本和 RuleNode 可信字段、类型、深度、节点数量、重复条件校验。原 command input 在旧模式不变，中央只加入稳定身份；路由锁/期限先于旧回执，实际ruleId身份审计、定义和命令同事务。

发布沿原 `List.of(id, version)` 意图，中央只追加稳定身份；业务层先读取实际租户+id+version，再绑定规则事实。事务内先锁路由、再锁真实版本、复核事实和期限，之后读取旧回执。资产version为不可变内容版本，不能误称为状态修订号；状态沿原DRAFT→PUBLISHED，已经PUBLISHED的新命令仍返回成功，保留既有行为。原条件更新检查行数；不得为此增加伪expectedVersion或改写既有迁移。审计目标实际ruleId，具体资产version由同命令响应关联。正常回退需识别新增RULE族；迁移序号实施前核对，V49—V61不改。

publishedRule(tenant, Ref) 是受信任活动/交易的内部引用入口，保留已发布固定版本及原校验；员工撤权不撤销已经发布的规则，也不替代客户/系统执行身份。测试区分员工管理和受信任决策链路，不以模板或客户端事实冒充交易输入。

## R2 页面与恢复

固定 `/operations/rules?tenant_id`，目录/创建/发布三Tab。GET `/v1/operations/rules/create-access` 与 `/v1/operations/rules/publish-access` 独立资格提示；提交仍绑定实际业务许可，hint不产生可复用凭据。目录显示实际最新版本、状态与规则详情，可信字段目录仅在read授权下查询。

创建沿原 Rule(ruleId, version, name, rule) 与既有可视 RuleEditor/RuleSummary、Ant Design、SSO壳；编辑器固定协议选项不是会员或业务Mock，服务端可信字段校验仍是权威，不为了创建隐含赋予read。发布输入已知ruleId+正版本，不要求先有目录read。ID64、name128、页面安全整数；不开放任意URL或请求头，正确编码冒号/点/连字符。

未知结果冻结原键/体/编码目标，切Tab/取消退出保留，重试不得变为新意图；409保留可纠正输入，401卸载，403独立拒绝，503关闭写表单并可重核验。真实PKCE、无read两写、规则校验、实际版本发布、撤权和停机需浏览器及SQL验证；1440/390截图实际查看。生产映射、目标与Owner持续HOLD。


CE05-R0本地DONE：marketing_rule稳定类型和read/create/publish三个独立有限能力；创建只集合，读取/发布绑定实际资产版本。完整252单元、真实自有PG5ff5f702ccbc/SpiceDB共13项ExecutionAuthorizationIT、SDK Boot4和最终forceCreation install通过，两个运行Jar嵌套依赖及4源码摘要一致。hygiene无阻断，Java formatter/静态分析限制保持。无新迁移，自有PG已finally停止；下一R1商城RULE族/实际Owner/事务审计，R2与其余CE05—08未完成。
