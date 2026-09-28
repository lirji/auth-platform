# P3-02 auth API 检查点

P3-02仍IN_PROGRESS，本记录仅是可供商城固定依赖的auth协议提交，不表示商城或整个切片完成。

- 新增scope-plan/check-resource后端接口，独立scope开关/调用服务允许名单/资源Owner配置。保持双身份、主库A/C、同Grant路径和双水位；不将图Token返回调用方。
- ScopePlan关联校验、短期限、256KiB响应上限、版本摘要及资源版本回显进入既有CentralAccessClient，无新的框架或传输依赖。
- product字段绑定来自商城真实catalog_product，保留store与product条件AND，未支持SELF/部门/供应商继续拒绝。范围期限增加成员到期上限。
- server/SDK及其依赖`mvn test` exit0；新Controller边界3项，CentralAccessClient共9项（新增4项）通过；日志scope-api-unit-final.log。
- 真实PG/图ReliableAuthorizationIT共11项exit0，新增商品必须同时满足store_id与product_id；日志product-scope-graph.log。不是Mock SQL证明。
- Code Hygiene COMPLETE_WITH_LIMITATIONS；无既有Java formatter，异常边界和常量有人工复查建议。P3-02剩余：商城真实SQL/游标/导出/详情、跨进程Owner调用与数据库负例、Boot4兼容验证。
