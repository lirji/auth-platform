# 治理身份模块

P1-01 当前实现：Principal/LoginIdentity/Tenant/Membership/旧身份映射/幂等命令/追加审计。权威为专用 PostgreSQL 16 `auth_governance` schema。模型/错误/安全边界见 [P1 契约](../docs/design/oa-auth-unification/CONTRACTS_P1.md)，精确依赖见 [技术选择](../docs/design/oa-auth-unification/TECH_SELECTION.md)。这是库和受控 CLI，不增加服务，不自动创建平台管理员，不发业务角色。

## 隔离集成验证

已有本机 `dev-infra-postgres16-1`、端口 45432 时，在仓库根执行：

```sh
python3 deploy/governance-test-db.py
GOVERNANCE_TEST_CONFIG="$PWD/.local/governance/database.properties" ./mvnw -B -Pgovernance-it verify
```

工具创建唯一 `auth_gov_p1_test_` 库和非超级用户 Owner；检查点/配置 0600、目录 0700，只操作本任务命名空间。重跑不重置角色密码、不接管其他 Owner、不删除夹具。仅用于本机测试；现有 `dev.sh` 不会因此迁移或启用治理。CI 在一次性 PostgreSQL 16 service 中执行同一 profile，缺配置失败，不能以 skipped 当集成 PASS。

普通 `./mvnw verify` 仍可无数据库运行单测；它不等于上述集成验收。P1-01 已验证真实迁移、约束、并发幂等、版本 CAS、旧 ID 保留和审计失败全事务回滚；P1-02 已实现固定 Casdoor Access Token 和 LoginIdentity 适配，P1-03 已提供默认关闭的 HTTP/S2S 接入。

## 固定发行方验证

`CasdoorAccessTokenVerifier` 仅接受实测 v4.11.0 的 RS256 Access Token；验证原始身份/时间、issuer、非空固定 audience、tokenType、实时 introspection，并在启用和每次请求检查发行方版本。凭据/ID Token/错误应用/过期一律拒绝，发行方或公钥不可用返回依赖错误，不缓存 active=true。`AuthenticatedIdentityReader` 只找显式绑定并读取当前成员，不自动开户或赋予业务角色。

隔离候选、复现命令、私密连接引用和完整升级门禁见 [Casdoor 兼容升级方案](../docs/implementation/oa-auth/phase-1/CASDOOR_COMPATIBILITY_UPGRADE.md)。`governance-identity-it` 必须同时显式设置 `GOVERNANCE_TEST_CONFIG` 和 `GOVERNANCE_IDENTITY_FIXTURE`；`governance-legacy-it` 显式设置 `GOVERNANCE_LEGACY_FIXTURE` 只读验证旧版拒绝。普通 PG profile 不隐式启动 IdP。

共享 v4.3.0 不能满足 Token 用途校验；隔离 v4.11.0 虽修复用途混用，但额外 redirect_uri 负例失败。尚未替换共享 IdP，不能将本模块验收或 CI Token 测试写成完整登录升级通过。`TokenAuthority` 的应用客户端和版本探针客户端分开；配置 `issuer/jwks.uri/audience/client.id/client.secret/version-probe.client.id/version-probe.client.secret` 仅进入后端私密配置，打印已脱敏。

## 受控初始化与读取

先编译并生成 Runtime 类路径：

```sh
./mvnw -B -pl auth-platform-governance package
./mvnw -B -pl auth-platform-governance dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
```

配置文件（真实值放 `.local/governance/database.properties`，0600）：

```properties
jdbc.url=jdbc:postgresql://127.0.0.1:45432/<专用治理数据库>
jdbc.username=<专用治理账号>
jdbc.password=<受控凭据>
jdbc.maximum-pool-size=8
```

初始化文件同样 0600，包含：`command.id`、`operator.ref`、`tenant.id`、`tenant.code`、`principal.id`、`issuer`、`subject`、`membership.id`、`valid.from`、可选 `valid.to`、`source.system`、`source.tenant.ref`、`source.subject.ref`。ID 为规范 UUID，时间 UTC Instant 且微秒精度；issuer/sub 与既有验证登录显式绑定。只允许受控 EMPLOYEE 初始化，旧事实冲突拒绝，不恢复停用或延长期限。

```sh
GOVERNANCE_CP="auth-platform-governance/target/classes:$(cat auth-platform-governance/target/runtime-classpath.txt)"
java -cp "$GOVERNANCE_CP" com.lrj.authz.governance.cli.GovernanceCli bootstrap .local/governance/database.properties .local/governance/bootstrap.properties
java -cp "$GOVERNANCE_CP" com.lrj.authz.governance.cli.GovernanceCli lookup .local/governance/database.properties .local/governance/bootstrap.properties
```

这是具有专用库写权限的离线运维入口；`operator.ref` 用于受控操作审计，不能将文件交给普通 Web 用户。初始化承担显式 migration owner；lookup 只 validate 既有迁移，未初始化即拒绝。Flyway 不 baseline 非空旧库，clean 禁用。失败不输出 SQL、Token、密码。没有停用回退或删除命令。

## 连接与保留

连接 ID `AUTH-GOVERNANCE-LOCAL-TEST`：dev_infra PostgreSQL 16（本机实测 16.15），127.0.0.1:45432；用户名/密码/数据库检查点引用忽略的 `.local/governance/database.json`，治理配置引用 `.local/governance/database.properties`。该凭据仅有专用库 Owner 权限，真实连接与写入已验证。没有部署到生产，也未共写认证/图/OA/商城库。

V1 仅新增表，admin/server 已按显式开关接入本模块。原业务主键和数据不变；后续只添加新迁移，不能修改已执行 V1。关闭治理功能后仍保留成员停用、审计和命令事实。审计归档期限/容量需正式治理策略确认，当前没有自动删除，不承诺生产增长治理已完成。

## P1-03 HTTP integration

Default-off admin/server routes, private configuration and isolated verification: [P1-03 runtime](../docs/implementation/oa-auth/phase-1/P1-03_RUNTIME.md). Identity context is not application admission or business authorization.

## P1-06 受控停用

[停用运行说明](../docs/implementation/oa-auth/phase-1/P1-06_RUNTIME.md)：独立成员/全局主体命令、版本 CAS、幂等与完整事务审计。V2/V3 仅扩展审计列和约束，V1 保持不变；身份读取始终检查当前停用事实。OA 目录投递尚待 P1-04，不把离线命令视为源端离职已全链路确认。
