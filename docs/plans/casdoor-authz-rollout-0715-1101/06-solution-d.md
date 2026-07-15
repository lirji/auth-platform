# 候选方案 D：人工 Casdoor 配置 + 一次性硬切

## 1. 架构

由管理员在 Casdoor UI 手工创建 org/user/role/permission 和角色分配，导出截图/JSON作为检查记录；随后同一维护窗开启 edge、把前端直接切到 oidc，并删除或停用 session/auth-service 路径。机器 API Key 保留。

## 2. 模块职责

- Casdoor 管理员：按 runbook 完成全部对象和顺序；
- 运维：维护窗同时切 edge/frontend/auth-service；
- 验证人员：抽查 admin/viewer 等 token 和几个端点；
- 回滚：恢复旧镜像、前端构建和 auth-service。

## 3. 核心流程

1. UI 建 org、users、roles；
2. 先分配角色，再手工重建 permission；
3. 少量 token 抽查；
4. edge on、frontend oidc、legacy off 一次完成；
5. 出错整体回退。

## 4. 改动范围与成本

- 代码和工具改动最少；短期工时低；
- 配置操作、审核和重跑高度依赖人员；没有机器可验证的 desired/current diff；
- tenant 或用户增加后成本线性增长。

## 5. 幂等、并发与审计

- UI 操作不具备可证明幂等；并发管理员可能相互覆盖；
- permission 写入时展开的顺序坑容易遗漏；
- 截图不能证明对象全集，也不能可靠复现；
- 出错后难以判断部分成功边界。

## 6. 主要风险

- 一个角色分配或 permission 漏项会在硬切后造成大面积 403；
- audience/redirect/allowlist 任一漂移会造成全量 401；
- 当前服务端 scope 门禁差异可能在有限抽查中被漏过；
- 删除 session 路径后，前端回滚到 apikey 构建也未必能重新登录；
- Casdoor/JWKS 故障没有观察期与 session 热兜底。

## 7. 回滚

理论上恢复旧配置；实际上硬切若同时删除路由、Secret、实例或 DB，回滚会跨多个系统且容易超时。必须禁止在同一维护窗做物理删除，但这又削弱了“大爆炸”的唯一优点。

## 8. 适用结论

只适用于完全可丢弃的个人 demo。即便本项目数据是 dev/演示，目标仍是全平台灰度与可审计收敛，因此不推荐。
