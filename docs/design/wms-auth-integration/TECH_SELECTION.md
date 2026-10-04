# 技术未知与选择

## 当前选择

复用 Java 21、Auth protocol/governance/sdk 的规则版本 1、可靠授权和 HTTP 客户端、WMS Boot 4.1.1/MyBatis/OIDC。当前 W01/W02 无新增依赖或基础设施，不需要另选权限产品。

| 未知项 | 证据与决定 | 状态 |
|---|---|---|
| 仓范围如何表达 | ScopeResourceBindings + ScopeRules 已支持精确资源路径；新增 wms_warehouse，只接受 SPECIFIED_RESOURCES | W01 可实施 |
| 门店字段是否复用 | store_id 的语义属于电商；WMS 仓库必须使用自己的 resource_id | 已否决复用门店字段 |
| 全企业共享资源 | 新增 wms_enterprise，只接受 TENANT_ALL，不能获得仓库范围 | W01 可实施 |
| WMS scope 编码大小写与粒度 | 94 条路由/43 scope；显式生成小写能力和路由-资源绑定，企业与仓级路径分开 | W02 可实施 |
| 组织/企业映射 | ENT-DEMO 与 Auth tenant UUID 不能等同；用户确认独立 local-wms → ENT-DEMO | 已冻结 |
| SDK 与 Boot4/Jackson | SDK 独立使用 Jackson2；固定源码依赖与 Boot4 实际构建在 W04 验证，未执行不计 PASS | 待验证 |
| Docker 到中央判权入口 | 现有 5273 运行管理面，中央 SDK 的 HTTP 只允许回环；服务入口、TLS/可信配置需在 W03 明确 | 待冻结 |
| 写命令与撤权窗口 | 不能让远程调用长期持有本地库存锁；每个写片明确准入/提交/异步效果边界 | W05/W06 待冻结 |

## 备选与成本

新增 SPECIFIED_WAREHOUSES/warehouse_id 会改变线上格式并要求所有消费者版本协同，当前精确仓库资源可以表达相同约束，因此不采用。给每个仓另建 SpiceDB 或复制权限平台增加运行成本，也不采用。

选型/架构/影响 Gate 对已冻结 W00–W02 为 PASS；W03–W07 已按用户答复补齐 CONTRACTS；实际实现/验收尚未完成，不能以局部 Gate 替代全任务完成。

W03–W07 决定：专属 Casdoor 4.11 PKCE 客户端；原治理管理主体显式 WMS 委派；现有 Auth server 内部入口；固定 SDK 源版本；宿主回环验收与 Docker HTTPS 信任。无新增中间件。跨容器明文 HTTP 或放宽 SDK 限制已否决。
