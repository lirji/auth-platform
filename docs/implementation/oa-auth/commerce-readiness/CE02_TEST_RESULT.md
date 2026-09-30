# CE-02 验证与实施记录

## CE-02-D 权限契约

2026-09-29，基线auth11d3776、commercef7cb512。输入为用户明确批准的员工端边界及独立高风险权限、沿用现有审批。产物：[适配契约](../../../design/oa-auth-unification/CONTRACTS_COMMERCE_ADAPTER.md)、[逐入口绑定](../../../design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json)、离线verify-commerce-contract.py及回归测试。

- 真实commerce源码扫描PASS：218/218 HTTP入口、122能力、34同资源岗位快照；无发布或自动人员授权。
- Python 9项PASS：遗漏/新接口、重复/未知能力、客户入口误授、跨资源角色、伪门店范围、动态未知动作、超限及空注解/数组解析。
- py_compile、git diff --check PASS；hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：无仓库Python格式化器，沿用周边样式，未新增依赖。CI新增该组回归；CI内对已审查HTTP清单比对，本地另对真实commerce源码比对，未声称CI下载了另一个仓库。
- 源码核对发现并记录ScopeRules/SDK/server资源白名单、execution仅CATALOG、V46本地有效凭据及V48单CATALOG接管约束；按切片补齐，不靠给用户ADMIN绕过。

CE-02-D设计验证PASS。CE-02-A兼容协议和CE-03库存尚未实现；此结果不是完整新增模块或生产迁移验收。新资源Owner配置/真实映射、角色授予、切换均未执行。

## CE-02-A 有限资源协议

实现：新增纯Java ScopeResourceBindings，治理ScopeRules、SDK范围响应、server Owner配置及资源事实校验统一复用。保留store/product三种范围；merchant只允许全租户/指定商家资源，18个无可信门店归属的会员营销等类型只允许TENANT_ALL。新类型事实不接收门店/员工/部门/供应商字段。未登记Owner仍拒绝；没有修改任何运行配置、清单或已有Grant。ExecutionAuthorization仍只支持既有CATALOG，库存精确扩展属于CE-03-I。

验证：

- 全模块 `./mvnw -B test`：248项通过，0失败/错误/跳过。
- `ReliableAuthorizationIT`：13项真实PostgreSQL+SpiceDB通过（含新增会员Grant持久化/投影、两运行时判权、跨租户/伪门店拒绝、撤权前栅栏拒绝及投影后DENY）。使用新建自有PG，连接池每实例2；原P3图仅新增随机隔离租户/application测试事实。
- Boot4 SDK兼容测试通过；旧SDK对新类型的失败关闭来自旧代码显式白名单审查，未声称运行了旧二进制对新服务的端到端演练。旧store/product行为由原单元和13项真实图测试回归。
- Hygiene无阻断；沿用仓库源码样式，无独立Java格式化器/静态分析配置，报告IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。
- 私有日志：`.local/governance/commerce-contracts/{unit,integration,boot4,hygiene-adapter}.log`。复用P7资源helper仅创建PG：eb6f374dc77f容器已停止，数据保留；未运行容量/灾备或修改共享IdP。

CE-02-A验证PASS；与CE-02-D同一增量任务分支交付。库存与其他商城新模块仍未接管，真实映射和生产接受保持原阻塞，下一片CE-03-I。
