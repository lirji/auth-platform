# MG09 设计核验

结论：设计验收 PASS_WITH_LIMITATIONS，MG09设计DONE；MG10／机器运行启用所需真实身份／PG验收仍 UNVERIFIED，运行 gate HOLD，不声称已自动发布或已完成阶段C。

## 验证与边界

| 验收 | 实际依据 | 结果 |
|---|---|---|
| 用户环境决定 | 本会话明确选择独立权限实例／数据库隔离测试和生产，已写入架构／契约／进度。 | PASS |
| 当前写模型 | 实际读取ApplicationCatalog／CatalogMapper XML／IdentityGovernance／V6/V21/V22；HUMAN认证与当前Owner、固定票据、应用锁、原回执、期限复查、不写Grant的不变量保留。 | PASS |
| IdP选型核对 | Casdoor v4.11.0固定Git提交四个原文件读取、SHA256保存；7项client_credentials／application subject及type／access-token／浮点期限／实时Token查询等源码核对。 | PASS（源码） |
| 跨环境安全设计 | 发布目标固定到独立DB实例UUID和environment；环境专用client/audience/secret，body不能选数据库；测试Token不能通过生产专用受众／委派／实例校验。可信IdP可复用，不新增认证平台。 | PASS（契约推演，非HTTP） |
| 机器不冒用HUMAN | VerifiedMachine独立；主库ACTIVE SERVICE＋HUMAN Owner＋定期委派；原HUMAN入口不接受SERVICE。实际发布主体写SERVICE，另记录Owner／委派／目标。 | PASS（契约推演） |
| 自动化风险 | 纯展示／分组可以自动发布；能力及执行入口变化返回REQUIRES_OWNER_REVIEW，由现有真实Owner流程发布，机器不自动选择授权处理决定。 | PASS |
| 文档／状态 | 设计、选型、正式契约、任务依赖、当前进度、恢复说明和引用核对，无产品／配置／依赖改动。 | PASS |
| 安装版机器请求 | 本轮3个探测均在版本GET超时，未到Token请求；后续公开discovery也超时。docker inspect／logs分别10秒超时，最新docker ps5秒亦超时。 | UNVERIFIED／运行HOLD |

固定源码Git提交34eec9034f34ca25449e0e3b4683ce98f3faf088，7项检查为实际Python读取源码结果，不是认证协议stub或安装版Token成功证据。本轮未创建IdP应用、未获取机器Token、未更改原角色Grant／目录／部署；MG08此前真实SSO结果仍是此前验收，不能当作本轮机器凭据证据。

## 恢复与下一步

- 本机环境恢复后，MG10在全新专用PG目标／新专用Casdoor client中做实测，不能运行原Owner发布脚本或重发原commerce v2。
- 可以继续默认关闭的MG10实现／本机协议测试；必要真实验证通过前不得DONE、不得开启机器发布、不得声称生产准备全部完成。
- credential轮换必须同时禁用原委派，不能仅换secret假装旧Token失效；禁用后不恢复已发布版本或业务授权。
- 本轮设计交付遵循正常任务分支／main合并推送，Git最终SHA见私密mg09-delivery.json；纯文档路径不触发当前Auth CI，精确查询另核实。
