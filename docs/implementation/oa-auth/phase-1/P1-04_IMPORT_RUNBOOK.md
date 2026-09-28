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
输出 processed/last_sequence 只说明本次进度，不代表所有积压已清除。以 OA status 的提交/确认水位判断积压；目标冲突隔离需要人工处置，不能重置游标或按缺失删除员工。

固定版本 Casdoor 的共享升级 Gate 仍 HOLD；导入测试不授权生产目录接管、共享 IdP 升级或生产部署。
