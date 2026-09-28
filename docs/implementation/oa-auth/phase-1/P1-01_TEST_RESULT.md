# P1-01 独立验证

结论：PASS（P1-01）；当前源码指纹 `84f79794282605c01156646e5d74be3ce2e9ea93721914f88b32b9abc3f8b564`。实现边界为治理关系库与受控 CLI，后续认证/权限不得由此宣称完成。

| 验收 | 实际证据 | 结果 |
|---|---|---|
| 编译与旧行为回归 | 全 reactor `mvn -B -Pgovernance-it verify`；126 单测，原有 124 项保留 | PASS |
| PostgreSQL 真实语义 | 专用 PG16.15 数据库、非超级用户/无 CREATEDB/CREATEROLE，11 IT，无跳过 | PASS |
| 身份冲突与旧键保留 | 相同 issuer/sub 重绑定失败、新记录/命令回滚；旧主键仅映射；多企业与其他 subject 隔离 | PASS |
| 唯一与幂等/并发 | 同企业主体唯一、旧键冲突、命令摘要冲突；四线程同命令只一条成员/审计 | PASS |
| 状态、时间与 CAS | 当前主体/企业/成员停用拒绝，过期不列出；两个同版本 CAS 仅一个成功 | PASS |
| 审计失败原子性 | 真实 PG 触发器拒绝指定夹具 audit INSERT，错误原因核验，主体/企业/身份/命令均回滚 | PASS |
| 迁移与注释 | 所有业务表/列 COMMENT 存在；显式迁移与非迁移 validate 重开 | PASS |
| 可复现入口 | Java CLI bootstrap 两次 + lookup 一次，同一结果；宽文件权限拒绝；无凭据回显 | PASS |
| Runtime工具 | 私密目录/文件0700/0600，独立库重跑不重复建库、不重置密码；Python 编译 | PASS |
| CI 配置 | 同 profile + PG16 service + 专用非超级用户 Owner；原 Bash 校验命令通过；远程精确 commit 另查 | PASS（本地）；远程待查 |
| 代码卫生 | Claude gate IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；git diff --check | PASS |
| 在线身份/正式目录/业务权限 | 归 P1-02/03/04/P2；本片无线上入口 | NOT_RUN（后续验收） |

限制：仓库无 formatter/static analyzer，FORMAT TOOL_NOT_AVAILABLE、STATIC N/A；不凭人工核对声称工具执行。没有容量/故障/生产部署或审计归档策略验收。数据库是本任务唯一命名空间，夹具/注入触发器保留供复核，不影响共享业务数据。

可复跑命令在 `auth-platform-governance/README.md`；结果明细与源码摘要见同目录 P1-01-test-results.json/P1-01-evidence-index.json。最终成功日志位于忽略 `.local/governance/p1-01-final-verify.log`，初次失败也保留。
