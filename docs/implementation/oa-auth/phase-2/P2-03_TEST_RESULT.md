# P2-03 管理API验收

PASS。全仓 `./mvnw -q -Pgovernance-it verify`（显式隔离PG配置）退出0；新增AccessWebTest 2项通过，既有真实PG 58项通过。增量V8补成员/租户复合FK，V1–V7未改。

`python3 deploy/governance-access-smoke.py` 真实admin JAR + 隔离Casdoor18090 + PG，16项HTTP/CLI链路通过。证据 `.local/governance/p2/access-88b14853dcb4/result.json`：缺Token/错误受众/无委派/伪造主体/跨环境/自授予/未知范围拒绝；创建角色与Grant、命令冲突/幂等、数据库状态查询、撤销和重放。当前Grant尚未投影时准确显示PENDING。

新入口由 governance.enabled + governance.access.enabled 双开关默认关闭；仍经过管理API专属Token认证。首个管理委派只从0600受控CLI配置初始化，HTTP不提供引导提权。公开DTO无私有实体/SQL/Token。无UI变化；无全局formatter或独立静态分析器，最终Hygiene记录限制。
