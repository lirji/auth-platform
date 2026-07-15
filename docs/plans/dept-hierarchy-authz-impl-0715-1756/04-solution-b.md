# 候选方案 B：维护窗离线迁移 + 一次性原地切换

## 1. 架构

不建设在线 department reconcile 的完整灰度窗口。维护窗前导出 Casdoor 和 SpiceDB 快照，生成完整 migration manifest；维护窗内关闭知识写入/将 authz 置 disabled，写目标 schema/tuple，部署新版本后直接 enforce。

## 2. 模块职责

- 离线脚本负责 Casdoor 全量分页、树校验、owner→department 映射和 tuple 写入。
- auth-platform-admin 的在线 GroupSync 只在切换后启用，首轮即接管目标状态。
- knowledge-service 直接单写 owner/home_dept，不保留 parent_space 双写。
- 运维通过维护窗和数据库/SpiceDB snapshot 提供一致点。

## 3. 核心流程

1. 冻结 Casdoor group、知识上传、分享和删除。
2. 导出 Casdoor org/users/groups 原始 JSON、全部 document direct tuple、registry 文档清单。
3. 离线生成并人工核对 manifest；异常必须归零。
4. 备份 SpiceDB datastore；清理与目标 schema 不兼容的旧 group viewer/public relation。
5. 写目标 schema、department/home_dept/public tuple；部署 edge/internal JWT/knowledge/admin。
6. 跑全矩阵后直接切 enforce，解除冻结。

## 4. 并发、事务和幂等

维护窗冻结减少并发。manifest 有 revision/hash，重复 apply 用 TOUCH 幂等；每次 apply 后全量读取对账。由于 schema、tuple、应用部署仍不是单事务，步骤间失败依赖 snapshot restore 或继续向前修复。

## 5. 改动范围和成本

应用兼容代码最少，不需要长时间双写或 dual relation；但离线导出、冻结、快照恢复和演练成本高，对停机窗口要求严格。

## 6. 扩展性

适合确定无历史数据的 dev 或非常小的单 org。org/文档规模增大后维护窗线性增长，频繁组织变更仍最终需要在线 reconcile。

## 7. 风险与弱点

- 冻结不彻底会丢失 group/文档变更；Casdoor 与业务 registry 没有共享事务水位。
- 一次性 enforce 缺少真实流量 shadow 证据。
- schema 收缩后应用回滚依赖 SpiceDB datastore restore，RTO 高。
- V-06 edit 缺口在切换前必须解决，无法靠兼容 schema延期。
- 多 org 中一个异常会延长全局维护窗。

## 8. 结论

仅在“已显式证明 dev 无历史文档/tuple”时可作为简化路径；不适合作为生产主方案。
