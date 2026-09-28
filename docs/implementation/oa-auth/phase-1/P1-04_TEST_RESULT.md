# P1-04 唯一 OA 目录接入总验收

完整切片验证 PASS；auth 状态补充 fb59d82 的精确 CI 36399402126 SUCCESS，最终 Git 交付另行记录。此报告聚合完整切片，各子 pass 的历史未完成描述保留其当时含义。

## 已实现边界

OA 是员工、部门、任职、汇报关系与离职的唯一来源。OA 事务写事实与 Outbox，来源序号和聚合版本由数据库约束；初始化受控且有条数/字节/事务时间上限。
auth 通过固定来源配置拉取，逐事件提交 Inbox、主体/成员/目录投影、审计与连续检查点后再确认。身份只按精确 issuer/来源标识绑定；没有登录标识时只创建未绑定员工主体。
源和目标分别持有自己的库；没有跨服务写业务表。服务出口默认关闭，固定企业服务身份独立于员工 JWT。无角色/应用默认授予，无授权图写入。

| 可观察验收 | 真实证据 | 结果 |
| --- | --- | --- |
| 重复、旧版本、同版本改体、序号空洞 | auth DirectoryPostgresIT；数据库去重/隔离/不越过检查点 | PASS |
| 未完成快照不误删、错误摘要隔离 | auth DirectoryPostgresIT；OA 双库快照按两条分批且保留其他成员 | PASS |
| 来源写入与 Outbox 原子、并发提交有序 | OA IdentityDirectoryPostgresIT；注入异常、影响行数为零、锁与初始化竞争 | PASS |
| 精确绑定、未绑定员工、跨企业拒绝 | auth 持久化负例；OA 查询/出口固定范围 | PASS |
| 部门环和跨企业引用拒绝 | auth 部门环与 OA 源引用负例；两端无静默吞错 | PASS |
| 源端确认已提交但 HTTP 503 后恢复 | 真实 OA Tomcat/安全链/Controller + 两个独立 PG + auth 拉取器 | PASS |
| 在职到离职，旧 Token 不恢复成员 | 同一真实 Casdoor Access Token，OA leave 后目标成员不可见；ID Token 拒绝 | PASS |
| 手工/全局停用不被同步恢复 | auth PG 回归；重新加入明确增加代际 | PASS |
| 待同步、已确认、隔离可诊断 | 回环 HTTP + PG，已隔离/OA 离线仍可读；只读不改水位 | PASS |
| 旧路径、默认关闭、当前 HTTP 安全边界 | auth 198 unit/46 PG/5 IdP/60 HTTP，OA 全仓与前端 CI | PASS |

## 版本与证据

- auth consumer fd03979：P1-04_CONSUMER_TEST_RESULT.md；CI 36394932731 SUCCESS。
- auth importer b11d080：P1-04_IMPORTER_TEST_RESULT.md；CI 36398245134 SUCCESS。
- auth status fb59d82：P1-04_STATUS_TEST_RESULT.md；源码摘要 P1-04-status-evidence-index.json。
- OA source 626ecc8：oa-platform/docs/implementation/oa-auth/P1-04_SOURCE_EVIDENCE.md；CI 36397627546 SUCCESS。
- OA 双库/真实身份 bc0734b：同目录 P1-04_E2E_TEST_RESULT.md；CI 36398882953 SUCCESS。
- OA 本地 191 unit PASS/1 既有 skip、15 PG PASS，其中真实双库与专属 Casdoor 均实际执行。OA CI 真实 IdP 项明确 skip，不能替代上述本地证据。
- 私密运行日志均保留于 auth .local/governance/p1-04；不提交凭据或 Token。

## 兼容、恢复与限制

V1–V4 未修改；auth 增量 V5、OA 增量 V8 已在独立库执行。旧 P1-03 JAR 读取 V5 并拒绝已停用成员，见 final-old-jar-v5-result.json。
回退代码不能撤销已接受的离职事实。暂停拉取保留检查点；关闭 OA HTTP 出口不停止已登记源的事务捕获。恢复用相同来源配置补确认后继续，冲突不自动跳过或清游标。

本次没有正式目录接管、共享 IdP 升级或生产部署；未承诺同步 SLA 或生产容量。FORMAT 无规范工具、独立静态分析器 N/A；源 V8 已执行后保留不可变（尾部空白行不影响 SQL）。同一执行者做实现后验证，没有独立子代理审查。
