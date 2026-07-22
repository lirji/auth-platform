# 统一项目租户登录 Delivery Report

## 交付结论

两阶段均已完成。统一门户不会替用户固定 acme/recsys/clientId，而是进入各项目自己的租户页；三个目标均在目标 origin 校验租户、建立 PKCE 后才跳 Casdoor。Phase 2 进一步关闭了 LangChain4j“格式合法但租户不存在仍跳转”的缺口，并把活动引擎登录页统一为双栏品牌体验。本地服务已更新，代码审查无未关闭 P0/P1。

## 已交付能力

- Portal：本地与生产示例均使用非 auto 登录地址，新增防回归契约测试。
- LangChain4j：新增部署租户 allowlist、页面即时校验与 auth store 二次校验；人工/旧 auto 未知租户都停留本页；空 allowlist fail-closed；补齐构建参数、文档和测试。
- Recsys：新增 `VITE_CASDOOR_ORGANIZATION`、租户输入、单租户 fail-closed 校验、clientId 后缀一致性校验和正式入口深链恢复。
- Drools：登录页使用双栏品牌卡、移动紧凑头、租户快捷选择和独立加载/配置/校验/跳转状态；tenant 仍从后端 webClients allowlist 精确映射 clientId；新前端镜像已发布到本地 `8095`。
- Compatibility：三个目标的受控 `source=portal&auto=1` 分支仍保留，旧书签不被强制打断，但新 catalog 不再使用。
- Engineering：同步交付计划、状态、审查、QA、接入文档、CI 与回滚说明。

## 本地访问

- 统一能力门户：`http://localhost:5274`
- LangChain4j 租户页：`http://localhost:8093/login?redirect=%2F`
- Recsys 租户页：`http://localhost:5275/login?returnTo=%2Foverview`
- Drools 租户页：`http://localhost:8095/ui/login?returnTo=%2Fhome`
- Casdoor：`http://localhost:8000`

## 生产配置

1. 复制 `project-portal/config/catalog.example.json`，只替换为各环境浏览器可达的 HTTPS 登录域名，不添加 `auto`、tenant 或 clientId。
2. LangChain4j 先在 Casdoor 开通 organization/shared app，再设置逗号分隔的 `SHOWCASE_CASDOOR_TENANTS`（Docker Compose）或 `VITE_CASDOOR_TENANTS`（直接构建）并重建；显式空值会关闭租户提交。
3. Recsys 构建时设置 `VITE_AUTH_MODE=oidc`、`VITE_CASDOOR_ORGANIZATION=recsys` 和匹配的 `VITE_CASDOOR_CLIENT_ID=...-org-recsys`；网关 `CASDOOR_ORG` 必须同值。
4. Drools 后端 `web-client-map` 必须列出允许的 tenant/clientId；前端不接受目录传入的任意 clientId。
5. 在 Casdoor 精确登记每个目标项目自己的 callback/logout URI 和 CORS origin；不要把门户登记为业务 callback。
6. 先把项目标为 `maintenance`，完成真实浏览器登录回环后再切 `available`。

## 已知限制

- Recsys 仍然只有 `recsys` 一个安全租户。要开放第二个租户，必须先完成网关动态 org 策略、内部 token tenant claim、数据隔离和跨租户测试，不能只改前端配置。
- LangChain4j 匿名页无法调用需登录的 Casdoor organization API，allowlist 因此是构建期配置；Casdoor 与 allowlist 发生漂移时应更新配置并重建，不能临时放开任意输入。
- 已有 Casdoor SSO 会话时，提交租户后可能直接 callback，而不会再次显示密码页，这是正常 SSO。
- 当前工具环境没有可用浏览器绑定，未完成可视点击/截图；自动化交互与运行态证据已通过，生产前仍需一次真实账号回环。

## 回滚

- 最快回滚：将运行时 catalog 恢复到上一版或把单项目设为 `maintenance`；不需要重建门户镜像。
- Recsys：回滚 LoginPage/config/helper 前端构建即可；无后端或数据库迁移。
- Drools：将 `activity-frontend` 镜像回滚到上一 tag；console/decision/MySQL 不需要回滚。
- LangChain4j：回滚租户 helper、LoginView/store 接入和构建变量后重建前端；没有服务端或数据库迁移。更安全的应急做法是把项目标为 `maintenance`，不要恢复任意租户跳转。

## 交付方法影响

端到端交付流程要求把产品边界、技术安全条件、实现、代码审查、完整 QA、文档和 CI 一起收敛。因此本次不仅修改入口，还显式阻止了 Recsys 的“伪多租户”配置和 LangChain4j 的未知租户跳转，修复部署空值语义与 E2E 契约，并补上可重复的 catalog/tenant 回归门禁。
