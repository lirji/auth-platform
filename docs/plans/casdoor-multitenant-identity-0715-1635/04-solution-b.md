# 候选方案 B：单一全局 org + 单 app + 用户自定义 tenant claim

## 1. 架构

所有平台人类用户进入一个 Casdoor organization 和一个 SPA application。租户不再由 `owner` 表示，而由用户自定义 property/affiliation 产生的新 token claim（计划名 `tenantId`）表示。

```text
global org/app -> all users
user custom property -> token tenantId (待验证)
edge tenant-claim=tenantId -> internal JWT
ReBAC/resources/groups 全部改为 tenantId 前缀
```

## 2. 登录和 audience

- 前端不需要租户选择来决定 clientId；一个 `UserManager` 和一个 audience 即可登录所有用户。
- 登录页仍可收 tenant alias，用于 login_hint/品牌和回调后的 tenant 断言，但不能阻止用户在 IdP 输入另一个租户账号。
- edge 静态一个 audience 即可，不需要动态 registry；新租户免 edge 重启。
- callback/silent/logout 和 redirect URI 最简单，每环境只在一个 app 登记一次。

## 3. claim 与隔离改造

- `owner` 恒为平台 org，不能再映射 tenant；必须让固定 Casdoor 版本稳定把用户 property 送进 access token。仓库当前没有这一证据，属于首要待验证。
- `CasdoorSecurityProperties.tenantClaim` 改为 `tenantId`，并删除/改写 `CasdoorDecoderConfig` 当前 ONLY 强制 owner 的断言（`:38-42`）。
- 仅验证 claim 非空仍不够；edge 需要 tenant registry 验证 tenant enabled，且防自定义 property 被低权限用户自助修改。
- 所有 group/object 编码必须使用 tenantId，而不能再使用 owner：`CasdoorGroupIds.encode(owner,group)`、`CasdoorClient.scopedGroupId()`、`KnowledgeResourceIds` 调用方、fixture/backfill 都需审计。

## 4. 租户开通

1. 在平台 tenant registry 建 tenant；
2. 创建/导入用户并写不可自助修改的 tenant property；
3. 创建 tenant-scoped roles/permissions，或以名称前缀隔离全局角色；
4. 创建 groups并同步 SpiceDB；
5. token probe 验 tenant claim；
6. 无 app/audience/redirect 更新。

app 运维成本最低，但角色、group、用户名和 property 命名空间都集中在一个 org。需要定义同名用户名策略；Casdoor 是否允许全局 org 内重复 username显然不能依赖，通常需把登录名编码为 tenant+username（具体规则待验证）。

## 5. admin 与迁移

- admin 只操作一个 org，API 调用和分页简单；但所有查询/写必须显式 tenant filter，否则一次 bug 可横跨全部租户。
- auth-service `(tenant,username)` 迁移到全局唯一 Casdoor name 需要编码和展示名分离；现有 `name→username` 映射要改。
- 历史 ReBAC 资源前缀可保留原 tenant 字符串，但 `CasdoorGroupIds` 不能再从 owner取 tenant，影响面大。
- coarse role 必须 tenant-scoped，防全局 `admin` 角色授予所有 tenant 权限；当前 permission name 等于 11 个全局 scope，无法单靠 scope 表达 tenant，必须确保 edge tenant claim限制数据面。

## 6. 数百/上千租户扩展性

- app/audience/redirect 运维最好，只有一套；edge 配置稳定。
- 单 org 用户/role/group 集合巨大，Casdoor Admin API分页、UI、token claim 大小和爆炸半径更差。
- 一个 org/app 配错会影响全平台；无法按租户隔离登录策略、品牌、IdP provider或故障。

## 7. 风险评审

- **正确性**：依赖未验证 custom claim，且 owner 不再等于 tenant，违反当前最强安全不变量。
- **兼容性**：edge ONLY 断言、前端 user parsing、GroupSync、ReBAC、脚本和文档均需改。
- **安全**：tenant property 的可写权限、缺失/多值、账号迁租、全局管理员误操作是高风险；必须 fail-closed。
- **事务/迁移**：用户改 tenant property 与 SpiceDB prefix/relationships 无法原子迁移；账号跨租户移动尤其危险，应禁止或建 saga。
- **回滚**：一旦历史 subject/group/name 改写，回滚 owner 模型需要双 claim/双 tuple 窗口，成本高。

## 8. 已知弱点与结论

它解决 app/audience 爆炸最彻底，但代价是改写整个系统最稳定的 owner→tenant 契约，并把隔离从 Casdoor org 边界降为一条用户属性。除非固定版本实验证明 claim、不可修改策略和规模性能，并且业务明确需要单 org，本期不采用。
