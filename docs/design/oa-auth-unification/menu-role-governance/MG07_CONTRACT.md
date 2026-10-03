# MG07 业务本人菜单提示契约

状态：APPROVED计划内D-NAV的具体化。复用现有Commerce后端双身份和本地显式绑定，不增加授权模型、缓存或执行引用。

## 身份与入口

Auth Server新增 `POST /internal/governance/v1/access/navigation`，缺省关闭，需原governance/access/scope开关及独立 `authz.governance.navigation.enabled=true`。受控0600文件 `navigation.callers` 为已有scope调用方子集，且已在固定CallerService注册；不隐式升级旧context/check调用方。

Authorization为服务凭据，X-User-Access-Token为Commerce专属既有audience的用户Token。服务身份固定application/environment；请求严格只有tenant_id、expected_membership_generation（可空正整数）、request_id（UUID）。禁止principal／application／environment／operator／URL／execution_id。用户必须当前有效HUMAN、当前租户成员且代际吻合；不创建身份或赋权。管理Token不能替代专属用户证据。

仅支持已经启用严格范围／双栅栏的分区；没有就绪栅栏或依赖失败整次503，禁止回退旧P2或管理/me/access。每次读取当前已发布目录／展示，按当前完整Grant范围路径存在性判权。只检查菜单引用的能力，最多200；整次7秒预算，结果最多100菜单、200能力、128KiB。能力检查前后取新的主库栅栏，核对主体、成员、目录／策略epoch、租户和Manifest版本及当前时限，查询期间变化使整次失败。图调用不放在数据库事务内。

## 响应

schema_version=1、request_id、当前context（不含Token）、manifest_version、content_hash、presentation_hash、observed_at、state（AVAILABLE／NO_ACCESS）、menus、capability_hints。

menus逐项code、parent、route（只允许本应用相对路径或null）、label、position；无直接能力的祖先仅组织子菜单，route必须null。按position再code稳定排序；旧目录无label／position保留null，由消费方固定声明兼容。能力提示只包含本次允许且由菜单引用的能力，不包含人员、角色、Grant、委派、范围或图Token。没有允许能力为NO_ACCESS／空集合，不把依赖拒绝当无权限。

SDK使用现有双header、禁重定向、有界响应和完整超时；严格核对schema、request、tenant/app/env/HUMAN及代际、UUID、正版本、双摘要、UTC时点、状态、菜单唯一／祖先／无环／相对路径／label与position、能力命名空间及上限；损坏响应503，无允许缓存。

## Commerce适配

新增 `GET /v1/operations/navigation`，独立中央安全链、缺省关闭，需 `commerce.iam.store-read.enabled`、`commerce.iam.navigation.enabled`。仅GET，可选expected_membership_generation查询参数；拒绝未知query参数、重复／缺失Authorization或X-Tenant-Id，服务凭据不发浏览器。使用已有CentralAccessClient固定应用／环境配置，每次Auth查询后用CentralStoreBindingMapper精确tenant/principal/member/generation匹配当前本地OPERATOR，映射不存在403。返回本次Nav响应，沿用Commerce公开DTO的camelCase字段（schemaVersion／requestId／manifestVersion／contentHash／presentationHash／observedAt／capabilityHints及context字段），Auth内部和SDK通信仍为snake_case。不自动开户、不赋予业务authority、无业务读写。

业务页面和API继续现有独立判权。MG08才消费导航提示过滤侧栏、移动菜单、搜索／默认页和深链；本片不改前端授权逻辑。

## 验收与兼容

真实Commerce audience身份、当前本地映射和严格PG／授权图得到本人提示；多能力／单能力／无授权、祖先无路由、展示顺序和版本可核对。主体／租户伪造、错audience、未经登记调用方、旧代际、停用、撤权、查询中版本变化、图故障拒绝；查询前后目录／角色／Grant／执行引用逐行不变。旧协议与旧管理展示接口不变，新开关关闭保持旧行为；启用新前端前先部署契约生产方，失败不允许全导航。
