# 候选方案 A：原地 Expand–Migrate–Contract + Pull Reconcile

## 1. 架构

在现有 SpiceDB、auth-platform-admin 和 knowledge-service 内原地演进：先加载兼容 schema，部署 department 身份/双写/同步，完成 per-org backfill 和 shadow 验证，再切 enforce；稳定观察后才 contract 旧 relation。目标 schema 不变。

```text
Casdoor users/groups ──分页快照──> CasdoorClient
                                      │ per-org complete snapshot
                                      v
                              GroupSyncService(single writer)
                                      │ diff/TOUCH/DELETE fuse
                                      v
                                  SpiceDB
edge groups ─> internal JWT dept ─> TenantContext ─> DocumentService
                                                    │ owner+home_dept
                                                    v
                                                SpiceDB
```

## 2. 模块职责

- `knowledge.zed`：expand 时加 department 和目标 view/share；暂留旧 document relation/edit 作为回滚兼容，不让旧 parent 分支进入新 view。
- edge/platform-security：可选 `dept` claim 的向后兼容读写；不改变 DUAL/ONLY。
- knowledge-service：新建写 owner/home_dept，兼容窗临时继续写 parent_space；读仍判 `view`；分享改判 `share`。
- auth-platform-admin：按 org 拉完整快照、校验树和一人一部门、计算 direct tuple 差量、per-org 熔断；一个启用 writer 的实例执行。
- backfill/fixture：从 owner 唯一部门生成 home_dept，dry-run/manifest/后置断言。

## 3. 核心流程

1. pin SpiceDB image digest；探活 Casdoor parent/admin/pagination/token groups。
2. inventory 旧 document tuple；处理不兼容 group viewer；确认 dev 空数据或生成异常 manifest。
3. 保持 `RAG_AUTHZ_MODE=disabled`，加载兼容 schema。
4. 先发布内部 JWT reader，再发布 edge department writer；此时缺 dept 可兼容。
5. 发布 knowledge 双写和 department sync；同步所有 org，重复运行至零 diff。
6. backfill owner→department、public_viewer→public；逐文档验证唯一 home_dept。
7. disabled→shadow；观察 would-deny、missing/ambiguous/unsynced、同步 drift 和性能。
8. 按租户波次切 enforce（若部署开关只能全局，则以独立试点 deployment/环境做波次，不偷改 mode 语义）。
9. 观察窗后停止 parent_space/public_viewer 双写；最后 contract。V-06 未决时保留 legacy edit 兼容，不谎称纯目标完成。

## 4. 并发、事务和幂等

- snapshot 校验完才生成 diff；一个 org 的删除阈值在任何写之前计算。
- TOUCH/DELETE 可重跑；写分批后中断，下一轮由 direct tuple diff 收敛。
- `onDocumentCreated` 保留 owner CREATE 的防接管语义；响应不确定时读回 owner/home_dept，只有精确等于期望才视为幂等成功。
- `home_dept` 单值靠写前/写后审计和单文档写者约束，不假定 SpiceDB relation 自动唯一。
- 单 JVM 用 `synchronized` 防 webhook/定时重入；部署只允许一个 `writer-enabled=true` 实例，其他实例手工/webhook 返回非 writer。其 HA 弱点见下。

## 5. 改动范围和成本

改动两仓现有 identity、knowledge、admin sync、脚本、测试和少量 auth-console 兼容代码；不增加第二套授权基础设施或业务数据库。开发/运维成本中等，和当前代码最贴合。

## 6. 扩展性

- 多 org 通过独立 snapshot/diff/阈值扩展；失败隔离在 org。
- department tuple 与 document ID 都带 org 前缀，可从全局 direct relationship inventory 分区。
- 日后可把 `writer-enabled` 升级为外部 lease，而不改变 Casdoor snapshot 或 SpiceDB tuple 形状。

## 7. 已知弱点

1. schema 的 `view` 是全局切换，真正的 per-org schema 开关不存在；试点要靠隔离环境/部署与数据 readiness，而非假装 schema 能按租户切。
2. 配置式单 writer 没有自动 HA；误启第二 writer 时，旧快照可能与新快照竞态。上线门必须检查副本/配置，长期可吸收方案 D 的 durable lease。
3. 兼容 schema 比最终 schema 宽，contract 前有技术债；V-06 会延长旧 edit 保留时间。
4. 业务数据和 SpiceDB 无分布式事务，关系写失败可能留下“存在但不可见”的文档；安全正确但需 reconcile。

## 8. 结论

这是推荐主方案：最少改变现有运行架构，提供可证明的 backfill、shadow、回滚和幂等路径。吸收方案 D 的 webhook 触发与 lease 接口思想，但不先建设事件平台。
