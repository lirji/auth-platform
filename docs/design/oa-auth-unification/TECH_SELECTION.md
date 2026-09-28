# 增量技术选择与兼容验证

状态：目标技术边界已按用户方案映射；新增依赖的精确版本和实际配置在 P1-00／所属切片冻结，本轮没有修改 pom、部署或应用配置。

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

版本记录以 pom、实际容器版本和镜像摘要为准。Casdoor 当前只有镜像摘要和 Discovery 证据，语义版本 UNKNOWN；不能把 `latest` 或历史文档里的 v3.115.0 写成实际安装版。
