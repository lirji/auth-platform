# MG09：隔离目录与受限机器发布架构

范围：已批准 PLAN 的阶段 C（MG09–MG11），Brownfield；不重划既有领域，不部署生产。设计状态 DESIGN_READY，安装版机器凭据和数据库并发验收由 MG10 执行；目前本机 IdP／Docker 查询超时，运行启用保持 HOLD。

## 事实、约束、决定和未核验项

| ID | 类型 | 依据与结论 |
|---|---|---|
| F01 | Fact | ApplicationCatalog 和 V6/V20/V21/V22 已有单库事务、应用锁、固定预览、双摘要基础栅栏、不可变历史、原命令回执；发布不写角色或 Grant。 |
| F02 | Fact | IdentityGovernance.principalForLogin、CatalogMapper.lockOwner 明确只接受 ACTIVE HUMAN；现有 Owner 接口不得接纳 SERVICE。 |
| F03 | Fact | 现有 CasdoorAccessTokenVerifier 固定 v4.11.0、RS256、issuer/audience、access-token 用途，每请求实时 introspection；无正向身份缓存。 |
| F04 | Fact | Casdoor 固定版本源码提供 client_credentials，其 sub 为应用 ID、type 为 application；JWT 生成与已有验证契约一致。源码依据及范围见 TECH_SELECTION。 |
| C01 | Constraint | 用户选择独立测试／生产权限实例和数据库；同实例多环境目录扩展不实施。Git 发布不授予生产部署权限。 |
| D01 | Decision | 延续已有 Admin 进程／governance 模块和 PG 本地事务；使用独立机器 HTTP 前缀及明确 SERVICE 主体，复用目录唯一写用例。 |
| D02 | Decision | 客户端仅 client_credentials，固定 issuer/sub/azp/单一 audience/application/instance/environment。OAuth scope 不代替发布委派。 |
| D03 | Decision | 第一版自动发布仅允许不改变能力及可执行入口绑定的展示／层级变更。首次目录、能力／资源类型变化、入口新增删除、route 或 any_of 变化，报告 REQUIRES_OWNER_REVIEW，由 HUMAN MG05 流程正式发布。 |
| D04 | Decision | 机器 Token 最大 300 秒，CI 每轮重新获取，不用 refresh token；发布委派必须有期限，最大 30 天，禁用后不原位恢复，轮换使用新客户端／新委派。 |
| U01 | Unverified | 本轮安装版机器 Token 尚未获取：所有探测在版本 GET 就超时；不能据此断言 client_credentials 不支持，也不能声称真 IdP 集成 PASS。 |
| R01 | Risk | 原位替换 client secret 不等于撤销已发行 Token；轮换必须同时禁用原委派，每个请求读主库状态，旧 Token 即刻不能取得新发布资格。 |

## 边界与数据权威

- Commerce 声明和固定源码制品为候选来源，Auth 的不可变发布快照为当前目录权威，现有角色／Grant 保持各自授权权威。
- IdP 只证明固定机器身份及当前 Token 事实；CatalogPublisher 用 PG 当前 SERVICE、HUMAN Owner、委派、期限和实例目标决定是否可操作。
- 单个实例的目录仍以 application_id 管理；不同环境拥有各自数据库、发布实例 UUID、受控发行方配置（可信IdP可复用，但各环境client／audience不同）／专用客户端及密钥、运行声明和调用方配置。请求 environment 只用于精确核对，不选择数据库、发行方或 URL。
- 受控初始化一次写入实例目标；应用启动只验证，不登记身份、不自动发布、不把测试目录复制到生产。旧目录程序忽略新增目标表仍可读取历史。
- 新机器身份不创建人员 Membership，不获得个人菜单／管理诊断／角色／Grant／能力恢复权限。只有当前 HUMAN Owner 可对受控配置中指定的发布客户端建立或禁用委派。

## 用例与依赖方向

Admin CatalogPublisherController → CatalogPublisher 应用服务 → ApplicationCatalog 固定发布内核／Publisher Mapper XML → 同一治理数据库。

机器 Bearer Filter → 既有 Casdoor 验证核心 → VerifiedMachine（已核验身份、iat、exp；不携带原始 Token）；该类型与 VerifiedLogin 分开。Owner 入口继续既有治理 Bearer 链。

不增加通用 OAuth 服务、第二套鉴权框架、缓存、MQ、Outbox、调度器或进程。固定发布内核抽取只消除这条用例的重复，原 HUMAN 校验、影响确认和错误语义保留。

## 事务、不变量和并发

1. 所有状态操作先定位受控客户端的固定 application，再锁 application 行。验证实例配置与 PG 目标一致，锁当前 SERVICE／HUMAN Owner 和委派，核对准确身份、版本、期限、GUARDED 模式及 Token 到期时间。
2. 预览继续写 MG05 十分钟不可变票据，另存不可变机器票据关系，绑定委派／实际 SERVICE。机器只可发布自己的票据；不能消费 HUMAN 票据或另一个委派的票据。
3. 发布只输入原 command UUID 和 preview UUID；同键异体冲突。原成功重试在通过当前身份／委派后读取原回执，先于票据期限／当前目录检查；已禁用或 Token 到期不提供认证例外。
4. 新发布在当前基础／双摘要／票据期限通过后重算自动发布资格，提交前再次以数据库当前时间核验委派和 Token；不使用事务开始时的旧时间。
5. 快照、显示、指针、原发布回执、机器 actor 元数据和审计同事务。实际 published_by／operator_ref 为 SERVICE UUID；另记录当前 HUMAN Owner、委派和实例环境，绝不把机器记成 Owner。
6. Owner 禁用采用相同应用锁顺序，发布与撤销串行。禁用事务提交后，旧机器 Token 的下一次请求失败。外部 IdP 在 HTTP 验证后发生变化不能宣称与 PG 提交原子；发布授权仍由主库同步门禁和五分钟凭据限制。
7. 任何目录发布均不写业务 Grant、角色版本、能力状态、execution reference。图投影未就绪要明确报告，不把目录提交当作业务 ALLOW。

## 兼容、恢复和演进

- 追加迁移 V23（实施时重新核对编号），不改已执行 V21/V22、不回填假来源。新增目标／委派／票据关系／actor／事件表均有表和字段注释、FK、唯一键及不可变保护。
- HUMAN 旧请求／旧快照 JSON／旧摘要不变；机器前缀默认关闭。既有 Owner 历史中的 published_by 字段仍是实际主体；机器详情通过独立 actor 回执解释 SERVICE 和委派，不改写历史为 HUMAN。
- 新程序启用机器前必须完成目标初始化、固定客户端配置、Owner 委派及 MG10 实测；不默认启用任何原应用、不代替 Owner 开启 GUARDED。
- 程序回退保留追加表和历史；停止机器入口／禁用委派可停止后续操作，已提交版本使用更高修正版本，不降低指针、不删除历史、不恢复 Grant。
- 全局容量／SLA 不做未经测量承诺；沿用请求体、目录数量、固定超时和有界认证并发，MG10 以真实数据库验证竞争和事务回滚。

## 实施 handoff

MG10 实现 MG09_CONTRACT 的认证、委派、唯一写内核和数据库约束；MG11 再实现默认 dry-run 的固定目标客户端和 CI 报告。当前安装版认证实测未验证，机器运行 gate HOLD；这不阻止完成默认关闭的实现和本机协议测试，但 MG10 不得标 DONE 或开启发布，直至必要真实验收通过。

## 后续事实与 MG12 只读模型（2026-10-03）

上文未验证状态为 MG09 当时记录。MG10／MG11安装版与隔离验证已通过，精确证据见对应 TEST_RESULT；默认关闭及独立环境的决定继续有效。

MG12采用 [MG12_CONTRACT](MG12_CONTRACT.md)：原管理链 → RoleMigrationPreview → 同主库 RoleMigrationMapper XML。角色、Grant和范围继续由原模块拥有；无新中间件、后台进程、授权写入口或跨库读取。

两个批量查询各最多50条，完整分区限定，关联固定范围及当前成员代际；READ_COMMITTED只读事务、5秒上限。当前管理权、目录和两份源快照不一致则409；最后PG时钟再次校验Grant／成员期限。ACTIVE记录不被当作真实图ALLOW，projection_status始终UNKNOWN。

角色引用使用UUID游标每页20条（多取1条判断下一页），V25仅添加匹配分区／角色／UUID的索引。公开报告不返回图Token或内部人员版本，不写预览审计、命令、范围、角色或Grant。MG13须独立建立持久任务与来源谱系，在写事务重新校验并取得真实撤权确认。
