# W05 实际入出库/PDA验收

当前Validation PASS；实现与原Owner回归记录在WMS docs/iam/W05_TEST_RESULT.md。Auth新增动作/写撤权验收工具与运行制品冻结、最终MySQL TCP就绪检查，CI检查语法。local-wms/ENT-DEMO映射和现有成员管理不变。

专属runtime-w05-b8e3b083814c：23身份/图/SQL、34入出库/履约、4PDA/入库浏览器、5菜单/深链、10PDA SQL/旧Token写撤权、15故障/恢复/读撤权PASS；无身份注入/API替换，8张当前图片已查看。安全21项、新真实Spring/RSA边界3项、Owner HTTP8项、default147门禁、受影响UI10项/类型/build通过。原失败保留：工具误读orderId/replayed；重打包覆盖运行JAR；MySQL临时socket就绪；菜单可访问名含图标；并发浏览器曾返回503。没有放宽业务断言或虚构容量保障。

旧机器身份限定内部发行方和显式subject白名单，Owner仍校验scope/企业/仓/状态；真实旧8000机器发行仍待W07，不把RSA协议夹具等同该证明。收货重放后SQL2，PDA实际提交1后SQL3，撤权后同旧Token的新请求和重试403，原事实和读权保留。有效出库动作不能绕过原TCC授权409。

Code Hygiene原自动FAIL的2项为浏览器工具输出/切片ID误报，按用户不机械常量化规则逐条审查；无formatter限制保留，共享政策未修改。本机Docker和生产尚未切换，整个W00–W07目标仍在执行。
