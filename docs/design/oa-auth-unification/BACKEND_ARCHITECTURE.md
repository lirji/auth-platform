# 目标架构与现有资产映射

## 1. 事实、目标和假设

| ID | 类型 | 内容 |
|---|---|---|
| F01 | 已核对事实 | auth 是 Boot 3.3.5／Java 21；protocol、core、sdk、server、admin 五模块；现有业务授权图主要由 SpiceDB 承载 |
| F02 | 已核对事实 | OA 已有 PostgreSQL IAM、目录、申请、角色、范围和失效实现；不是从零建设 RBAC |
| F03 | 已核对事实 | commerce 使用 Boot 4.1.1／Jackson 3／MySQL；有固定角色、本人隔离和 STORE/MERCHANT CATALOG 授权 |
| F04 | 已核对事实 | auth 水位为实例内存；图写客户端没有前置条件参数；既有图有多个写入口 |
| F05 | 已核对事实 | OA Human 当前由员工投影，不能直接支持没有员工记录的外部 Human；OA Identity 带租户，不等于目标全局 Principal |
| D01 | 用户方案目标 | auth 业务库成为新治理事实权威；OA 保留目录和审批职责，两后端继续独立部署 |
| D02 | 用户方案目标 | app/env/tenant 作用域、固定 RoleVersion、同 Grant 动作与 scope、无跨请求 ALLOW 缓存、版本栅栏 |
| A01 | 隔离试点假设 | 内部只读试点使用现有 commerce 门店接口；正式企业／身份映射待提供真实来源，不按邮箱合并 |
| A02 | 待决假设 | OA 可作为目录来源适配候选；已有 HR 权威则复用其来源，不能在本轮擅自改成 OA 主写 |
| R01 | 风险 | 将 OA 私有 Entity/Mapper 直接放入 auth 或 SDK 会破坏依赖边界；采用公开适配和分批迁移 |
| R02 | 风险 | 现有 TEMPORARY+APPROVAL 被视为 JIT，普通限时角色语义需要独立验证，当前只有源码疑点，未证明实际业务故障 |

源码与实际测试边界在 [P0 基线](../../implementation/oa-auth/phase-0/baseline.md)，未运行链路不因这张表变成已验证能力。

## 2. 模块与部署边界

```text
protocol（纯 Java，旧 AuthzEngine + 新公开身份/判权契约）
    ↑                 ↑
core（SpiceDB HTTP）   sdk（远程检查/错误/上下文适配）
    ↑                 ↑
admin          业务应用 OA / commerce
    ↖             ↗
      governance（候选新增库，领域/API/Mapper/迁移）
             ↑
       server（中央检查/ScopePlan）
```

图仅表达允许依赖：sdk 只依赖公开 protocol；admin/server 分别依赖治理模块和所需 core；governance 不依赖 admin/server 或 OA 私有实现。治理模块若需要引擎能力，通过 protocol 端口接入。新增 Maven 库不会形成新的服务或数据库写权威。

| 逻辑能力 | 目标承载 | 复用资产 |
|---|---|---|
| identity-adapter、enterprise-directory | governance；admin 管理入口、server 上下文解析 | Casdoor OIDC、OA UserContextFilter／IdentityProjectionListener／oa-org 公开事件 |
| application-catalog、access-management | governance；admin API | OA PermissionCatalog、RoleAdminService、GrantService 的规则及回归样例，重建 app/env 与不可变版本边界 |
| authorization-runtime | server＋core＋protocol＋sdk | 现有九项 AuthzEngine 和严格响应校验；OA 按权限保存范围的规则资产 |
| access-projection | admin 内受控执行器＋governance 持久化 | core HTTP 适配，新增 CAS／Outbox／receipt；不使用实例内水位证明跨实例一致性 |
| oa-integration | auth admin／governance，OA oa-flow 公共适配 | OA 已有工作流网关、待办及通知；旧本地申请规则只作为复用候选 |
| resource-scope adapter | OA 各业务 Mapper；commerce store/catalog 等 | OA org_path 规则和商城 StoreAccessMapper 的租户／分页过滤分别保留方言 |

## 3. 数据权威与写入

| 数据 | 新体系唯一权威 | 写入口／事务 | 旧数据处理 |
|---|---|---|---|
| 凭据、登录会话、issuer 身份 | Casdoor／实际 IdP | 认证提供方受控 API | 不复制密码或重建已有账户 |
| Principal、LoginIdentity | auth 治理业务库 | 可信身份适配；`(issuer,sub)` 唯一；绑定审计 | OA identity、旧 sub 通过映射关联；冲突隔离 |
| Tenant、Membership、generation | auth 治理业务库 | 当前租户/主体唯一成员记录，合法状态 CAS | 不把 OA bigint 与 commerce 字符串租户直接相等 |
| Employment、部门、岗位 | OA 正式目录来源（P1已确认，本轮再次确认） | 来源系统写；auth 通过公开契约消费事实 | 接收版本、检查点和目录 fence；禁止跨库写 OA |
| 受信组 | 选定来源＋auth 成员消费模型 | 同源版本；成员代际 | P3 栅栏前不启用组授权扩展 |
| Application、Environment、TenantApplication | auth 治理业务库 | 平台/企业应用管理入口 | Casdoor Application 只映射认证客户端，不自动等同业务应用 |
| Capability、菜单清单 | auth 发布记录；内容由应用 Owner 负责 | manifest 预览、版本、摘要、发布审计 | 旧能力 code 逐项映射，不自动扩权 |
| RoleVersion、ScopeRule、AccessGrant | auth 治理业务库 | 管理上限校验后，同事务记录、版本、审计、Outbox | OA/commerce 的旧治理记录在各迁移单元切换前仍是旧链路权威 |
| AccessRequest | auth 治理业务库 | 快照与状态 CAS；同事务 Inbox/Grant/审计 | 旧 OA 请求单独映射，不移植可变申请语义 |
| ApprovalInstance／Task／Evidence | OA/现有工作流 | OA 公共审批边界与幂等 business key | auth 仅保存关联和已验证结果 |
| PolicyPartition／DirectoryFence／OperationReceipt | auth 治理业务库 | 单事务版本推进、持久化领取与回执 | graph receipt 与主库 READY 需要 CAS |
| Grant eligible 关系 | SpiceDB 派生投影 | 只允许受控中央投影写入 | 不作为 Role/Scope/时间窗业务权威，可由业务库重建 |
| 业务订单／门店／商家／合作方归属 | 各业务应用 | 原有业务事务及 Mapper | 不把所有业务实体迁入 auth |

旧体系与新体系在迁移期间按 app/env/tenant/队列分区；每个单元只有一个有效写权威和判权路由。新旧独立覆盖不同单元，不进行未经协议约束的同步双写。

## 4. 信任边界与公开契约

管理用户访问 auth 管理 API 时，验证管理 API 受众。业务服务调用中央检查时，使用绑定 app/env/API 的服务凭据，并提交服务端取得的用户证据，由平台按该应用允许的 issuer/aud 验证。两个身份分别确认；不能为兼容业务 Token 放宽 auth 管理受众。

平台从可信登录身份、服务注册和当前有效 Membership 构建 AccessContext。请求中的 principal、actor、tenant、app 只是需验证的信息；不能直接覆盖最终上下文。普通用户不能指定任意人代查或获取权限诊断。

在 P1-00／所属阶段冻结实际 DTO、版本、错误、幂等作用域、批次上限和兼容格式。源方案的 `/internal/authorization/*`、`/api/access-grants` 等路径是候选；现有 `/v1/*` 和 OA Result 包装继续保留。此文不创建第二份线上 JSON 契约。

判权返回 ALLOW／DENY／CONDITIONAL／ERROR；只有明确 ALLOW 才可继续。依赖故障显示不可用，不能进入宽权限回退。decision_id 只作关联，不能作为可复用授权票据。前端展示读模型不能替代后端实时检查。

## 5. 事务、一致性与恢复

1. 本地授权写入将业务事实、审计和 Outbox 原子提交；权威审计失败时管理写入失败。图写不放在该 SQL 事务内。
2. P2 单执行者推进图并保存写水位和状态；P3 增加远端标记 CAS、领取租约、desired/applied epoch 与恢复协议。旧 worker 不得读取新标记后套用旧 payload。
3. 图成功但 SQL receipt 失败时保持分区非 READY；远端超时结果未知时读取标记定位结果，不能盲重放旧意图。大批操作全部完成前不 READY。
4. 检查 A 读取权威新快照及 READY 分区；B 进行完整且有新鲜度要求的图检查；C 使用新的主库快照重查全部版本、成员代际、状态、期限。变化或未知时有界重试，仍不稳定则不 ALLOW。
5. 多个 ZedToken 不按字符串排序；采用经过证明的因果水位协议或保守逐水位复查。必要变更的水位持久化在权威库。
6. 图 API 的原子关系写入与前置条件由官方接口定义支持，但本客户端的新增适配、目标 Schema 和旧执行器故障必须在真实安装版验证；本轮只核对源码和版本，没有运行该证明。[官方 API](https://raw.githubusercontent.com/authzed/api/main/authzed/api/v1/permission_service.proto)

## 6. 执行约束

新增表及每列都写中文注释；SQL 放 Mapper；关键写校验影响行数；状态用稳定 code 与明确迁移规则；类、公共方法和安全关键分支写中文原因注释。不得改已执行的 Flyway 文件、直接写其他仓库业务表或用 Mock 证明真实事务。

早期采用主库校验且没有跨请求 ALLOW 缓存。以后若引入缓存，另行证明 key、epoch、生命周期和故障行为；不能靠广播或 TTL 宣称严格撤权。性能和资源预算在 P7 按实测定义。
