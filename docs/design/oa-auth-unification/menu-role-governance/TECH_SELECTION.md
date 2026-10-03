# MG09 技术选型

Brownfield，只选择阶段 C 新增的机器身份及部署隔离方式；已有 Java／Spring Boot BOM、MyBatis XML、Flyway、PG 和 Casdoor 版本维持现状，不升级依赖。

| 决策 | 候选 | 选择／理由 | 否决／代价 | 引入阶段 |
|---|---|---|---|---|
| 环境目录 | 独立权限实例／数据库；同实例扩展目录环境键 | 用户已选择前者。现有目录 application_id 键及发布事务不用全路径迁移，各目标独立客户端和实例 UUID（可复用可信 IdP，必须不同 audience／密钥）。 | 同实例需改全部目录读写／判权／历史及兼容窗口，当前无该需求；独立实例需分别运维、发布和备份。 | MG09设计、MG10隔离验收；不生产部署 |
| 机器认证 | Casdoor client_credentials；永久共享发布 Bearer；新增 OAuth／工作负载身份平台 | 沿用 Casdoor v4.11.0，专用 client_credentials／RS256／固定受众／access-token／实时 introspection，五分钟 Token。 | 永久 Bearer 不具备当前发行方事实和短期 Token；新增平台无已证需求、运维和迁移成本更高。 | MG10默认关闭实现与实测 |
| 发布授权 | 复用 HUMAN Owner；独立 SERVICE 委派 | 独立 SERVICE，PG 绑定固定应用／实例／环境和 HUMAN Owner。 | 伪装 HUMAN 破坏发布与人员边界；仅 OAuth scope 无法证明应用发布资格。需要追加有期限委派及 actor 审计。 | MG10 |
| 风险门禁 | 任何候选自动发布；非语义变更自动发布；新增人工审批引擎 | 非语义变更自动发布，语义变更继续现有 HUMAN MG05。 | CI 不能自行选择授权扩大处理决定；新增审批引擎会重复既有 Owner 用例。第一版机器不执行人工风险票据。 | MG10／MG11 |
| 一致性／存储 | PG 本地事务；远程双写／MQ | 现有 PG、应用锁、不可变票据和原命令回执。 | 无发布跨库副作用，不新增 MQ／Outbox／缓存／调度。 | MG10 |

## 固定版本证据与限制

[Casdoor v4.11.0 client_credentials 实现](https://github.com/casdoor/casdoor/blob/34eec9034f34ca25449e0e3b4683ce98f3faf088/object/token_oauth.go)把应用 ID 作为 subject，使用 application 类型，并单独记录该 grant；不会产生 HUMAN 用户。该函数不返回 refresh token，应用允许的 grant 类型必须限定为 client_credentials。

[同版本 JWT 生成](https://github.com/casdoor/casdoor/blob/34eec9034f34ca25449e0e3b4683ce98f3faf088/object/token_jwt.go)生成 access-token 用途、客户端 audience／azp 和 iat／exp；ExpireInHours 使用浮点时长，允许约五分钟配置。服务端仍检查实际 exp−iat 不超过300秒，不以配置声明代替验证。

[同版本 introspection](https://github.com/casdoor/casdoor/blob/34eec9034f34ca25449e0e3b4683ce98f3faf088/controllers/token.go)校验客户端并查询当前 Token 记录，返回客户端、受众和时效事实。[Token 有效性实现](https://github.com/casdoor/casdoor/blob/34eec9034f34ca25449e0e3b4683ce98f3faf088/object/token.go)明确 client_credentials 不依赖终端人员状态，因此额外 SERVICE／委派主库校验必须保留。

通用客户端凭据仅用于机密客户端，认证与发布授权分别处理，依据 [RFC6749 §4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4)。OAuth scope 本轮不作为角色或目录授权。

当前安装版来源：MG08真实 PKCE／HTTP验收沿用现有 v4.11.0；本轮固定版本源码已读取并保存 SHA256。当前新机器 Token 探测止于版本接口超时，Docker inspect/logs亦超时。源码核对 PASS 不等于安装版机器验收 PASS；MG10真实 IdP／PG／跨目标验证是启用硬门禁。

## Complexity Budget

无新增主要基础设施、库、运行进程或跨库事务。新增成本限定于：PG 实例目标／委派／审计，Admin 独立默认关闭的机器边界，CI 固定目标客户端及凭据轮换说明。不同环境同应用标识合法，但客户端／秘密／目标 UUID／数据库连接不可共享；不能把测试实例备份直接恢复成同身份的生产目标。

固定源码提交：34eec9034f34ca25449e0e3b4683ce98f3faf088；四份原始文件SHA256记录于本机私密mg09-source-contract/evidence.json，tag与该提交的四文件逐字节核对PASS。源码选择不宣称容器与此Git提交逐字节相同。
