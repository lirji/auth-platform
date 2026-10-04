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


## W03–W07 冻结契约（2026-10-04）

用户确认独立中央组织 local-wms → WMS 企业 ENT-DEMO；中央 tenant UUID 由私密受控引导文件固定。application=wms、environment=local。服务端配置绑定 tenant/enterprise/issuer/audience，浏览器请求、JWT owner 或 X-Tenant-Id 不得重新选择企业。来源租户引用明确记录 ENT-DEMO。

### 身份与管理

- 复用兼容的 Casdoor 4.11（localhost:18090），新增专属组织 local-wms、公开 PKCE 客户端 wms-central；固定 audience=wms-central。旧 localhost:8000/wms-platform 保留。
- 本地业务演示成员为新组织的明确白名单身份，不批量导入旧 WMS 或电商成员；未授权成员没有任何业务 Grant。旧 Token 的 scope/warehouses 不参与中央决策。
- 已有 Auth 管理主体作为显式受控 WMS Owner/管理者，仅新增 local-wms 成员与 wms/local 委派；管理身份继续使用原治理管理客户端，不新增 isAdmin/首次登录自动权限。
- 登录身份与成员、目录和角色分别由现有 CLI/管理用例写入；配置 0600、目录 0700，固定命令幂等且冲突拒绝，不恢复停用或撤销状态。

### 判权与消费

- 复用 auth-platform-server 的现有内部 scope-plan/navigation/执行引用端点，独立开启治理配置；共用既有 auth_governance 权威库及 P3 可靠授权图，不新增数据库、消息系统或权限中心。
- WMS 使用 Auth SDK，来源固定至已验收 Auth commit 7712d2606805afb3b9a7de7a6d88fe94a28104fb；构建和 CI 显式安装同版 SDK，不能消费开发者机器的未核对 SNAPSHOT。
- 每次 HTTP 业务动作按 method/path 的 Owner 绑定申请能力范围；仓级只能 SPECIFIED_RESOURCES，SQL 中企业和仓库范围来自已验证中央上下文。独立写动作不得使用读取范围；已验证范围仅属于当前请求，不能进入 session、业务消息或跨请求 ALLOW 缓存。
- 路由中仓、查询参数仓及业务 Owner 读到的单据仓都必须落在当前动作的中央仓集合。调拨读取允许源/目标任一可见，发货/损耗检查源仓，收货许可与收货检查目标仓；源/目标规则沿用已有 Owner 代码。
- 全企业能力只允许对应企业资源；涉及明确仓库副作用时另外要求同动作的仓级能力，不能把企业能力当全仓许可。
- 业务层继续调用 WmsJwtAuthorities 的边界；中央模式携带后端产生的专用 Jwt 子类型并按当前动作解释范围，未取得中央上下文不进入 Controller。旧模式在开关关闭时保持；启用后拒绝和故障不回落旧 JWT。
- 权限准入发生在用例事务前；已持久化接受的命令由原幂等/状态/消息协议完成，撤权拒绝后续新命令、重试和人工操作，不声称撤销已经提交的业务效果。异步线程不得复用请求凭据。

### 本人权限和界面

- 新增 GET /api/wms/v1/me/access，可选 warehouseId，只读服务端固定映射。响应含 mode、enterpriseId、中央本人菜单、当前仓可用的原语义 scopes（由中央能力反映射）、warehouseIds 和观察信息；Cache-Control:no-store。菜单/按钮是提示，后端每次重新校验。
- 未登录 401，无成员/动作/仓范围 403；中央依赖、身份发行方或投影未就绪 503（AUTHORIZATION_UNAVAILABLE）；未知公开入口拒绝。保持原 JSON 错误的 code/message/retryable/requestId。
- 桌面/PDA 使用本人接口展示目录与能力；中央模式忽略 Token 的权限/仓声明。失败清空旧权限提示并提供重试；深链显示无权限或不可达，不自动打开未授权页面。
- 本人接口只允许真实目录的固定相对路由，选仓由 WMS 壳层构造；不对浏览器暴露服务凭据、管理 Token 或中央数据库配置。

### 运行与恢复

- 中央开关默认关闭；启用缺配置/错误映射时启动失败。地址只允许 SDK 原有 HTTPS 或回环 HTTP，禁止取消 TLS 验证。调用并发、连接和总响应期限有界，无自动授权重试。
- 宿主隔离验收可使用 localhost HTTP；Docker 跨网络中央入口使用 HTTPS 和明确本机信任证书。私钥、服务凭据、配置不入镜像/Git；不通过宽放 SDK 的 HTTP 主机规则接入。
- 本机更新复用原库、卷、端口与旧镜像，原 Auth 管理分区保持；新分区配置和图独立投影。回退检查新库和旧版本兼容，不清空授权或业务数据。生产部署不在授权范围。

此契约足以执行 W03–W07；容量/延迟不宣称达标，以实测记录。目录版本由 Owner 发布，不动态拼造能力或跨范围合并许可。
