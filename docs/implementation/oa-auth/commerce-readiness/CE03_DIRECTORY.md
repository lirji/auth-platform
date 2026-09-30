# CE03-D 商家/门店目录接管

## D0 执行范围协议：本地验证PASS

基线auth db96617/commerce8cf2a58，当前auth分支feat/commerce-directory-execution。正式技术边界见CONTRACTS_COMMERCE_DIRECTORY.md，原用户批准业务边界不变。

新增四个精确能力/类型的60秒执行引用：merchant.read/create→merchant、store.directory.read/create→store；旧CATALOG与库存不改期限。创建签发/范围复核只保留完整TENANT_ALL路径，指定对象或门店范围不能授予创建。新内部execution-scope只使用服务凭据与已签发引用，校验原caller/app/env/tenant/身份代际和到期，当前路径与原Grant完整交集、目录epoch不变；撤权后新Grant不能复活引用。响应复用有界Plan和SDK全部响应绑定/路径校验。HTTP要求显式Owner类型登记，不改运行配置。

验证：

- `mvn test` 250项PASS，日志.local/governance/commerce-contracts/directory-unit-verified.log；覆盖SDK真实HTTP协议桩的服务凭据/错误与恶意响应，Controller MockHttpServletRequest的Caller/Owner边界。不是实际IdP+新scope端点全链路验收，该项留D1真实商城适配联调。
- 专用PG 2e88fa51c318 + 当前P3 SpiceDB运行ExecutionAuthorizationIT共3项PASS。新增一项遍历四个目录能力，验证指定商家/门店读、部分范围不能创建、跨租户/资源类型/调用环境、撤权、重授不复活及到期；旧CATALOG与库存引用整项回归。directory-integration.log。专用PG已由finally停止，数据保留，不占共享PG连接。
- 当前SDK install及Boot4独立兼容测试PASS，directory-sdk-install.log/directory-boot4.log；旧issue/check JSON保持，原SDK不会调用新增方法。
- hygiene最终PASS_WITH_LIMITATIONS（Java无规范formatter、未配置静态分析），directory-hygiene-final.log。git diff --check PASS。

失败历史：新增SDK403测试沿用了上一轮超大响应，客户端正确先因有界响应失败返回不可用而非预期拒绝。已重置正常错误响应再测，未放宽有界校验；directory-unit-final.log失败和directory-unit-verified.log成功均保留。最初无新增用例的编译/旧单测PASS不替代最终验证。

D0实现与本地验证DONE；Git/CI交付另记。没有新schema、业务表、真实Grant/Owner配置或生产部署。D1/D2尚未实现。

## 下一片

D1先落实EmployeeAccess集合许可/身份审计及V51兼容扩展，由Merchant/Store Owner负责SQL过滤与创建准入；D2绑定真实列表/创建SSO页面。不能给中央Actor ADMIN，不能向领域传Token，不能拿CATALOG或store.read替代store.directory.read，不能伪造待创建对象Facts。真实OA映射和生产验收输入HOLD保持。
