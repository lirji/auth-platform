# P5-05 TEST_RESULT

状态：PASS。真实commerce商品Owner接入中央product.update，内部EMPLOYEE读取、受控编辑及撤权后拒绝已验证。IdP夹具账号名字external不代表成员类型；本片bootstrap真实EMPLOYEE，P506另经邀请建立PARTNER。

- 8项CentralScopeMySqlTest PASS：范围SQL/事务/恢复原6项，新增真实条件写入、版本冲突、同命令回放、同本地actor不同中央主体命令隔离、越范围/过期决策/资源换绑拒绝。
- 4项AuthorizationCoverageTest PASS，包括固定SPA GET深链/回调静态壳，匿名POST和业务API仍拒绝。两前端构建与auth上下文4Node测试PASS；新依赖仅复用oidc-client-ts3.5.0（Apache-2.0），npm audit零漏洞。
- http-756ed29af567完成54项真实双auth节点/Casdoor/graph/MySQL检查，含内部UI主链和原P3持久导出SIGKILL恢复/撤权/依赖故障回归。
- 最终http-ac027002b488使用独立JAR副本，真实只读→授予update→UI提交真实商品→撤权后读保留/写403；三阶段6项浏览器检查及HTTP夹具PASS。跨门店/租户资源ID拒绝，乐观版本过旧409，资料版本实际0→1；刷新URL恢复详情。1440表单/结果及390可读/可写/撤权截图逐张查看，无加载态冒充证据。
- Code Hygiene两仓通过，唯一既有FORMAT_TOOL_NOT_AVAILABLE限制；前端Prettier已执行。旧业务未改权威来源，无新迁移/中间件。SQL在Owner Mapper XML中以tenant/store/id/version/范围/5秒以内决策期限原子更新；网络判权不持有本地事务。命令审计和效果同事务。

初轮UI发生实际图可读尚未收敛，夹具修为等待真实actions允许；第三次过程中重打包覆盖正在运行JAR导致类读取失败，工具已改独立副本，最终重跑通过。不放宽授权、不造ALLOW。授权平台不可用保留503语义。

本片OIDC使用真实PKCE令牌注入进行业务浏览器验收；完整交互登录/跨应用SSO仍属P507。受控写仅元资料，不扩大到退款/订单等。P506商品export独立能力未在本片宣称完成。撤权不回滚已提交商品修订，在途判权到本地提交存在最多5秒有界窗口。

SKILL_HANDOFF: slice=P5-05, status=COMPLETED, gate=PASS_WITH_ASSUMPTIONS, next=P5-06。
