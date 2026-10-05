# MG17 实施证据（COMPLETE）

本片对应冻结的 [MG17_CONTRACT](MG17_CONTRACT.md)，复用原目录、关系型数据库、Mapper、严格投影、独立诊断及现有组件。当前28路径指纹：`0c88e0c87993be07e6ced221fc5d4b7cc3a565c9e5f26a7e0439f381d210e268`。不写原业务Grant，不回写OA，不自动岗位授予或部署生产。

## 实现与约束

- V35增加不可变目录前后证据，与Inbox、当前目录、版本回执、原审计、投影意图及检查点在同一事务提交；字段全部中文注释。旧版本标OBSOLETE，重放不追加，旧历史不补造。
- 接受员工事件先按主体→成员锁顺序取得真实前值，手工暂停不被OA ACTIVE绕过；只保存状态、任职和成员代际，不复制登录绑定或原始审批正文。
- 人员GET固定完整分区与目标。读取前后重新核验管理、诊断和当前身份版本；不存在／跨租户统一拒绝并审计。SQL集中Mapper，所有值绑定。
- 变更和来源每页100，游标绑定分区、目标、类型和全依据摘要；来源保留旧个人代际、完整GROUP历史关系和真实撤权回执。10000依据／关系上限显式INCOMPLETE；单次读取5秒，无事务内RPC或N+1。
- 摘要含真实期限是否已开始／到期，采用clock_timestamp防止长事务起点冻结时间；没有用checked_at让每页必然失效。仅本地检查点保持source_sync UNKNOWN。
- 人员页及原来源诊断双向入口绑定真实GET；失败、读取中和范围切换隐藏旧数据。任职用中文标签和日期展示，历史关系与真实撤权分别说明。

## 窄验证与限度

后端256单测通过；最新10项人员PG＋2项读取期间真实失权PG通过；3项真实图验证调岗保留个人／OA来源、逐源撤销、新代际和两企业全局暂停，业务HTTP夹具在服务端生成资源事实。此前未变化Directory17／Portal16回归有效，不冒充同一次运行。前端46单测与最新类型构建通过。

最终mg17-http-ff60945b6ce2的114实际HTTP／5浏览器组／18三视口当前图全部PASS，图片全部实际查看；当前Jar SHA256 5eb86bb0f88bf5221b648045413d3936c544af42698fb40a70fba7a0fd4a5479，嵌套Jar字节核对通过，浏览器写入0、原业务Grant写入0。自有21826／21827已退出，全部失败、专库和截图保留。完整验收与8项卫生人工复核见[MG17_TEST_RESULT](MG17_TEST_RESULT.md)。OA目录和批准传输为契约夹具；Auth、PG、图及IdP真实运行，未连接真实OA引擎。当前图测量不代表性能或恢复时间承诺。

## 改动路径

- `auth-console/src/api/governance.ts`
- `auth-console/src/governance/personnelImpact.ts`
- `auth-console/src/pages/GovernancePage.tsx`
- `auth-console/src/pages/GovernancePermissionsPage.tsx`
- `auth-console/src/pages/GovernancePersonnelPage.tsx`
- `auth-console/src/router/routes.tsx`
- `auth-console/tests/governance-personnel-impact.test.mjs`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/governance/http/GovernanceAccessController.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/application/DirectoryGovernance.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/application/DirectoryJson.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/access/application/PersonnelFacts.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/access/application/PersonnelImpact.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/access/application/PortalPermissions.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/domain/DirectoryModels.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/persistence/DirectoryMapper.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/runtime/persistence/GovernanceRuntime.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/access/persistence/PermissionMapper.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/access/persistence/PersonnelImpactMapper.java`
- `auth-platform-governance/src/main/resources/db/governance/migration/V35__directory_change_evidence.sql`
- `auth-platform-governance/src/main/resources/mappers/governance/DirectoryMapper.xml`
- `auth-platform-governance/src/main/resources/mappers/governance/PermissionMapper.xml`
- `auth-platform-governance/src/main/resources/mappers/governance/PersonnelImpactMapper.xml`
- `auth-platform-governance/src/test/java/com/lrj/authz/governance/access/application/PersonnelImpactQualificationIT.java`
- `auth-platform-governance/src/test/java/com/lrj/authz/governance/shared/persistence/PersonnelImpactPostgresIT.java`
- `auth-platform-governance/src/test/java/com/lrj/authz/governance/projection/PersonnelImpactProjectionIT.java`
- `auth-platform-protocol/src/main/java/com/lrj/authz/protocol/PersonnelImpactDtos.java`
- `deploy/governance-personnel-impact-runtime.py`
- `deploy/governance-personnel-impact-ui.mjs`
