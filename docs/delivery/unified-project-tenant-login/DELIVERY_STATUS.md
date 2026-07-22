# 统一项目租户登录 Delivery Status

## 当前状态

- 阶段：Phase 2 Complete
- 计划：Approved
- 实施：Complete
- Review：Pass（P0/P1 未关闭 0）
- QA：Pass with browser-environment limitation
- Release：本地 `8093` 与 `8095` 已重建并加载 Phase 2 产物

## 进度

- [x] 完成四仓现状与安全边界审计。
- [x] 确认 Recsys 当前只能安全开放 organization `recsys`，不具备真实多租户数据隔离证据。
- [x] 用户批准修订设计并授权开始执行。
- [x] 固化目标、非目标、路由、组件、契约、测试与回滚计划。
- [x] 修改门户正式入口和本地/生产示例契约测试。
- [x] 实现 Recsys 单租户确认、配置一致性校验和安全深链。
- [x] 实现 Drools allowlist 租户输入与 clientId 映射。
- [x] 同步四仓文档和 CI 门禁。
- [x] 完成完整测试、类型检查、生产构建、代码审查和运行态 HTTP 验证。
- [x] 重建并启动 Drools `activity-frontend` 本地镜像。

## 验证摘要

- Portal：17 tests、typecheck、production build、Docker image build 全通过。
- Recsys：54 tests、typecheck、完整 ESLint（0 error，17 个既有 warning）、Prettier、production build 全通过。
- Drools：62 tests、typecheck、production build 全通过；`8095` 健康容器已加载双栏品牌租户页。
- LangChain4j：552 tests、typecheck、production build 全通过；`8093` 已加载租户 allowlist 拦截产物。
- 运行态：门户目录为三条非 auto 入口；Recsys dev 实例为 OIDC 且加载新租户组件；Drools auth-config 返回 acme/beta 且已发布新静态 chunk；Casdoor 与三个目标 login URL 均返回 200。

## 环境限制

当前 Codex 会话没有可连接的浏览器实例，因此没有执行可视化点击/截图 QA。组件交互测试、静态产物检查和实际 HTTP 运行态检查均已通过；生产发布前仍应在真实浏览器以真实账号走一次 portal → target tenant → Casdoor → callback 回环。

## Phase 2 进度

- [x] 复现并定位未知租户跳转根因：LangChain4j 只校验格式，未校验存在性/开放列表。
- [x] 确认 Recsys 与 Drools 当前未知租户拦截已有测试证据。
- [x] 完成 Phase 2 产品、安全、UI、技术和验收设计，记录用户批准。
- [x] 实现 LangChain4j 租户 allowlist 双层校验，人工与旧 portal auto 入口均 fail-closed。
- [x] 重构 Drools 登录页视觉与状态，保留后端 `webClients` 安全映射。
- [x] 同步 Drools 真实 OIDC E2E 脚本到“输入租户 → 提交”交互契约。
- [x] 完成测试、构建、容器更新、审查、QA 和交付审计。
