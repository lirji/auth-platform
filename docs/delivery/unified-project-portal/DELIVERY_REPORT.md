# Delivery Report

## Outcome

已交付独立的公开免登录 `project-portal`，并为 LangChain4j、Recsys、Drools 增加目标-owned Casdoor 登录入口。门户只展示公开项目目录；最终交互是先进入目标项目租户页，再由目标项目校验租户、生成 PKCE/state 并跳 Casdoor。生产域名通过运行时 catalog 挂载，同一 portal 镜像无需重建。

交付状态：**implementation complete / QA conditional-pass / review pass**。

## Requirement Coverage

| AC | Coverage | Evidence / note |
| --- | --- | --- |
| AC-01 | conditional | 匿名静态/nginx 运行不依赖 Casdoor；浏览器网络证据待有浏览器环境复验 |
| AC-02 | conditional | 代码审查证明只 fetch 同源 catalog、无 token/storage/auth API；Network 面板待复验 |
| AC-03 | conditional | 页面实现名称/说明/能力/类别/状态/域名、搜索和分类；filter tests pass，视觉待复验 |
| AC-04 | pass | view model tests 证明 available 安全链接、maintenance/coming-soon 无链接 |
| AC-05 | pass | 同一 final bundle 挂两套 catalog，三生产域名变化且 `cmp` 相同 |
| AC-06 | pass | 13 项 parser/view model tests，危险 scheme/userinfo/重复/字段/schema fail-closed |
| AC-07 | conditional | 三目标聚焦组件测试证明调用既有 PKCE client；真实 Casdoor 回环待发布环境 |
| AC-08 | pass | Lang 合法 tenant auto，非法/缺失/direct 不触发 |
| AC-09 | pass | Recsys OIDC-only、legacy no-op、StrictMode exactly once |
| AC-10 | pass | Drools auth-config allowlist、auth off/unknown/direct no-op |
| AC-11 | pass | 最新全量验证为 LangChain4j 552、Recsys 54、Drools 62 tests，type checks、builds 通过 |
| AC-12 | conditional | CSS 有 1023/639 breakpoints、原生可聚焦控件与文字状态；浏览器 viewport/keyboard 待复验 |
| AC-13 | pass | Workflow 已含 frozen install/test/typecheck/build/Docker build；本地完整 Docker build 已通过，workflow 已发布到远程 |

## Delivered Changes

### auth-platform

- 新 React/Vite/TS 门户、响应式卡片、搜索/分类、loading/error/empty/maintenance 状态。
- 不可信 runtime catalog parser、安全 URL 投影、同源 no-store fetch 与 13 项测试。
- multi-stage Dockerfile、nginx health/cache/CSP/referrer/frame/MIME headers、运行时 volume 示例。
- `dev.sh` 默认加入 portal，可 `--no-portal`，支持独立启停与 `PORTAL_PORT` 覆盖；顺带修复健康检查误判。
- 根 README、完整接入指南、Gate/Review/QA/Delivery 工件。
- `.github/workflows/portal-ci.yml`：Node 22 + pnpm cache + frozen install + test + typecheck + build + Docker build。

### Target projects

- LangChain4j: `/login?source=portal&auto=1&tenant=...&redirect=...`；格式校验、站内 redirect、组件/纯函数/guard 回归。
- Recsys: `/login?source=portal&auto=1&returnTo=...`；OIDC-only、重复参数拒绝、StrictMode 单飞、legacy 回归。
- Drools: `/ui/login?source=portal&auto=1&clientId=...&returnTo=...`；后端 auth-config allowlist、配置错误态、PKCE client 纵深清洗。
- 三仓 README 均同步 launch contract 与聚焦测试命令。

## Verification Summary

- Portal: 17 tests, typecheck, production build, Docker image build — pass.
- LangChain4j: 552 tests, type-check, production build — pass（本次未提交该仓库）。
- Recsys: 54 tests, typecheck, production build — pass.
- Drools: 前端 62 tests、typecheck、production build，以及 Maven 115 tests（3 skipped）— pass.
- nginx same-bundle/two-catalog smoke、health/cache/security headers — pass.
- `dev.sh` bash syntax、independent launch, port override, clean stop — pass.
- Four repository `git diff --check` — pass.

详见 `QA_REPORT.md` 与 `REVIEW_REPORT.md`。

## Pipeline

新增 `Project Portal CI` GitHub Actions，只在 portal/workflow 变更时触发，也支持手动运行。它不访问生产、不使用业务 secret、不跨 checkout 其他三个独立仓库。workflow 已随 auth-platform 提交推送；本地等价命令与完整 multi-stage Docker build 均已通过。

## Plan Deviations

- 门户组件测试原计划使用 Vitest/Testing Library；离线包缓存缺测试依赖，为遵守不联网约束，改用 Node 22 内置 test runner 测 parser/view model，并以 Vite production build + nginx runtime smoke 补足。目标项目仍有真实组件测试。
- 本机无 browser runtime，未用其他工具冒充视觉证据；viewport/keyboard 明确转为发布条件。
- 初始验证时本机缺少 `node:22-alpine`；最终提交前已成功获取基础镜像并完成完整 multi-stage Docker build。
- 生产域名、Lang tenant、Drools client 和 Recsys/Drools auth 状态未擅自猜测；安全示例将后两项保持 maintenance。

## Release Notes

- 新功能：匿名能力门户可统一发现 AI、推荐与规则项目。
- 登录变化：正式门户入口先进入目标项目租户页；仅旧的显式 portal auto 参数继续走受控兼容分支，普通登录无行为变化。
- 运维变化：portal production port 建议 `8203`；catalog 独立挂载并 no-store，可不重建换域名/上下线项目。
- 回滚：单项目设 `maintenance`/`enabled=false`；门户回滚静态镜像；无数据库/schema migration。

## Remaining Release Inputs

- 各环境门户/三目标 HTTPS 域名与 base path。
- LangChain4j 是否有单一公开预选 tenant；Drools 允许的 `webClients` clientId。
- Casdoor 精确 callback/silent/logout URI 与测试账号。
- 首次 workflow 运行、真实浏览器 QA 和真实 OIDC 回环结果。

## Post-delivery Activation

用户后续明确要求“把项目都接进去”。本地 runtime catalog 已将 LangChain4j、Recsys、Drools 全部切为 `available`；最终本地入口分别使用 `8093`、`5275`、`8095/ui/login` 并进入各项目租户页。生产示例也已切为 available，部署时仍须用真实 HTTPS 域名和已登记的 Casdoor callback 替换示例地址。

## Remote Publication

2026-07-22 已将 auth-platform `66a54a8`、recsys `b590202d`、drools-demo `9fc8ab0` 分别通过 `codex/unified-tenant-login-20260722` 分支 fast-forward 合入并推送远程 `main`。LangChain4j 仓库不在本次用户指定的三仓提交范围内，因此其本地改动未随本次发布推送。
