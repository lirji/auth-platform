# 候选方案 C：单 app 多 org 路由（organizationName/子域）

## 1. 架构假设

保留一租户一 organization，但试图让所有 org 共用一个平台 application。登录时由子域、tenant alias 或 `organizationName` authorize 参数选择 org；token `owner` 仍是 tenant，edge audience 永远是单一 clientId。

```text
tenant.example.com / tenant selector
  -> one global app + organizationName=<tenant> (待验证)
  -> token owner=<tenant>, aud=<global client>
  -> existing owner->tenant pipeline
```

这是与 A 真正不同的地方：A 的 app 隔离和 audience 都按租户；C 只保留 org 隔离，app/audience 全局共享。

## 2. 可行性状态

- 仓库 `add-application` payload 把 application 明确绑定到一个 `organization`（`casdoor-tenant-provision.sh:64-66`）。
- 前端依赖 `oidc-client-ts ^3.5.0`，其本地类型支持 `extraQueryParams`，但仓库没有任何 `organizationName` 使用。
- 当前 Casdoor 镜像为 `latest` 且本地未运行，无法证明单 application 可以认证其它 organization 用户、参数名称/大小写或安全语义。

因此必须先做“固定版本 contract spike”。若验证失败，方案 C 直接淘汰，不能用 UI 拼接参数绕过。

## 3. 登录流程

1. 根据 host 或登录页 tenant alias 确定 organization；
2. 用固定 clientId 的 manager 发 authorize，并通过已验证的参数传 organization；
3. callback 后断言 token owner等于选择 org；
4. silent renew/signout 使用相同 organization 参数/会话上下文；
5. edge 验单 aud + owner 位于启用 tenant registry。

子域方案要求每租户 DNS/TLS/CORS/CSP，但可以用 wildcard；登录页选择方案只需单域。两者都仍需 registry 校验 enabled owner，不能接受 Casdoor 中任意 org。

## 4. 租户开通

- 建 org/user/role/permission，不建 app；
- 把 org加入 registry，token probe验证全局 app能登录该 org；
- 无 edge audience变更、无 app redirect重复登记；可免 edge重启。

## 5. 影响范围

- 前端：tenant选择、pending/active上下文仍要做，但 manager配置只有一个 clientId；需要 extraQueryParams/subdomain。
- edge：静态单audience，新增 owner tenant registry；owner→tenant和ReBAC不变。
- deploy/admin：org生命周期保留，app reconcile大幅简化。
- Casdoor：所有租户共享一个 app的 grant、redirect、provider和安全策略，app故障爆炸半径全平台。

## 6. 数百/上千租户扩展性

- 对象数量和redirect维护优于A；org数量仍线性。
- 子域需wildcard DNS/cert与回调策略；若 callback origin随tenant子域变化，redirect URI可能再次线性增长。
- 全局app的登录吞吐、rate limit、secret/配置变更成为单点。

## 7. 风险评审

- **首要风险是产品能力不成立**：当前仓库没有证据，评分必须计入。
- **租户混淆**：authorize选择org与token owner不一致时必须在前端和edge拒绝。
- **SSO cookie/静默续期**：同Casdoor域多org会话可能自动选错账户；必须实测 prompt、login_hint、silent renew。
- **回滚**：若从A合并app，需要保留旧tenant apps和双registry；从C回A需为每org补app，数据本身可保留。
- **安全隔离**：org仍隔离用户，但app级provider/redirect/grant无法按租户隔离。

## 8. 结论

若固定版本实测支持，它是A的有吸引力优化：保留owner语义且消除多audience。但在当前证据下不能作为交付主线。建议仅做时间盒spike；成功后也作为二期app整合，而不是阻塞A。
