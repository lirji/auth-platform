# 受控停用运行说明

运行者必须具有专用治理库操作权限。复用私密数据库属性 `jdbc.url/jdbc.username/jdbc.password`，另外配置 `lifecycle.operator-ref` 和 `lifecycle.scope`。scope 为规范企业 UUID 或全局主体专用 `GLOBAL`，不能混用。配置和命令文件都要求 0600；操作者、范围不得从待执行命令中选择。

命令文件仅四个属性：`command.id`、`target.id`、`expected.version`、`reason`。命令和目标 ID 为规范 UUID；版本为当前正整数；原因必填且不可含凭据。相同有意命令重试使用同一 ID 和相同内容，冲突后重新读取当前事实并做业务决定，不能自动重试旧版本覆盖。

运行示意（私密文件需由受控运维流程创建，不提交真实凭据）：

```sh
java -Dloader.main=com.lrj.authz.governance.cli.LifecycleCli \
  -cp auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  suspend-member /absolute/private-lifecycle.properties /absolute/private-command.properties
```

全局主体停用使用 `suspend-principal` 和 GLOBAL 配置。成功只输出目标 ID、SUSPENDED、当前版本；失败输出稳定错误码并非零退出。CLI 仅 validate，V2/V3 迁移仍需由受控 migration owner 完成；本地隔离验证复用既有 bootstrap/PG profile 的迁移入口。没有自动迁移共享库。

应用代码回退应保留新增审计列和已停用事实，不能运行恢复 SQL 来“回滚”安全效果。已验证 P1-03 旧 JAR 在 V3 库读取仍拒绝停用成员；更早或其他消费者需要自己的兼容证据。

本机验证：`GOVERNANCE_TEST_CONFIG` 指向隔离库后执行 `./mvnw -B -Pgovernance-it verify`；固定候选的真实 Token 夹具有效时执行 `python3 deploy/governance-context-smoke.py`。工具只操作自建成员，并停止自身应用进程。现行 HTTP 工具已使用真实 CLI，不再需要 Docker PostgreSQL 容器参数；P1-03 历史报告中对应旧调用保留为版本事实。
