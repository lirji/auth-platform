# 改造计划与实施进度

## 当前状态

当前用户要求：完成 P1 后暂停，不推进 P2；工作树已收敛至原 auth-platform 主目录。单执行者，复用原任务分支。P0、P1-00/01/02 已完成并推送 main。P1-03 已交付 main（3634a2b，CI 36389104153 SUCCESS）；P1-06 已交付 main（9fd58ca，CI 36389719173 SUCCESS）。只在隔离环境启用新治理路径，共享 IdP 升级继续 HOLD。

## 已完成

- P0：源文件、63 节点 DAG、三仓基线与源码证据，历史事实保留 phase-0。
- P1-00：CONTRACTS_P1 与 TECH_SELECTION 冻结，3d404c3。
- P1-01：治理库、身份成员绑定、命令幂等、事务审计、CLI，e5ff625 已合并推送；精确 CI 36384588723 SUCCESS。
- P1-02：149 单测、11 PG、5 Casdoor、1 legacy IT PASS。前两次 CI readiness 失败已保留；最终原生 UID 与私密配置卷修复 3db3f3b 的精确 CI 36388476420 SUCCESS，已合并推送 main。
- P1-03：统一管理认证 Filter、本人列表、内部双身份上下文、当前数据库状态与版本、严格 JSON、专用 Runtime 默认关闭。167 单测、14 PG、5 Casdoor、29 真实 HTTP 检查 PASS；详见 phase-1/P1-03_TEST_RESULT.md。精确 CI 36389104153 SUCCESS，已合并推送 main。
- P1-06：受控成员/全局主体停用、版本 CAS、幂等与完整事务审计；170 单测、20 PG、30 HTTP 与旧 JAR/V3 读取兼容 PASS，见 P1-06_TEST_RESULT。
- Q-DIR：用户确认 OA 员工和组织目录为唯一事实来源；来源映射准备已完成，尚未接管正式目录。

## 未完成和限制

- P1-04 按既有 DAG 连续推进；P2—P7 留待后续授权，身份上下文不等于应用准入或业务授权。
- 共享 Casdoor v4.3.0 Access==ID，禁止用于新治理入口。隔离 v4.11.0 解决用途混用，但不拒绝错误 redirect_uri；完整升级 Gate HOLD。未升级共享实例，未完成旧库迁移及浏览器完整登录验证。
- 正式最小权限版本证明仍需落实；隔离 built-in 运维客户端不发给 SDK/浏览器。
- Q-PROVISION 已确认：可信 OA 来源自动建立主体和员工成员；登录身份仅凭精确来源标识绑定，禁止按邮箱/姓名合并。P1-04 按此规则冻结契约并实施。
- Q-EXT 外部真实业务资源/场景未确认，仅阻塞对应试点；正式审计保留期限和容量目标待确认。
- Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无仓库 formatter；独立静态分析器未配置，N/A。

## 授权与保留

AGENTS 第8条授权验证后正常任务提交、合并、推送 main；无强推、生产部署、共享 IdP 替换或清库授权。用户已明确授权清理已合并 auth worktree；已归档并正常移除，后续只使用原主目录；原 OA/commerce 改动和源方案均保留。隔离数据库、容器及忽略目录含进行中任务证据/私密配置，需要保留，不擅自清理。

## 下一步

P1-06 已交付；P1-05 本地验收 DONE（181 单测、29 PG、5 Casdoor、60 HTTP/CLI），精确提交 CI 待完成；P1-04 首次绑定策略已获用户确认。交付 P1-05 并完成 P1-04 后执行 P1-07 总验收，随后暂停，不开始 P2。每片完成实施、独立验证、文档/进度与 Git 交付，不等待反复继续。

目录收敛证据见 docs/implementation/oa-auth/WORKTREE_CONSOLIDATION.md；Git 只剩主目录工作树，历史资料/私密配置和 P0 基线已保留。
