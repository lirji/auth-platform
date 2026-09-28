# 增量技术选择与兼容验证

状态：P1-00 已冻结首片关系持久化装配；后续组件仍由所属切片核验，不升级既有 Spring Boot。

| 问题 | 选择／候选 | 取舍与首次阶段 |
|---|---|---|
| 是否新写整个平台 | 复用 auth+OA 两后端、现有控制台及业务应用 | 新独立平台会重复登录、IAM、审批和迁移；本轮不选物理合并，也不创建第三套业务权威 |
| 框架版本 | auth/OA Boot 3.3.5；commerce Boot 4.1.1；Java 21 | 不为统一名称整体升级。SDK Boot 4 兼容必须在 P2-05a 验证，不能仅凭 Jackson 包名认定兼容或不兼容 |
| 授权业务记录 | 复用 PostgreSQL 16 实例，隔离 auth 治理业务数据库／Schema | auth 当前主要是图态和可选审计，新增关系持久化是业务权威所必需；不共写 OA/Casdoor/SpiceDB 表。P1 |
| 持久化实现 | P1-00 核对并选择现有 Mapper 生态的最小装配 | 候选 MyBatis XML 或复用 OA MyBatis-Plus 3.5.5；JdbcTemplate 沿用既有审计但不在业务服务拼 SQL。不在准备阶段擅自引入新版本 |
| 迁移工具 | 优先沿用 OA/commerce 已有 Flyway 思路并核对 auth Boot BOM 的版本 | auth 治理库需独立增量迁移序列；SpiceDB datastore migrate 是另一条迁移链，不能混用。P1 |
| 关系判权 | 保留 SpiceDB HTTP、现有 AuthzEngine 端口 | 实测运行版 v1.56.2；不引 gRPC/protobuf 破坏既有约束。客户端和图 CAS 在 P3 增量扩展 |
| 可靠副作用 | 同库 Outbox/Inbox 和现有进程内有界执行器 | 图投影与 OA 回调确有跨边界失败窗口；P2 引 Outbox、P4 引 Inbox。先不为此新增 broker；需要消息输送时再复用已确认总线 |
| 缓存 | 首版检查不启跨请求 ALLOW 缓存 | OA 的旧缓存留在旧路由；不会把实例 epoch、L1/L2 TTL 当成新体系撤权证明。性能证据支持时再选优化 |
| 门户与会话 | auth-console、project-portal；SPA PKCE 或同进程 BFF 待真实 Token 核验 | 保留现有 React/Vite；不强制微前端、不增加默认 BFF 进程。P1 认证契约决定，P5 完成体验 |
| 本地组件 | 优先 dev_infra 和现有 auth 组件 | 真实写入测试须使用独立测试库／graph 命名与已验证隔离，不执行共享 compose down、FLUSHDB 或 schema reset |

Complexity Budget：本轮新增复杂度集中于一份治理关系模型、可靠图投影、有限 ScopePlan、审批幂等和迁移路由，分别服务已存在的不变量。Nacos、Apollo、网关、ES、新 MQ、Kubernetes、通用策略脚本和第二套工作流引擎没有对应首版必要问题，不安排新增。

## 实施前技术验证

- **V-SDK**：用 commerce 锁定版本消费来自指定 auth commit 的 protocol/sdk 制品，实际编译、启动、自动装配、真实 HTTP 判权和拒绝异常；检查 Jackson 2/3 共存、AOP 和显式调用。仅 jar 编译不算接入完成。
- **V-PERSIST**：以隔离 PostgreSQL 16 验证迁移、唯一约束、条件更新、并发邀请与事件幂等；H2 通过不等于 PostgreSQL 行为通过。
- **V-GRAPH**：当前 core 无写前置条件。按官方字段扩展 HTTP 适配，在 v1.56.2 独立测试目标验证 MUST_MATCH/MUST_NOT_MATCH、原子 marker 迁移、超时未知结果和 token 记录；不能只测 Mock JSON。[官方 API](https://raw.githubusercontent.com/authzed/api/main/authzed/api/v1/permission_service.proto)
- **V-APPROVAL**：先验证 OA 普通限时角色与 JIT 的区别，再验证真实 OA 流程启动／查询／回调；本地审批单测不能替代 auth-OA 跨进程闭环。

版本记录以 pom、实际容器版本和镜像摘要为准。P0 的 Casdoor 语义版本曾为 UNKNOWN；P1-02 通过管理 API 实测共享实例 v4.3.0，隔离候选 v4.11.0。不能把 `latest` 或历史文档里的 v3.115.0 写成实际安装版。

## P1-00 精确装配

- 治理模块使用 MyBatis 3.5.19 + mybatis-spring 3.0.4（Spring 6 适配），手动专用 SqlSessionFactory/SqlSessionTemplate，不引入第二套 ORM 或 Boot 4 starter。[MyBatis 官方兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/), [mybatis-spring 3.0.4](https://github.com/mybatis/spring/releases/tag/mybatis-spring-3.0.4)
- Flyway core + database-postgresql 10.10.0、HikariCP 5.1.0、PostgreSQL JDBC 42.7.4，版本由当前 Boot 3.3.5 BOM 管理；Spring JDBC/事务沿用 BOM。现有模块无需升级。依赖许可证沿用 Apache-2.0/BSD 生态；独立漏洞扫描未执行，不将 BOM 复用声称为漏洞审计通过。
- 首片是库+受控 CLI；专用池最大 8、连接等待 3 秒、SQL statement/lock timeout、事务超时 5 秒（可受控配置，不承诺性能 SLA）。migration 固定 auth_governance schema，validateOnMigrate=true、baselineOnMigrate=false、cleanDisabled=true，显式 migrate owner，禁止自动接管非空旧库。
- 真实本地测试复用 dev-infra-postgres16-1（实测 16.15），创建唯一 auth_gov_p1_test_ 前缀库及非超级用户角色；私密配置只存在忽略路径。CI 复用现有 verify job 并提供隔离 PostgreSQL 16 service。
- 审计/命令记录初版保留，不在请求线程清理；正式归档期限需 Q-GOV/合规确认。未经确认不启动自动删除；上线前须完成增长容量和归档治理。

## P1-02 认证技术装配

- 复用 Boot 3.3.5 BOM 的 Spring Security OAuth2 JOSE 6.3.4、Jackson 2.17.2、Spring Web 6.1.14；不升级框架。Nimbus JWKS 缓存只存公钥；身份/用途/状态实时校验。旧 OAuth Token 兼容性不放宽新契约。
- 固定隔离候选 `casbin/casdoor@sha256:138b5e46d49ad678ea2d53487fa74f8aae6e3ee3a670fc1b62ebe981c2a9d964`，实际版本 v4.11.0；不依赖不存在的 Docker `v4.11.0` tag，不重新拉取/覆盖共享 latest 标签。专用 PostgreSQL 库、回环 18090、1 CPU/768 MiB，复用 dev_infra。用户仅授权隔离验证与升级准备。
- 发行方精确版本探针和 introspection 使用分开的后端凭据；默认关闭。隔离 built-in 探针凭据权限较高，正式最小权限凭据仍是上线条件；不扩展组织客户端全局权限来使测试通过。
- 已验证 Token 用途修复，额外 redirect_uri 负例 FAIL；共享替换 Gate HOLD，未实施升级。完整范围、代码流与未验证旧库升级见兼容方案；不能将 Token 验证通过写成全部登录能力通过。[官方 v4.11.0](https://github.com/casdoor/casdoor/releases/tag/v4.11.0)、[用途修复提交](https://github.com/casdoor/casdoor/commit/f8eb7273a8ec16bf6caa2d09c8e8068d578288b0)
