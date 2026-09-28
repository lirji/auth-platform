# P1 首批实施准备

当前入口为 P1-00，完成后依次实现 P1-01、02、03 的内部可信成员路径。外部、目录正式接管和生产存量导入不会因为这些基础模型完成而自动获得准入。

## 1. 首批纵向任务

| ID | 可观察结果 | 责任路径 | 前置／验收 | 回退 |
|---|---|---|---|---|
| P1-00 | 执行者可按冻结契约实现“验证登录→有效成员→本人上下文” | auth `docs/design/oa-auth-unification/`；目标 `protocol` 和候选 `governance` | 明确字段、旧 ID 映射、Token 类型/受众、S2S 用户证据、DB/migration Owner、错误和幂等；无真实正式映射时只用隔离夹具 | 版本化设计，不改变运行链路 |
| P1-01 | 隔离库可持久化并读取一个明确绑定的 Principal/Tenant/Membership | 候选 `auth-platform-governance/src/main/{java,resources}`，auth 根 pom，admin 受保护内部入口 | P1-00；PG 唯一约束、事务、注释、乐观更新，旧 OA／商城主键不改；同 issuer/sub 冲突隔离 | 新功能关闭；扩展表保留；不删除旧库数据 |
| P1-02 | 实际可信 Access Token 可映射主体，错误凭据被拒绝 | auth 管理安全配置、公开身份适配、protocol | P1-01；真实错误 iss/aud/签名/期限/ID Token 负例；不自动采用 isAdmin | 新认证适配关闭，旧管理入口保留 |
| P1-03 | 当前成员上下文只来自可信身份与有效成员关系 | governance 成员解析；server 安全边界及协议 | P1-02；伪造 principal/tenant/app 不生效，退出成员和旧 generation 拒绝；S2S 与用户证据分别验证 | 拒绝新中央路径；不扩大旧角色 |
| P1-04 | 选定目录源的重复／乱序事实只产生一次正确成员变更 | OA `oa-org/api`／发布适配；auth directory/inbox/checkpoint | 目录 Owner 确认；source/event/version/payload conflict；不完整快照不误删 | 暂停该源消费、保留检查点；不恢复已接受的离职状态 |
| P1-05 | 外部认证人可安全接受一次邀请，未获准入时不能进内部应用 | auth admin 邀请 API、治理模型、现有 auth-console 对应页 | P1-03；单次 CAS、有效期、绑定、并发、防 sponsor 越界；不是 OA 员工默认授权 | 停邀请入口；已创建成员遵循当前生命周期 |
| P1-06 | 旧 JWT 不能绕过当前主体／成员停用 | governance 状态命令、server 当前状态检查、审计 | P1-03；状态版本、重加入代际、审计失败回滚；接受源事件与完成状态明确 | 保留停用事实；不能用旧版本全量回退 |
| P1-07 | 有真实认证、PG 行为和内外身份隔离的阶段交接 | P1 测试与阶段记录 | P1-04/05/06；Mock 仅覆盖边界，真组件单独验收；P2 输入完整 | 保留限制与证据，未通过项不标完成 |

P1-01 内部测试入口仅用于当前可信操作者或受控初始化，不能匿名建人／自动引导管理员。模块边界、公开 DTO 和迁移命名需先冻结，表名采用用户设计等价映射，不机械复制 OA Entity。

## 2. P1-00 冻结清单

| 项目 | 要明确的内容 |
|---|---|
| 主体与登录 | HUMAN/SERVICE；Principal 全局状态；`(issuer,sub)` 唯一；不因同邮箱合并；pairwise 绑定只能经受控证据 |
| 企业与成员 | 稳定企业 ID；本次只用隔离企业；Membership 当前记录唯一、类型、状态、generation、UTC 有效期、sponsor；重新加入递增 |
| 旧身份 | OA tenant+identity+sub、商城 tenant+actor/member 的显式映射，唯一约束与冲突隔离；未知正式映射不写入 |
| Token | discovery/JWKS/issuer、管理 API 与业务 API 的受众、Access Token 类型和证明、PKCE/state/nonce/redirect；刷新和登出边界 |
| 服务信任 | 绑定 app/env/可调用 API 的服务凭据，独立用户证据；上下文由服务器构造，不信任 body principal |
| 持久化 | 复用 PG16 独立业务库；Mapper 方言与装配；Flyway 管理与序列 Owner；唯一/检查约束、版本 CAS、表列注释 |
| 目录 | 实际来源待 Q-DIR；事件 aggregate/version/hash、Inbox 唯一键、同版本冲突和检查点；未完整全量不删 |
| 兼容与错误 | 新版本契约或增量门面，保留旧九项操作；401/403/409/503 及各仓 Result 适配；未知枚举默认拒绝 |

上述为契约需求清单，不是已发布 DTO 或可调用 API。只需冻结当前薄路径，不要求把 P4／P7 的所有合同提前写完。

## 3. 验证与环境

| 验证 | 真实范围 | 状态 |
|---|---|---|
| auth 旧安全契约基线 | `mvn -B test`，124 项 | PASS（P0；不是 P1 实现） |
| OA 身份／权限资产基线 | `mvn -B -pl oa-iam,oa-security -am test`，149 项 | PASS（P0） |
| commerce 编译 | `mvn -B -pl commerce-app -am test-compile -DskipTests` | PASS（P0；未运行权限回归） |
| 新模型真实 PG | 新迁移、唯一性、CAS、事务、重放／并发 | NOT_RUN，P1 必需 |
| 真实 Token 身份正反例 | Discovery 可读；Token 类型、受众、绑定仍须实测 | NOT_RUN，P1 必需 |
| Boot4 SDK 消费 | 指定源码制品、自动装配、HTTP、安全异常 | NOT_RUN，P2-05a 必需 |
| commerce 实际权限回归 | StoreAccessTest、AuthorizationCoverageTest、PlatformRuntimeAuthorizationTest | NOT_RUN；定向命令还需避开上游空测试门禁并配置安全隔离库 |

现有 commerce 测试要求 `COMMERCE_TEST_DB_URL` 含精确 `/commerce_test_20260923?`。不能为运行它擅自使用其他任务正在使用的库、改 URL 加虚假标记或解除安全校验。下一次业务接入验证应先准备互不干扰的真实目标，或在获准的消费者切片中让测试库隔离规则支持本任务唯一命名。

本轮无共享数据库／图写入，无 compose 重启或清理。P1 真组件验证前需明确测试库和认证试点命名空间；这些技术准备独立于正式员工数据迁移。

## 4. 下一条具体操作

在 `feat/oa-auth-unification-plan` 的最新交接上，为产品实施创建独立任务分支。读取本文件、基线和源方案 P1／共享契约后，先完成 P1-00：跟踪当前 Casdoor 签发的实际凭据类型和各入口受众，确认治理库迁移／Mapper 装配，输出当前薄路径正式契约与负例。然后由 Claude `backend-implementation` 一次执行一条依赖满足的 P1 切片。

每片实现后运行适用验证、同步进度，并按用户持续 Git 授权正常合并交付。目录正式接入仅等待 Q-DIR；外部资源范围仅等待 Q-EXT。未满足条件的片标 BLOCKED，不把未运行测试记作通过。
