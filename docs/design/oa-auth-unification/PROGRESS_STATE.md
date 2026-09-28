# 改造计划与实施进度

## 当前状态

已授权连续实施。P0、P1-00/01/02 DONE；P1-03 READY。P1-02 只在隔离环境验证，HTTP 新路径尚未启用；共享 IdP 升级 HOLD。单执行者，复用已有任务工作树。

## 已完成

- P0：源文件/63 节点 DAG/三仓基线与源码证据，历史事实保留 phase-0。
- P1-00：CONTRACTS_P1 与 TECH_SELECTION 冻结，3d404c3。
- P1-01：治理库/身份成员绑定/命令幂等/事务审计/CLI，e5ff625 已正常合并推送 main；精确 CI 36384588723 SUCCESS。
- P1-02：[Token/身份独立验证](../../implementation/oa-auth/phase-1/P1-02_TEST_RESULT.md) PASS：149 单测、11 PG、5 Casdoor、1 legacy IT；错误用途/签名/时间/受众、实时状态、依赖故障与容量上限。源码和升级门禁有独立证据；当前交付待 Git 事实确认。
- Q-DIR 用户已确认：OA 员工和组织目录为唯一事实来源；P1-04 来源/租户映射准备在 phase-1，尚未接管正式目录。

## 未完成和限制

- P1-03 本人/内部双身份上下文、P1-04/05/06 和 P2—P7 尚未实施或未验收，按稳定 DAG 连续推进。
- 共享 Casdoor 实测 v4.3.0，Access==ID，禁止用于新治理入口。隔离 v4.11.0 解决用途混用，但码交换不拒绝错误 redirect_uri；[升级方案](../../implementation/oa-auth/phase-1/CASDOOR_COMPATIBILITY_UPGRADE.md) Gate HOLD。未升级共享实例、未演练旧库迁移、未验证浏览器完整登录。
- 正式只读版本证明/最小权限凭据仍需落实；隔离 built-in 运维客户端不发给 SDK/浏览器。
- Q-EXT 外部真实业务资源/场景未确认，只阻塞对应试点；归档期限、正式运维和容量/生产目标待确认。
- Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：无仓库 formatter；没有独立静态分析器，N/A。

## 授权与保留

用户已授权继续实现，AGENTS 第8条授权验证后正常任务提交/合并/推送 main；无强推/生产部署/共享 IdP 替换/清库授权。第9条允许复用已有 auth-platform-p1-identity 工作树，不按阶段再建目录。原 OA/commerce 改动保留，源方案未改。

## 下一步

按 task-git-delivery 交付 P1-02 并观察精确 SHA CI，随后 P1-03：本人列表、固定服务身份/独立用户 Token、当前数据库成员与代际/版本、严格 DTO/错误/default-off 装配。通过实施/独立验证/进度同步后继续 P1-04 等依赖满足的片，不等待反复继续。
