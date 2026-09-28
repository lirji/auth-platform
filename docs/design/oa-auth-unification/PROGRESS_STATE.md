# 改造计划与实施进度

## 任务目标与当前状态

用户范围：完成 P1 后暂停，不推进 P2；auth 只保留原主目录。P0、P1-00—P1-07 已完成验收，当前收尾两仓正常 Git 交付与 main CI。63 个稳定 DAG 节点保持不变，P2—P7 未执行且未获本次授权。

## 已完成

- P1-00/01：冻结身份契约、独立治理库、主体/成员/精确登录映射、幂等与事务审计。
- P1-02/03：固定隔离 Casdoor 的 Token/用途/来源验证，本人成员与内部服务/用户双身份上下文。
- P1-04：OA 权威事务 Outbox、受控初始化/出口、auth 幂等消费/拉取/确认恢复、离线冲突诊断；真实双库及专属 Casdoor 离职链路通过。
- P1-05/06：外部邀请/并发接受/退出重入、成员及全局主体停用、版本 CAS 与审计；旧 Token 不能绕过当前状态。
- P1-07：总验收与 P2 交接。auth 198 unit+46 PG+5真实 IdP+60 HTTP/CLI；OA191 unit通过/1既有跳过、15 PG全部通过。旧 P1-03 JAR读取V5仍拒绝停用成员。
- 最终产品精确 CI：auth fb59d82 / 36399402126 SUCCESS；OA bc0734b / 36398882953 SUCCESS。详细历史证据见 phase-1/P1-04_TEST_RESULT、P1-07_TEST_RESULT。
- auth worktree已正常收敛到原目录；224个私密/历史文件校验保存，其他仓P0基线位于 .local/p0-baselines。

## 两仓交付状态

| 仓库 | 产品与任务分支 | 当前交付 |
| --- | --- | --- |
| auth-platform 原目录 | feat/oa-auth-p1-identity；最终产品 fb59d82 | 验证通过，阶段文档与 main 交付收尾 |
| oa-platform 原目录 | feat/oa-auth-p1-directory；产品 bc0734b、CI记录4ea8be9 | 已正常合并推送 main 4ea8be9，main CI36399675935待完成 |

OA 原 CODEX_PROGRESS、既有设计 PROGRESS_STATE、DEPLOYMENT_RESULT 和 tmp 为用户原改动，未纳入本片；5个文件 SHA 已核对不变。

## 保留限制

- 共享 Casdoor v4.3.0 Access==ID，禁止用于新治理入口。隔离v4.11.0用途校验通过，但错误redirect_uri仍可兑换，完整升级 Gate HOLD。正式最小权限版本证明、浏览器回调与旧身份库迁移未完成，不宣称生产就绪。
- 身份上下文不等于应用准入、RBAC或数据权限。P2—P7仍TODO；Q-EXT仅阻塞后续真实外部业务试点，正式保留期限及容量目标待所属阶段确定。
- Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：无统一formatter；独立静态分析器N/A。源V8已执行后保留不可变，尾部空白不影响SQL。
- 没有正式目录接管、共享认证替换或生产部署。只在隔离环境验证新路径，运行默认关闭。

## 下一步与授权

完成最终正常 Git 交付、main CI 与保留目录审计后暂停；后续授权时从 P2-01 接续，交接见 phase-1/P2_HANDOFF.md，不重新规划或要求反复继续。
AGENTS第8条已授权正常提交、合并和推送main；不强推、不清库、不生产部署。隔离数据库、私密配置和证据仍须保留，不自动删除。
