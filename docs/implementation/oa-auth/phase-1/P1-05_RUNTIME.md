# P1-05 运行与回退

沿用治理 PostgreSQL、admin/server 和固定版本 Casdoor；无新增服务或中间件。
仅隔离环境验证。HTTP 进程只 validate schema，V4 由既有 migration owner 初始化。
已执行的 V1–V3 未修改；V4 增加邀请、邀请审计明细与外部退出审计约束。

## 私密配置与调用

配置、命令均为普通非符号链接文件，权限 0600。数据库配置沿用 jdbc 属性。
邀请 CLI 配置额外固定 `invitation.operator-ref`、`invitation.tenant-id`、
`invitation.sponsor-membership-id`。命令不得覆盖这些范围。

```sh
java -Dloader.main=com.lrj.authz.governance.cli.InvitationCli \
  -cp auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  issue /private/invitation.properties /private/issue.properties /private/proof.properties
```

JAR 路径使用本次构建的实际版本，不从不可信路径加载代码。
issue 命令：`command.id`、`invitation.id`、`issuer`、`subject`、`member.kind`、
`expires.at`、`membership.valid.to`、`reason`。时间为微秒精度 ISO UTC 时间。
首次排他创建 proof 文件；重试必须保留它。终端不输出原文。
revoke 使用同一 CLI 与配置，省略 proof 参数；命令只有 command.id、invitation.id、expected.version、reason。

外部退出沿用 LifecycleCli，动作 `leave-external-member`，配置和命令格式与 suspend-member 一致。
只允许 ACTIVE PARTNER/GUEST → LEFT，不处理员工离职，不绕过 SUSPENDED。

## HTTP 开关与配置

admin 同时打开 `authz.governance.enabled=true`、`authz.governance.invitations.enabled=true`，
并以 `authz.governance.configuration` 指定私密配置。后者增加完整 `invitation.user.*` TokenAuthority：
issuer、jwks.uri、audience、client.id、client.secret、version-probe.client.id/secret。
一般治理入口仍使用根级 TokenAuthority 和精确登录绑定，邀请例外仅限指定 POST 路径。

POST `/api/governance/v1/invitations/accept`，Bearer 为受邀用户 Access Token，
JSON 仅 invitation_id/token。返回成员引用不等于应用或业务访问许可。
两个开关默认关闭。开启时缺专用认证配置会拒绝启动。

## 可重复验证

```sh
python3 deploy/governance-casdoor-fixture.py --base http://localhost:18090 --phase tokens \
  --directory .local/governance/p1-05-idp \
  --management-config .local/governance/casdoor-isolated/management-client.json
python3 deploy/governance-invitation-smoke.py
```

只使用独立 P1-05 IdP 组织/客户端，保留 P1-02 未绑定账号负例。
测试启动本工具持有的回环 18091/18092 进程，端口冲突则拒绝启动，退出只停止自己创建的进程。
私密配置、令牌、JAR 日志保留在忽略的 `.local/governance`，验证结果不含凭据。
CI 增加同一真实链路；无需业务数据库或 SpiceDB 写入。

关闭邀请开关即可停止新接受；回退应用不删除成员、邀请、审计或代际。
不能把代码回退当作撤销已经完成的接受，应通过受控生命周期命令处理业务效果。
Casdoor 错误 redirect_uri 缺陷仍单独 HOLD，不切换共享 IdP，不执行生产部署。
