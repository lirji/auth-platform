# P4-07 TEST_RESULT

status=COMPLETED；G4=PASS。本报告验证本地隔离环境，不代表生产上线或容量承诺。Git/远程CI单独记录。

## 真实跨进程证据

[evidence/e2e.json](evidence/e2e.json)：60次HTTP检查、41个不同检查名，全部通过；断言还覆盖实际状态与授权结果。使用真实Casdoor PKCE用户、邀请接受后的PARTNER成员、auth管理/授权及独立worker、OA JWT、独立Kafka 3.8/Flowable、PostgreSQL 16.15和P3 SpiceDB。没有LOCAL工作流或Mock回调冒充G4。

- request_id：be52c9e3-a005-465b-a5a0-34ce6c3514c8；OA实例：22。
- grant_id：ad93eb90-4cab-4b25-9470-80cdaba4d28e。
- 实际生效operation_id：9aeb5067-89b8-4d8c-8234-8b8110a3650a；实际回收operation_id：61976b9f-13ff-4545-a531-5c8baa799145。
- 短期申请37d001cd-3f65-43c5-8974-a2a6201ebb7c在OA和清理worker停止后到期拒绝，随后可靠清理。

验证：未审批拒绝；审批人读取固定request_version/snapshot_hash/RoleVersion；外部人无法读OA工作台或审批依据；扩大角色版本不扩张原申请；OA离线启动保留重试；真实办理后签名回调；真实转发提交后故意丢首个ACK，同event/body以不同nonce重试且只有一条Grant；图连接故障保持未生效和拒绝，恢复后真实回执ACTIVE；S001允许/S002拒绝；OA完全停止不影响既有授权；取消已批准申请回收本来源，独立DIRECT查询仍允许。

P3双令牌读可能短暂保守DENY，新增ALLOW断言最多等待10秒读取追平，不改成默认允许或绕过栅栏。到期/撤销反例仍直接要求拒绝。

## 回归和必要修复

- auth全reactor `-Pgovernance-it verify` exit0：231单测、86真实PG（含19项申请用例）；P3完整25项真实图/CAS/跨进程恢复通过，最终局部修改后RequestProjectionIT复验通过。
- OA全reactor `mvn verify -Pdirectory-it` exit0：195单测（既有1项虚拟线程pinning探针条件跳过），31集成项（既有1项需要额外目录Casdoor夹具的离职测试条件跳过）。本片CentralApprovalPostgresIT 5项全部通过。这里未把历史条件跳过说成本次通过；本次P4真实Casdoor与外部成员完整链路另有上述证据。
- OA permission/API golden、架构检查、OpenAPI生成一致性、TypeScript和控制台build通过。仅同步本片3个新增API及4个直接引用schema，旧OpenAPI其他历史漂移未借机改写。
- 两仓Code hygiene通过：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，唯一工具限制为既有统一格式化器未配置。事务人工复核无新增远程调用进入auth原子授权事务；OA继续复用原工作流边界。

真实链路发现并修复两处原流程缺陷：异步任务先出现导致OA待办缺申请引用，回绑后补齐且不复活DONE待办；原注解SQL把`<>`误写XML实体，导致引擎已办理但OA完成写入失败。加入真实SQL回归。当前指定审批人的固定依据读取补齐，回调扫描在LIMIT前过滤已绑定分区。auth补能力紧急停用的批准重验和UNAVAILABLE展示，避免停用后新增授权或虚假ACTIVE。

## 失败记录与边界

原始日志在忽略的`.local/governance/p4/`保留：首次共享PG连接不足，改用专属PG；真实待办绑定失败、完成SQL错误均在修复前留下失败日志；一次HTTP拒绝断言误写200，改为真实403；邀请夹具补齐独立TokenAuthority配置；两次过早ALLOW断言按已有P3保守读语义改为有界追平。最终e2e-run8 exit0是通过证据，前序失败不计PASS。

运行说明见[RUNTIME_SPEC](RUNTIME_SPEC.md)。未修改共享总线信任，未停止用户其他进程、删库/卷、升级依赖或生产部署。邮件/短信未实现，通知仅可靠站内渠道；生产TLS/ACL、容量、保留期限、灰度及恢复演练仍按后续阶段。

## 远程CI补充

OA初次远程运行暴露架构扫描误扫CI依赖仓库的问题，限定直属Maven模块生产源码并增加临时目录回归，两项定向测试通过；未增加旁路白名单。最终OA比前述本地全量记录新增1项扫描边界测试。两仓最终ref与远程结果见[CI_RESULT](CI_RESULT.md)，首次失败保留，不计为PASS。
