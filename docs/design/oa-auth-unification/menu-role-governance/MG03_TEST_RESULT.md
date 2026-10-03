# MG03 潜在授权影响验证

状态：PASS，MG03 DONE；全计划ACTIVE，下一片MG04。未生产部署，未修改原Commerce目录／业务角色／Grant。

## 实现与证据

- 独立 `POST /access/catalog-impact` 校验当前HUMAN管理委派和相同分区／当前代际诊断资格；拒绝持久化DENIED诊断审计。Owner／普通管理权不替代诊断权。
- PostgreSQL一次语句给出完整计数与三组最多100条明细，人员按稳定主体去重，ACTIVE／PENDING分开；组资格按现有业务时区和任职日期规则，不仅检查current。新鲜第二次SQL重验事实与管理资格，变化拒绝；游标绑定完整依据。
- 报告包括固定角色、有效来源、当前成员代际／范围／期限、启用及停用申请策略、投影／目录栅栏、来源隔离和紧急停用。10000依据事实上限外明确无完整依据和续页，完整计数仍来自SQL。
- 202项编译／单测（protocol20/core27/governance101/admin54）PASS，严格JSON新增4测试；旧Manifest同步拒绝数值字符串和枚举序号，旧有效JSON／摘要不改变。
- 34项真实隔离PG PASS（新增CatalogImpactPostgresIT11、Portal15、PublishedCatalog8）。覆盖多角色／多来源／组与直接重叠、撤销／到期／未来／停用／旧代际排除、空组、错误环境／隔离、角色／来源／策略100以上分页、未授予新能力、Owner无诊断及失去委派、10002角色超限、两实际SQL之间并发撤销与管理失权。
- 真实XML BoundSql绑定参数的EXPLAIN ANALYZE：隔离10002角色实测88.902ms（单语句执行时间），在已有4秒statement_timeout内；不是生产容量、P95或端到端承诺。完整计划位于忽略的0600 `mg03-query-plan.private.json`。
- Console类型检查／构建及25项行为测试PASS。候选／分区匹配、完整性缺失、超限／错误游标不能变成零影响；修改候选取消旧请求。
- 真实Casdoor PKCE、实际新Admin JAR、隔离PG：18项HTTP检查PASS；实际CatalogEditor组件壳5组交互PASS，最终证据 `.local/menu-role-governance/mg03-721ee289a30f`。页面测试不是完整门户PKCE演练。真实Owner无诊断403清掉旧计数；503为明确注入的UI故障分支，不作为真实数据库恢复证明。
- 1440/390/320的统计、来源范围和拒绝9截图均查看，布局证据 `.local/menu-role-governance/mg03-a25fd524b51f`；最终同等UI重新产生9图。无横向溢出，长ID／范围可换行，底部操作可用。页面验收0发布写入；仅通过原用例创建本次随机隔离角色和3个PENDING来源，原业务对象0写入。自有进程已停止。
- Python编译／Node语法／diff PASS； `mg03-hygiene-final.log` 为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无BLOCKING。仓库无统一formatter／静态分析；数字启发式为已命名预算常量，事务仅复用独立诊断审计，不持远程锁或新增授权写入。

## 失败闭环

- 首轮PG测试误用DirectoryAuthority.sourceId和旧表名，修正夹具后通过。
- 组范围数据库保存camelCase，不能用公开snake_case Mapper猜读；已用私有SourceRow和既有ScopeRules.decode转换，真实组范围验证通过。
- Jackson关闭标量转换仍允许数字转Textual；显式CoercionConfig拒绝Integer／Float／Boolean，严格边界测试通过。
- 首轮HTTP缺少presentation所需图配置，装配既有隔离图配置后启动；仅配置读取，不借此宣称资源ALLOW。外部HUMAN先补本次分区成员夹具再验证无诊断资格。
- 首次浏览器来源选择器误命中角色中的PENDING计数；改为真实DIRECT来源选择器，随后5组通过。测试取消分支不吞异常，只接受浏览器明确ERR_ABORTED。
- 日志、失败与修复后的同版本证据保留于忽略的 `.local/menu-role-governance/mg03-*`，没有删除测试／放松安全条件。

## 下游

MG04追加发布来源和历史，同事务保存，原历史不补造来源。MG05影响确认只能使用COMPLETE依据，并在发布时重新授权和重算，不复用旧完整结论。Git／CI结果写入进度；现有MG02 e62abe6精确CI37102284667 SUCCESS、Commerce47ebb65精确CI37100968743 SUCCESS。
