# MG18 实施证据（COMPLETE）

对应[冻结契约](MG18_CONTRACT.md)。21源码／测试／工具路径指纹 `184067df8cd88aa55a13c6d9656e7aa529b9dd37a544ea3f953a13a3d20c0b74`；逐路径SHA256记录在私密mg18-source-current.json，最终验收后复核一致。

## 实现

- V36人工复核任务、固定来源条目、追加审计持久化于PG，表和每字段中文注释；原输入不可修改／删除，任务和条目受状态、版本与关系约束。V36已实际应用，不修改历史迁移。
- 创建显式选同一已加载来源页的1–100条，固定成员代际、原依据、完整来源／业务摘要／版本，负责人从当前管理加独立诊断资格候选中选择。任务列表每页20、详情最多100条；不会自动包含其他页或新增来源。
- KEEP只记录保留意见，INVESTIGATE保留未完成；REVOKE复用严格撤权并与条目检查点／幂等回执／审计同事务。GROUP必须确认全组影响。5秒数据库事务不含图RPC，主体／成员／分区／任务锁序与CAS保护资格及版本。
- 确認只接受固定原撤权审计命令、原版本加一和实际COMPLETED操作证明；GET不推进状态。取消不恢复来源，已发WAIT_REVOKE可在取消后和进程重启后继续确认；负责人失权后由当前合格管理者明确改派。
- 新接口无缓存；不存在、跨分区、普通人员和无独立诊断资格统一拒绝。幂等命令使用同一复核命令命名空间与操作摘要，重复或跨操作同UUID改体拒绝，重试不重复副作用。
- 人员变更核对页显式选择来源后创建；侧栏“权限复核”提供持久任务列表、固定范围、逐项决定、完整来源、当前诊断、取消和改派。真实接口守卫、冻结未知结果重试、必填反馈和范围切换清空沿用现有组件。

## 验证与边界

256后端单测、10真实PG、2真实图、50前端单测与类型／生产构建PASS。最终mg18-http-20cc1b859d40：20实际HTTP、5浏览器组、6状态×3宽度18图片全部实看PASS，实际自有Admin退出／重启后原任务恢复；真实图完成一源撤权，其他三源仍ACTIVE。当前Jar SHA256 b345095e95897d6650ada2341c8f81dc8922fb45f37038bc18d3ec1e44e6bdd7，嵌套Jar字节一致。原业务Grant写入0，自有21828／21829端口实际关闭。失败证据、专库及IdP保留。

详见[MG18_TEST_RESULT](MG18_TEST_RESULT.md)。OA目录／批准为受信HTTP契约夹具，真实OA引擎未联调；Auth、PG、IdP及授权图真实运行。不是生产部署或容量／恢复时间承诺。

## 改动路径

- `auth-console/src/api/accessReview.ts`
- `auth-console/src/governance/ReviewCreate.tsx`
- `auth-console/src/governance/accessReview.ts`
- `auth-console/src/pages/GovernancePage.tsx`
- `auth-console/src/pages/GovernancePersonnelPage.tsx`
- `auth-console/src/pages/GovernanceReviewsPage.tsx`
- `auth-console/src/router/routes.tsx`
- `auth-console/tests/governance-access-review.test.mjs`
- `auth-platform-admin/src/main/java/com/lrj/authz/admin/governance/GovernanceReviewController.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/application/AccessReviews.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/application/PortalPermissions.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/domain/AccessReviewModels.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/persistence/AccessReviewMapper.java`
- `auth-platform-governance/src/main/java/com/lrj/authz/governance/persistence/GovernanceRuntime.java`
- `auth-platform-governance/src/main/resources/db/governance/migration/V36__manual_access_review.sql`
- `auth-platform-governance/src/main/resources/mappers/governance/AccessReviewMapper.xml`
- `auth-platform-governance/src/test/java/com/lrj/authz/governance/persistence/AccessReviewPostgresIT.java`
- `auth-platform-governance/src/test/java/com/lrj/authz/governance/projection/AccessReviewProjectionIT.java`
- `auth-platform-protocol/src/main/java/com/lrj/authz/protocol/AccessReviewDtos.java`
- `deploy/governance-access-review-runtime.py`
- `deploy/governance-access-review-ui.mjs`
