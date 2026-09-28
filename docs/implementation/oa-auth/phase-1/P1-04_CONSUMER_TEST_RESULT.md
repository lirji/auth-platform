# P1-04 auth 消费 pass 验证

本 pass Gate PASS；P1-04 整体仍未完成。
源码指纹：`5476d241c90be33ecc8443f9cb50eb5888a4799a9baa58b6a0678bb0064659cf`，逐文件索引见 P1-04-consumer-evidence-index.json。

| 验证范围 | 实际结果 | 证据 |
| --- | --- | --- |
| 整仓编译与单测 | 189 PASS，零失败/错误/跳过 | consumer-full-final.log |
| PostgreSQL 持久化 | 44 PASS，目录新增15 | 同上 / DirectoryPostgresIT |
| 固定版本真实 IdP | 5 PASS，未污染原未绑定账号 | identity-final.log |
| 既有上下文与停用 HTTP | 30 PASS | context-final.json |
| 外部邀请 HTTP/CLI | 30 PASS | invitation-final.json |
| FORMAT | UNVERIFIED：无仓库统一 formatter | Hygiene 限制 |
| 独立静态分析器 | N/A | 仓库未配置 |
| OA 事务生产者/HTTP出口/双库 | UNVERIFIED，本 pass 不声明完成 | 后续 pass |

目录验收已覆盖：精确身份/缺标识、范围伪造、重复和并发、连续检查点不越过空洞、
聚合所有版本异内容检测、审计失败回滚后重试、首次多企业并发绑定无孤儿主体、
手工/全局暂停不恢复、离职后代际变化、完整/不完整/错误摘要快照、部门多节点环。
协议字段没有邮箱或姓名，未调用任何角色、应用或 SpiceDB 写入能力。

独立审查说明：同一执行者在实现之后核对源码与实际结果，并未声称跨模型或代理评审。
目录事务无远程调用，回滚后单独记录冲突；局部保存点只撤销尚未对外可见的候选主体。
V1–V4 不变，V5 为增量迁移；当前测试针对本任务独立 PostgreSQL 与固定版本 Casdoor。
不把源端日志、进程健康或本 pass 的成功当作 P1-04 完整交付。

远程精确提交 CI 待执行。日志均位于 `.local/governance/p1-04/`，不包含在版本控制中。
