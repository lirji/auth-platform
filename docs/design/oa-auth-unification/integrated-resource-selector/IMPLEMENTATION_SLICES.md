# IR-01 实施切片

## IR-01：实际接入目录与有限权限选择

状态 DONE；验证 TEST_RESULT.md=PASS；owner backend-implementation + frontend-implementation。依赖既有P5/GUX、P2 catalog/access和P3 scope；用户明确并行隔离实施授权，无需修改原D2工作树。

范围：PortalDtos、PortalManagement、PortalMapper/XML、新内部目录basis；新增治理GET；治理API、目录选择组件、AccessPage、PoliciesPage、共享ScopeFields及本片测试。禁止改旧写授权语义、协议绑定/迁移、Commerce产品及原目录target。

验收：真实Owner/窄委派目录与grantable差异；外分区/撤权/代际/版本并发拒绝且读取零写；旧management兼容；ScopeFields两个消费者按真实类型过滤范围；菜单浏览不授予、复制显式纠正、未知命令原样重放；真实PG/HTTP/PKCE/SQL、当前制品及1440/390/320视觉。Owner真实menu发布缺口不能由测试菜单冒充。
