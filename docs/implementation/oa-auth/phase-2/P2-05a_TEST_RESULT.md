# P2-05a Boot4 SDK兼容验证

PASS。SDK源码基线eaa6efb（当前旧SDK保持未改）执行 `./mvnw -q -pl auth-platform-sdk -am install`，再运行 `./mvnw -q -f compatibility/boot4-sdk/pom.xml test`，均退出0。

Boot4.1.1 + Java21实际启动Tomcat11随机回环端口，SDK自动装配RemoteAuthzEngine/CheckAccessAspect；Jackson2与Jackson3类同时加载；HTTP显式/代理调用ALLOW返回200、DENY返回403、缺allowed字段返回503；非HTTP代理调用同样拒绝，6次真实HTTP协议交互通过。SDK旧单测回归通过。

此片使用本地HTTP协议夹具，只证明SDK/宿主版本、自动装配和协议失败行为，不冒充真实业务授权验收。真实IdP→中央检查→SpiceDB→commerce链路由P2-05/06继续。

可重复工程位于compatibility/boot4-sdk。依赖锁定来自commerce现有Boot4.1.1；未升级auth/OA主框架。私密日志.local/governance/p2/p2-05a-boot4.log。
