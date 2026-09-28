# P1-07 真实集成总验收

验证结果 PASS；auth 最终产品 fb59d82 的精确 CI 36399402126 SUCCESS。两仓 Git 交付结果另行记录。适用范围为已冻结 P1 后端身份/目录/邀请契约；不声明 P2 权限闭环或生产就绪。

| P1 能力 | 验收证据 | 结果 |
| --- | --- | --- |
| 主体/成员/身份/旧映射持久化 | P1-01_TEST_RESULT，当前真实 PG 回归 | PASS |
| Token 签名、用途、发行方/受众、过期与身份映射 | P1-02_TEST_RESULT；final-identity.log 的 5 项真实 Casdoor | PASS |
| 本人组织与服务/用户双身份上下文 | P1-03_TEST_RESULT；final-context-result.json 30 项 HTTP/CLI | PASS |
| OA 权威目录与故障后恢复 | P1-04_TEST_RESULT；实际双库、真实来源写入及身份源 | PASS |
| 外部邀请、目标绑定、并发接受与退出重入 | P1-05_TEST_RESULT；当前 9 邀请 PG 与 final-invitation-result.json 30 项 | PASS |
| 当前成员/主体停用及审计 | P1-06_TEST_RESULT；当前 PG/HTTP 回归，旧 Token 不能绕过当前状态 | PASS |
| 向后读取兼容 | 旧 P1-03 server JAR + V5，当前停用成员返回403 MEMBERSHIP_UNAVAILABLE | PASS |
| 跨仓持续验证 | auth、OA 各批精确 CI，链接见 P1-04 总验收和交付记录 | PASS |

最终本地 auth 全仓 198 unit +46 PG（244，零失败/错误/跳过）；身份专项另有5项真实 IdP；两个真实 HTTP 脚本各30项。OA 全仓191 unit通过/1既有跳过，15真实PG集成全部通过。不是将 Mock、健康检查或一次成功发送当作真实链路证据。

测试使用 dev_infra PG16 下两个独立专用库/角色及固定 v4.11.0 Casdoor 隔离实例18090。OA 本地双库IdP使用专属 run-01，不污染原未绑定外部负例。产品默认新入口关闭，生产配置未启用。

## 证据保存

auth .local/governance/p1-04：status-full.log、final-identity.log、dual-db-idp-full.log、final-context-result.json、final-invitation-result.json、final-old-jar-v5-result.json、status-hygiene.json。
聚合摘要和文件 SHA-256 见 P1-07-evidence-index.json；历史失败日志保留，不伪造全部尝试一次通过。

## 不属于本次完成声明的事项

- 共享 Casdoor v4.3.0 Access/ID 混用，隔离 v4.11.0 仍存在错误 redirect_uri 兑换缺陷，升级 Gate HOLD。正式最小权限版本探针、浏览器 state/nonce/回调及存量登录迁移仍须后续解决。
- 未完成外部企业联邦 SSO、真实供应商业务试点、应用 RBAC、数据权限、OA 权限审批或统一门户。
- 未验证生产容量、跨实例授权撤销时限、灾备恢复和生产发布。当前目录传播有延迟，源已提交不等于 auth 已确认。
- 无规范 formatter，独立静态分析器 N/A。同一执行者实施后验证；不声明独立代理审查。

上述边界沿用冻结 CONTRACTS_P1 与兼容升级报告，不把共享升级 HOLD 改写成成功。P1-07 交接见 P2_HANDOFF.md，按用户要求不执行 P2。
