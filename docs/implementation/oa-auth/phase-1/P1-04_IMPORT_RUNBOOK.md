# P1-04 受控导入

先完成既有治理库迁移和目标企业建立、OA 目录接管与出口配置。运维配置必须是普通 0600 文件，不能提交真实凭据：

```properties
jdbc.url=jdbc:postgresql://127.0.0.1:<port>/<governance_db>
jdbc.username=<role>
jdbc.password=<private>
directory.id=<stable_source_uuid>
directory.source=oa
directory.environment=test
directory.source-tenant-ref=<positive_oa_tenant>
directory.tenant-id=<existing_auth_tenant_uuid>
directory.issuer=<exact_casdoor_issuer>
directory.endpoint=https://<oa-host>/internal/directory/v1
directory.credential=<private_service_credential>
directory.operator-ref=<operator_reference>
directory.max-events=1000
directory.timeout-ms=3000
```

使用既有可执行宿主 JAR 内的 CLI：

```sh
java -Dloader.main=com.lrj.authz.governance.cli.DirectoryImportCli \
  -cp auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher register /private/directory.properties
java -Dloader.main=com.lrj.authz.governance.cli.DirectoryImportCli \
  -cp auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher pull /private/directory.properties
```

`register` 先核对来源再登记，重复登记不覆盖权限范围；`pull` 只消费既有来源。数据库、服务出口超时后可重新运行同一配置，先恢复确认，再取连续检查点后的事件。
输出 processed/last_sequence 只说明本次进度，不代表所有积压已清除。用同一 CLI 的 `status` 命令查询目标隔离状态和双端水位：

```sh
java -Dloader.main=com.lrj.authz.governance.cli.DirectoryImportCli \
  -cp auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher status /private/directory.properties
```

- `PENDING`：来源已提交、本地已导入、来源已确认水位尚未全部相等。
- `ACKNOWLEDGED`：本次观察三个水位相等，且本地未隔离；不承诺查询完成后没有新增事件。
- `CONFLICT`：本地已持久化隔离，或来源水位与本地不可能一致。已隔离时无需 OA 在线，未知来源/确认水位输出 `null`。

`status` 不消费、确认、清除隔离或修改检查点。非隔离情况下 OA 不可用会非零退出，不能误报已确认。目标冲突需人工核查来源配置和保留事件；不能重置游标或按缺失删除员工。

固定版本 Casdoor 的共享升级 Gate 仍 HOLD；导入测试不授权生产目录接管、共享 IdP 升级或生产部署。
