# P6 本地运行说明

## 固定范围

用户确认OA目录权威、本地、commerce-platform首批、选择一个既有运营租户隔离演练，并选择完整CATALOG能力。P6_SELECTED_UNIT.json固定来源；P6_REHEARSAL_RESULT.json记录当前31项验收。原commerce_local和8602不切换。

## 工具与运行

- `deploy/governance-p6-migration.py snapshot`：只读InnoDB一致快照，4MiB/每表1000行、字段白名单、0600排他输出。
- `dry-run --snapshot ... --sha256 ... --mapping ... --output ...`：未知主体/范围/能力隔离；活跃永久来源无显式期限决定隔离。全为拒绝时退出2且ready_for_import=false，不能冒充正向接管已验证。
- `MigrationImportCli <0600治理配置> <0600批次JSON>`：最多100条，每条独立事务。配置固定migration.tenant/environment/issuer/subject；复用当前管理员委派及正式Grant写用例。单元/来源/sequence和指纹防重放冲突，旧序号拒绝，拒绝墓碑单向。新Grant还需真实投影收敛才有业务ALLOW。
- `python3 deploy/governance-p6-rehearsal.py --commerce-root ../commerce-platform`：使用已建立的隔离IdP18090/图18544和当前打包JAR；新建自己持有的PG/MySQL库、回环18161/18162/18661进程，结束只停止自己的Popen。原商城只读。完整配置/Token留.local/governance/p6/rehearsal-*，不可提交。

运行前构建auth admin/server/SDK及commerce包；commerce SDK来源须钉本次auth已交付SHA。需要已有dev_infra-mysql84、dev_infra-postgres16、P3图、P2隔离Casdoor夹具。工具不自动重启共享业务或升级IdP。

## 开关与凭据

- auth需既有governance/access/scope开关及新增`authz.governance.executions.enabled=true`；私有文件必须含`execution.callers=commerce-p6`，且该列表是scope.check.callers子集、commerce资源Owner含store。默认关闭。
- commerce需`commerce.iam.store-read.enabled=true`、`commerce.iam.catalog.enabled=true`及0600的`commerce.iam.store-read.configuration`。沿用固定central.url/credential/application/environment；不从用户body读取服务身份。
- `/v1/operations`既有商品/SKU/类目/模板/图文/条码/价格/任务接口以SSO Bearer和X-Tenant-Id进入新认证链，最终仍由当前本地Actor和数据库权威路由判定。省略/替换租户头不能绕过用例守卫。
- 执行引用只在服务端任务快照持久化；同步引用60秒，任务引用取明确deadline，最长37天+60秒。执行检查只用服务凭据+引用，绑定原身份版本、目录epoch和同Grant范围；不保存用户Token。
- V48触发器由独立Flyway迁移Owner创建：Spring原生SPRING_FLYWAY_USER/PASSWORD可指定迁移连接；业务COMMERCE_DB_USER/PASSWORD保持普通应用账号。隔离演练先Owner迁移后停止，随后不带Owner凭据重新启动并验收。CI同样分开连接，私密值仅写0600运行文件。

## 失败与恢复

本地测试第一次V48因binlog触发器权限失败；核对仅本次V48失败、路由空表、没有其他校验错误后，用受控Flyway repair并完成同一迁移，未删除业务数据，未修改共享MySQL全局变量。失败记录与恢复日志保留commerce/.local/p6-migration-recovery.log。正式环境不得盲目repair；先核对DDL部分提交、版本和既有表结构。

中央故障、身份/目录版本变化、引用到期、任何当前拒绝都会停止任务推进。已切换单元回退只能STOPPED，恢复CENTRAL时仍保留新拒绝；旧表状态可能仍ALLOW，不得据此打开旧权威。生产限制和历史下线条件见P6-07_CANDIDATE_REPORT.md。
