# W00–W02 契约

状态：用户已授权实施范围中的通用资源与源目录契约；不包含尚未决定的组织成员映射。

## 资源

- wms_warehouse：Owner 为登记的 WMS 应用，Facts.resource_id 是真实仓库 ID，Facts.tenant_id 为经过映射的中央租户 UUID；Facts.store_id/supplier_id/owner_principal_id/department_id 均为 null，department_ancestors 为空；资源版本非负，后续 Owner 适配须从真实版本字段取得。
- wms_warehouse 规则版本 1，只允许 SPECIFIED_RESOURCES，values 非空、唯一、最多 100 项，标识沿用现有协议；不支持 TENANT_ALL、门店、部门或供应商范围。
- wms_enterprise：仅 TENANT_ALL；任何窄范围和 store_id 拒绝。企业 ID 与中央 tenant 的绑定不能通过这个类型自动创建。
- ScopePlan/Facts 的线上字段与版本不增加。未登记类型继续失败关闭。

## 源目录

Owner 仓库为 WMS。唯一操作来源为 wms-security/src/main/resources/wms-operation-scopes.tsv，生成器另持有有限路由资源分类与界面菜单映射。产出 docs/iam/catalog.json、docs/iam/operations.json，均无凭据、成员或授权数据。

能力固定 application=wms。既有 scope 的大小写词转为 snake_case，前缀 wms；同 scope 同时用于全企业与仓级路径时，全企业能力追加 .enterprise。能力风险由 HTTP 写动作标记 HIGH；这里只是新目录的保守发布分类，不给任何人赋权。

仓级路径使用 wms_warehouse；SKU、企业级履约、全企业对账入口及创建仓库使用 wms_enterprise。仓级调拨路径后续必须增加列表/详情的 Owner 范围适配。菜单是固定相对路由，选仓上下文由 WMS 构建，不能通过发布绝对 URL 跳到其他应用。

生成器拒绝重复路由、畸形 scope、未分类新路由、能力编码冲突；--check 必须核对产物完整内容，不只核对行数。后续新增公开操作需要同时扩充绑定和验收，不能自动赋予全企业范围。

## 后续契约门禁

W03 需补齐组织映射、成员来源、固定服务身份/issuer/audience、判权入口与安全传输、开关/回退及 API/菜单响应。W04–W06 必须消费该契约，不能对临时 JSON 开发 UI 或擅自开启旧 JWT 回退。
