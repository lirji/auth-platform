# P2-05 中央协议与SDK验收

PASS。全仓package、SDK install/unit、Boot4兼容复跑均通过；CentralAccessClientTest 4项真实HTTP边界专项通过，覆盖ALLOW/DENY/401/503、畸形/缺字段/错误主体上下文/错误scope、批量缺项重复错位与超时。SDK响应有64KiB上限，连接/请求超时固定有界，不重试、不缓存。

`python3 deploy/governance-access-smoke.py --with-checks`：真实管理JAR/检查JAR + 专属Casdoor身份 + SQL + SpiceDB，共31项检查通过。证据 `.local/governance/p2/access-79e1fcafcc44/result.json`。包含管理16项及新协议的伪造主体、错服务/用户受众、跨租户、错代际、重复批次、待投影不可用、真实ALLOW、撤销后无ALLOW、清理后DENY和后续隔离夹具。

应用/环境由固定服务配置给定，新增access.check.callers允许名单避免旧context.resolve服务自动获新能力。所有中央入口默认关闭；旧九项SDK及AOP兼容回归通过。

跨请求缓存、多实例撤权和细范围仍未启用。批量最多20项并限定同tenant/generation；单检查候选最多100且有6秒预算，批量10秒预算，不把超时当DENY或成功。完整商城应用真实链路由P2-06继续。
