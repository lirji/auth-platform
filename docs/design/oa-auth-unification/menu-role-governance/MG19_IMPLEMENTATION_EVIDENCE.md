# MG19 实施与文档同步证据（COMPLETE）

对应[冻结验证矩阵](MG19_VALIDATION_MATRIX.md)，复用MG00–18契约，不改产品权限语义、schema、架构、依赖或部署目标。2工具／CI路径指纹 `1eb333ecd497c6dcc7fc85c6b5ba046a31e360956e60e1da5c90098bc013be27`，逐文件保存在mg19-source-current.json。

- 新governance-recovery-runtime.py严格限定已经PASS的自有MG18目录、DB名称、应用、test环境和相同Jar；固定两依赖允许列表，只断自有JVM代理TCP，不停止共享服务／伪造IdP事实。秘密仅内存与既有0600配置，不记录HTTP认证字段或正文。
- 已缓存公钥后版本／introspection断连、空缓存新JVM的JWKS断连、真实图读取断连都实际503；恢复真实上游后成功，身份／权限拒绝仍有效。
- 原命令固定私密UNKNOWN检查点；真实v2提交后断响应，实际Admin退出、新PID同命令恢复回执；更高v3展示修正不改Grant，旧回执重放不降低指针，旧快照和历史摘要保留。
- 既有ProjectionProcessIT以当前代码验证真实SIGSTOP晚写拒绝和图提交后SIGKILL／租约到期／marker恢复，没有新增产品故障开关。
- CI路径覆盖新恢复工具，语法检查涵盖人员／复核及生命周期／退役相关已完成工具；托管CI不运行新本机专库演练或生产发布。

最终23检查（22HTTP＋1SQL保护）、独立当前2进程图专项PASS，详见[MG19_TEST_RESULT](MG19_TEST_RESULT.md)。当前可见UI未修改，复用MG18当前18图／5组行为及MG16终态验收，非全仓重跑。

## PROJECT_DOCUMENTATION_REPORT

增量同步依据040ff19及本片差异，保留原计划／契约权威与历史：运行／回退手册、设计README、计划状态／PROGRESS_STATE／本地CODEX_PROGRESS和最小[文档地图](../../../doc-map.md)已更新。V36结构／复核API／实际页面在MG18契约及验收中已有事实，地图只链接不复制；没有新增选型或拓扑。真实OA引擎、生产运行扫描、生产部署及容量／RTO／RPO未执行，手册明确保留边界。凭据没有轮换或复制到公开文档。

SKILL_HANDOFF：project-documentation／MG19／COMPLETED／PASS，produced为OPERATIONS_RUNBOOK、MG19验收／实施证据及docs/doc-map；下游正常Git交付和精确CI，不授予生产部署。

## 改动路径

- `.github/workflows/authz-ci.yml`
- `deploy/governance-recovery-runtime.py`
- MG19_VALIDATION_MATRIX／IMPLEMENTATION_EVIDENCE／TEST_RESULT／OPERATIONS_RUNBOOK，以及既有README／计划状态／进度和docs/doc-map；只同步当前治理任务。
