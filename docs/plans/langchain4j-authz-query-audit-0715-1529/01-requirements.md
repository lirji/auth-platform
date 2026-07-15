# 01 — Requirements Analysis

> 视角：requirements-analyst。审计快照为 2026-07-15 两个工作树的当前内容；两仓库均有未提交改动，因此文中行号对应本次审计时工作树，不等同于各自 `HEAD`。

## 1. 目标

审计 `langchain4j-platform` 接入 `auth-platform` 后，`knowledge-service` 的所有授权查询调用是否满足：权限语义正确、租户隔离、主体可信、批量响应严格对齐、模式语义一致、依赖失败姿态安全，并能在发布前以测试和运行门禁证明。

本次“授权查询”仅指 `AuthzEngine.check/checkBulk/lookupResources/lookupSubjects` 及 `@CheckAccess` 间接 `check`。关系写删仅作为查询结果能否正确推导的前置证据，不扩展为关系写入事务重构。

## 2. 非目标

- 本轮不实现新权限产品、space CRUD、folder ACL、图谱溯源或图片 ACL。
- 不修改业务代码；本轮只产出审计与修复计划。
- 不把性能优化自动等价为正确性修复；`fullyConsistent` 是否降级必须以撤权语义和实测为依据。
- 不推断历史文档 owner，不发明 schema 中不存在的 `document.manage/share` 权限。
- 不把公共库 `__public__` 改造成 SpiceDB `public_viewer`；当前公共分区是独立、显式开启的产品语义。

## 3. 已确认业务规则

1. 文档读使用 `view`；覆盖、删除、分享、撤销分享使用 `edit`。
2. SpiceDB document/space id 统一为 `<tenantId>_<id>`，只由服务端构造。
3. 调用主体为 `SubjectRef.user(userId)`；Casdoor token 的 `sub` 经 edge 换成内部 JWT `uid`，下游验签后重建 `TenantContext.userId`。
4. `document.owner` 可推导 `edit`，`edit` 又经 `comment` 推导 `view`；`parent_space->edit/view` 也参与文档权限。
5. 已冻结的 D3 业务决定是“同租户成员默认可见”：必须存在 `space:<t>_default#viewer@group:<t>_<members>#member` 及相应 group membership。该 tuple 不是 `RealKnowledgeAuthz.onDocumentCreated` 自动创建的，而由受控 provision/backfill 负责。
6. `disabled` 不调用 AP；`shadow` 对实际执行的单查/批查 fail-open 并记录差异；`enforce` 真过滤/真拒绝并在依赖异常时 fail-closed。
7. 公共库命中有意绕过文档 ReBAC；严格租户模式与公共库启动配置互斥。
8. 无 `docId` 的非公共命中在 enforce 下必须丢弃。
9. 当前分享 API 的既有设计只在 enforce 暴露；早期已批准计划明确 disabled/shadow 不暴露。它与本次任务措辞“shadow 放行操作并观测”存在冲突，必须作为待确认项，不能擅自改变 API 可见性。

## 4. 边界与易遗漏点

- `DocumentService.list/get/delete/overwrite` 也是授权查询消费方，不只 `/rag/query`。
- `GraphController`、`MultimodalImageSearchController` 没有调用 AP，但 enforce 下按 mode 整体禁用，属于授权覆盖边界。
- 全仓库只有 `knowledge-service` 引入 `auth-platform-sdk`；其它服务没有四类查询调用。
- conversation 的 L1 语义缓存位于 RAG 前，按 tenant 而非 user 分桶；一旦开启会短路 knowledge 查询。它不是新增授权调用，却会绕过已审计调用，并使撤权强一致失去端到端意义。
- `ResourceRef` 是 Java record，equals/hashCode 包含 `type/id`，bulk map 回读必须构造完全相同的二元组。
- `shared` 是内部检索源产生的标志；当前真实源不可由请求体直接伪造，但未来新增 `RetrievalSource` 是信任边界。
- 当前 SpiceDB 镜像使用 `latest`，仓库未冻结服务端 bulk 上限；默认 `bulk-size=100` 是否满足生产版本限制/延迟 SLO 为“待验证”，不能臆造数值。

## 5. 验收标准

- 有完整调用清单，覆盖 7 个有效消费场景、2 个 AOP 方法、0 个 lookup，并给出实现落点。
- 12 个指定维度逐项给出“正确/存疑/缺陷”、证据、风险、触发场景和最小修复。
- enforce 下任何 AP/SDK 异常不产生未授权放行；shadow 下同类异常不阻断既有读路径。
- malformed check/checkBulk 响应必须被识别为协议错误，而不是伪装成普通 deny。
- D3 tuple 和 group membership 在切 enforce 前可机器验证；缺失则阻断切换。
- `RAG_AUTHZ_MODE=shadow|enforce` 时，conversation tenant-wide pre-RAG semantic cache 保持关闭，直到有授权感知的缓存设计。
- 所有现有公共/私有、null-docId、分批、乱序响应、依赖故障、AOP 代理场景均有自动化验收。

## 6. 待确认但不阻塞本次计划

| 编号 | 问题 | 默认处理 |
|---|---|---|
| Q1 | shadow 是否应暴露 share/unshare 并只观测不拦截 | 沿用已批准的“仅 enforce 暴露”；若产品改口，按方案 B 单独实施 |
| Q2 | editor 是否可继续转授 viewer | 当前规则明确用 `edit` 且 schema 无 `document.manage`，本审计判为正确；如要 owner-only，需独立 schema/产品变更 |
| Q3 | 生产 P95/P99 与 SpiceDB bulk 上限 | 待压测；不得据此提前降低一致性 |
| Q4 | authorization-aware semantic cache 的长期形态 | 本轮先禁用冲突组合；长期方案需 user/授权版本或命中源重校验 |

