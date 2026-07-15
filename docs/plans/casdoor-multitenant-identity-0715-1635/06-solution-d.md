# 候选方案 D：平台 org + 每租户一个 Casdoor group

## 1. 架构

所有用户和单一 SPA app 位于平台 organization；每个租户建一个专用 group。edge 从 token `groups` 中解析唯一 tenant group并得到 tenantId，`owner` 只表示平台 org。

```text
platform org/app
  user groups = [tenant/acme, functional groups...]
  -> edge classify exactly one tenant group
  -> tenantId=group mapping
  -> internal JWT / ReBAC tenant prefix
```

与方案 B 的区别：B 依赖一个标量自定义 property；D 以 group membership 为租户归属，同时复用 GroupSync 管理 ReBAC成员。

## 2. 登录与 claim

- 单 app/clientId/redirect，前端可不按tenant构造 manager；tenant选择只是登录名提示。
- 仓库已证明 admin resource server能读取 token `groups` collection（`SecurityConfig.jwtAuthenticationConverter():73-88`），但没有证明所有平台用户token必含完整groups、大小和更新时效。
- edge需定义tenant group命名空间，并要求恰好一个启用tenant group：0个或>1个一律401；普通功能group不得误解析。

## 3. 租户开通和管理

1. registry创建tenant与tenant-group映射；
2. 在平台org建tenant group；
3. 建用户并加入唯一tenant group；
4. 建/赋coarse roles与permissions；
5. GroupSync把group成员写为`group:<tenant>_<group>`；
6. token probe验证groups与permissions。

用户迁租是“从旧tenant group移除 + 加新tenant group + 迁数据/tuple”的跨系统saga，默认应禁止在线迁租。

## 4. 对现有 ReBAC/edge 的冲击

- `CasdoorGroupIds` 当前以 organization作为tenant前缀；平台org模式下会产生`platform_<group>`，必须改为registry解析的tenantId，不能直接复用owner。
- `CasdoorClient` 当前按一个org读取用户，反而适配单org，但`scopedGroupId()`必须区分tenant group和功能group。
- `KnowledgeResourceIds`仍可接解析后的tenantId，资源协议本身不变；历史主体仍要crosswalk。
- edge ONLY 的tenant-claim=owner启动断言必须改，前端user parsing也从owner改groups。

## 5. admin 与角色

- tenant membership和ReBAC group membership使用同一原语，概念上统一；GroupSync作用更大。
- 但Casdoor groups同时承担“安全租户边界”和“业务协作组”，误删/多加一个group可能跨租户；必须命名空间、唯一约束和审批。
- 全局角色名也需tenant-scoped或安全证明不会携带tenant数据权限。scope只表示能力，真正数据隔离仍完全依赖解析出的tenant group。

## 6. 数百/上千租户扩展性

- app/audience成本最低；group数量线性，单org对象集合、分页和token claim大小增长。
- 若用户属于大量功能group，access token可能超过当前实测约8.8KB并触及edge 32KB上限；需容量测试和group claim裁剪能力（待验证）。
- 单org故障/误配影响全部租户，无法按租户配置独立IdP/策略/品牌。

## 7. 风险评审

- **正确性**：必须可靠区分tenant group并保证唯一，groups多值比标量property更易出现歧义。
- **并发**：两个管理员并发给不同tenant group时可能短暂形成双tenant；必须条件写/operation lock，Casdoor是否支持版本条件待验证。
- **撤权**：旧token保留旧groups直到TTL，用户迁租尤其危险；需禁用账号并等待TTL。
- **兼容/回滚**：owner契约、GroupSync编码、edge/front claim和既有tuple均改，回滚成本接近B。
- **安全**：平台org管理员/同步bug的爆炸半径最大；tenant边界不再由org天然隔离。

## 8. 结论

它复用groups并显著降低app运维，但把协作组提升为安全租户边界，且与当前owner和`CasdoorGroupIds`实现冲突。可用于本来就以workspace group为租户的新系统，不适合当前已按org/tenant和`<tenant>_`前缀运行的平台。
