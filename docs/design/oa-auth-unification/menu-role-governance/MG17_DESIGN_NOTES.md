# MG17 人员变更治理影响分析（历史DRAFT）

本文件为冻结前只读草稿，已被[MG17_CONTRACT](MG17_CONTRACT.md)替代；当前状态以[PROGRESS_STATE](PROGRESS_STATE.md)为准。下方TODO／待业务选择为历史记录。

2026-10-03。只读核对现有目录、生命周期和来源解释链路；D-HR 岗位治理选择及 MG14 依赖尚未完成。本文件不冻结岗位授权规则，不新增人员写入口，MG17 仍为 TODO。

## 当前事实

- [DirectoryPullImporter](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/application/DirectoryPullImporter.java) 沿用 OA 权威来源／环境／企业分区，网络拉取在数据库事务外，每页最多 100 事件，本轮有两分钟截止和配置的事件上限。本地连续检查点提交后才确认；重跑先确认已有水位，冲突隔离时来源水位返回未知，不能把来源不可用当成同步成功。
- [DirectoryGovernance](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/directory/application/DirectoryGovernance.java) 验证身份绑定、聚合版本和完整快照；手工 SUSPENDED 不被 OA ACTIVE／LEFT 绕过。LEFT 重新加入会增加成员 generation，旧个人 DIRECT／OA 来源不会因重新入职自动对应新代际。GROUP 仍按当前有效组成员代际及任职区间判定，不能将组授权展开为永久个人授权。
- [DirectoryMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/DirectoryMapper.xml) 的 directory_entry 更新当前 payload_json；Inbox 和版本回执保存版本、摘要等事实，没有保存完整旧人员 payload。因此不能从现有记录还原每次历史调岗的前后部门差异。新增可读变更证据应在受信事件接受事务中记录必要前后事实；既有历史缺失必须如实呈现，不能补造。
- [LifecycleGovernance](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/identity/application/LifecycleGovernance.java) 区分租户成员暂停、全局主体暂停和外部成员退出，命令、版本 CAS 与审计同事务。离职人员不等同所有历史 Grant 已逐条 REVOKED；当前身份读取查数据库状态，访问不可用与原来源撤回回执应分别说明。
- [IdentityGovernance](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/identity/application/IdentityGovernance.java) 与 [IdentityMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/IdentityMapper.xml) 在当前登录身份解析时核对主体、企业、成员状态、代际及期限；旧 Token 的身份验证成功不能绕过当前成员检查。实际旧 Token 拒绝仍需本片真实链路验证，不能仅引用源码视为验收通过。
- [LifecycleCli](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/cli/LifecycleCli.java) 是持有专用配置与数据库操作权限的受控运维入口，普通治理管理员没有等价人员写权限。新诊断页面不能顺带提供绕过该边界的暂停／离职接口。
- [PortalPermissions](../../../../auth-platform-governance/src/main/java/com/lrj/authz/governance/access/application/PortalPermissions.java) 将本人来源与他人诊断分开，他人信息需当前管理委派及独立分区诊断资格，并保留访问审计。[PermissionMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/PermissionMapper.xml) 的 GROUP_CHECK_REQUIRED 不是逐资源 ALLOW；暂停、旧代际、投影等待、失败和完成撤权不能合并为一个“已回收”标签。

## 待决定的 D-HR

已异步提出，尚未收到业务选择：

1. 推荐先核对人员／组织变化，由负责人复核，不自动按岗位授予。
2. 配置岗位与角色映射，调岗后立即撤旧授新。
3. 配置岗位与角色映射，并设置有限交接期。

第一种不需要推断岗位角色，但仍需明确谁承担复核责任；后两种还需业务提供映射所有者、例外来源处理、切换或交接规则。不存在的映射不能用全员／管理员角色代替。所有方案继续以 OA 为人员事实权威，不由权限平台修改 OA 人员或组资格。

## 后续实施边界

MG14 完成并收到 D-HR 后冻结 MG17 契约。首个闭环需要受信变更证据、当前人员／组版本、多来源解释、投影结果和有界诊断页面；当前内容与历史缺失要区分。若按岗位自动处理，仅处理被明确纳入规则的来源，个人例外及审批事实不能被未确认规则清空。

验收需在独立测试库及新 UUID 分区验证重复／旧事件、离职旧 Token、新代际、退组与多来源、当前诊断资格失效、同步未知／冲突及投影中断；调岗保留例外必须有可追溯决定。本文件没有执行这些产品验收，不标 PASS 或 DONE。MG18 的复核负责人、条目冲突和受控整改契约接在该规则之后，不提前添加 cron 或外部通知。
