# P1-03 运行与验证

两个既有宿主使用 `authz.governance.enabled=true` 和 `authz.governance.configuration=/absolute/private.properties` 显式启用。默认关闭，开启但无有效配置时启动失败。配置必须普通 0600 文件、最多 64 KiB；运行期间不动态刷新。不要把秘密放入启动参数、浏览器或版本控制。

共有数据库属性：`jdbc.url`、`jdbc.username`、`jdbc.password`，可选连接池上限沿用 GovernanceDatabase；必须是治理专用库，先用既有 CLI 初始化，HTTP 宿主只校验迁移。不会迁移旧审计库或 Casdoor 库。

admin 文件再提供 `issuer`、`jwks.uri`、`audience`、`client.id`、`client.secret`、`version-probe.client.id`、`version-probe.client.secret`。连接、读取超时和并发上限沿用 TokenAuthority。仅接受实测固定 v4.11.0；共享旧实例不可用。

server 文件再提供 `service.count`（1–32），每个 `service.N.` 下配置：`id`、`application-id`、`environment`、`operation=context.resolve`、`credential-sha256`（高熵服务凭据的 64 位小写 SHA-256）。每个 `service.N.user.` 下配置上述 TokenAuthority 全部属性。相同服务 ID 或摘要不能有多个绑定。服务凭据原文通过受控秘密渠道交付所属后端，不发送给最终用户。

接口：

- GET admin `/api/governance/v1/me/memberships`，`Authorization: Bearer <user access token>`。返回 `memberships` 数组和 `trace_id`；每项为 `membership_id/tenant_id/member_kind/membership_generation/membership_version/valid_from/valid_to`。
- POST server `/internal/governance/v1/context/resolve`，`Authorization: Bearer <service credential>` 与 `X-User-Access-Token: <user access token>`。JSON 仅 `tenant_id` 和可选正整数 `expected_membership_generation`，最多 4096 字节。返回 P1 契约 AccessContext；重复 Header、重复/未知 JSON 字段及尾随 JSON 拒绝。

以上只提供身份事实，业务方不能将解析成功当作授权。关闭开关可以退出新 HTTP 路径，保留成员停用/审计事实；旧接口仍由原安全链管理。没有共享 IdP 切换或生产发布动作。

本地复现（固定发行方夹具按 P1-02 文档准备，必须先构建 JAR）：

```sh
GOVERNANCE_TEST_CONFIG="$PWD/.local/governance/database.properties" ./mvnw -B -Pgovernance-it verify
GOVERNANCE_TEST_CONFIG="$PWD/.local/governance/database.properties" GOVERNANCE_IDENTITY_FIXTURE="$PWD/.local/governance/casdoor-isolated" ./mvnw -B -pl auth-platform-governance -am -Pgovernance-identity-it verify
python3 deploy/governance-context-smoke.py
```

HTTP 工具只绑定回环 18091/18092，端口占用则失败，使用 18090 固定候选及自有数据库检查点。每次创建独立 `p1-03/http-*` 目录，私密文件 0600，真实进程日志保留；成功后停止本次应用进程，保留验证数据以供审查。失败时同样停止自己的应用，不删除数据。CI 使用同样命令与一次性专用 PostgreSQL；通过 `--postgres-container` 指向 CI service。
