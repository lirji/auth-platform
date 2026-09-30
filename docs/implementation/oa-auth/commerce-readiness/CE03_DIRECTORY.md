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

D0实现与本地验证DONE；auth2557de1已合并推送main，CI36666736638 SUCCESS。没有新schema、业务表、真实Grant/Owner配置或生产部署。D1结果见下；D2尚未实现。

## 下一片

D1先落实EmployeeAccess集合许可/身份审计及V51兼容扩展，由Merchant/Store Owner负责SQL过滤与创建准入；D2绑定真实列表/创建SSO页面。不能给中央Actor ADMIN，不能向领域传Token，不能拿CATALOG或store.read替代store.directory.read，不能伪造待创建对象Facts。真实OA映射和生产验收输入HOLD保持。

## D1 商城Owner本地DONE

commerce分支feat/central-commerce-directory。四能力精确HTTP接管、EmployeeAccess.ScopePermit/CentralEmployeeCheck.scope、CENTRAL/STOPPED防旧ADMIN回退已实现；merchant/store list由本域Mapper参数化路径在LIMIT前过滤并前后复核；create全租户许可、Commands前置路由锁、稳定身份幂等、真实目标类型审计同事务。内部requireActive与客户browse保持既有契约。V51在专用MySQL成功应用，保留V49/V50历史、旧库存写入与所有路由状态触发器。

本地完整401项（396PASS/5可选skip）通过，directory-verify.log；新CentralDirectoryMySqlTest3项实际MySQL+HTTP但SDK为协议桩。hygiene首轮两处Service沿用tab文件新增空格缩进阻断，按原风格修正后最终无阻断；仅保留无formatter和事务人工审查提示。真实中央/IdP/商城联调rehearsal-16693ea0d846共83项PASS（含库存/CATALOG回归）；directory-owner-rehearsal.log及该目录result.json保留。四独立能力就绪、指定商家/门店过滤先于LIMIT、部分范围创建拒绝、全租户创建、真实父商家检查、身份审计、相同键仅执行一次、撤权后旧回执拒绝和中央故障503均通过。没有浏览器验收，D2仍待实施。

兼容边界：V51允许旧库存二进制继续写历史形状，但旧CE03-U二进制不检查DIRECTORY族。因此所有承载该族的应用实例升级后才允许CENTRAL；回退需使用已认识DIRECTORY路由的版本并置STOPPED，不能仅回滚到旧UI版恢复旧ADMIN。没有真实租户切换。
