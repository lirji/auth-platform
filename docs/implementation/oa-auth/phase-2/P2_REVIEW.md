# P2 交付前复核

对P2变更单独进行只读复核，再回到实施阶段修复具体发现；无子Agent委派。范围：auth基线187cbde与commerce基线04567b2之后的本轮代码、迁移、SDK、脚本和页面。

已修复并验证：

- SDK响应头后body卡住不受原HttpRequest超时完全约束：真实失败用例复现2秒停滞；完整异步结果限时等待并取消，新增专项通过。

- 商城与auth Maven安装仓库不同导致旧SNAPSHOT被使用：统一安装使用商城Maven，并固定源码完整SHA、核对SDK范围diff。
- Actor存在兼容构造器，MyBatis自动构造映射会失败：显式constructor resultMap；真实MySQL门店读取13项通过。
- 失败耗尽的投影缺少受控恢复：增加当前委派管理、幂等、版本、审计的retry-projection，不直接激活Grant。
- 已耗尽旧意图被撤销新版本取代后会永久阻塞：drain允许废弃旧版本意图，真实PG/图专项7项通过。
- 单执行者锁清理异常被忽略：记录suppressed异常，释放失败中止物理连接并报告失败；不声称远端栅栏已完成。
- 独立图复用检查只核对名称不够：比较去注释、规范化后的全部definition内容，允许服务端排序差异，拒绝额外schema。
- 控制台生产反代缺少治理路径：新增同源/api/governance反代并通过nginx配置检查。

核心不变量：固定RoleVersion；来源独立；同Grant的能力与范围；当前SQL成员/状态/期限和图资格共同满足；服务app/env不接受用户替换；管理委派不等于业务权限；故障与DENY不同；新商城路径无旧授权OR回退；列表SQL租户过滤早于分页；前端可见性不是鉴权边界。

质量Gate：两仓code-hygiene均IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。仓库未配置Java统一formatter/静态分析器，沿用局部风格、git diff --check及编译测试；未为门禁添加工具依赖。auth-core测试依赖、既有failsafe集成profile与商城SDK均是本P2批准范围的必要依赖。脚本超时/协议边界常量的非阻塞提示经人工复核。

范围限制：单投影执行者；无跨请求ALLOW缓存；仅直接成员/TENANT_ALL；P3远端CAS和细范围、多实例撤权未认证。共享Casdoor升级原HOLD保留；浏览器测试使用真实隔离IdP签发Token恢复已有会话，不证明正式SSO切换或修复上游回调漏洞。没有生产部署、存量权限接管或OA文件修改。
