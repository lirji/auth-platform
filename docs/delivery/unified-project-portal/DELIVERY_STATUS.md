# Delivery Status

## Goal

新建独立、公开、免登录的 `project-portal`，展示 LangChain4j、Recsys、Drools 等能力项目；点击后由目标项目自己的 SSO launch route 建立 PKCE 并跳 Casdoor。

## State

- Phase: Phase 9 — final delivery
- Status: complete (review pass; QA conditional-pass)
- Last updated: 2026-07-22 Asia/Taipei

## Completed

- 读取交付技能、artifact contract、相关仓库规则和现有交付状态。
- 检查 auth-platform、auth-console 与三个目标前端的路由、登录、OIDC、部署、构建、测试、分支和 worktree。
- 根据用户澄清撤销“复用 auth-console + 后端目录 API”方案。
- 完成修订版产品/UX/技术方案：独立静态门户、运行时公开 catalog、目标-owned SSO launch adapters。
- 复核三个目标当前行为：它们都会先停在自身登录页；若要一次点击后自动跳 Casdoor，必须由目标 origin 增加受控 auto-launch 分支，门户不能直接拼 authorize URL。
- 完成 13 条可观察 AC、文件级变更、测试、CI、发布和回滚设计。
- Gate A approved by user message “批准修订后的设计”; approved scope recorded in `DELIVERY_PLAN.md`.
- 完成独立 React/Vite/TS 公开门户、运行时 catalog parser、搜索/分类/状态、nginx/Docker 和 13 项测试。
- 完成 LangChain4j、Recsys、Drools 三套目标-owned portal auto-login adapters 与组件/安全回归。
- 三目标全量前端测试分别 544/49/58 项通过，类型检查和 production build 通过。
- 完成同一 bundle 两套 catalog 容器 smoke、安全/缓存头、`dev.sh` 独立启停/端口覆盖验证。
- 完成根/目标 README、公开接入指南、GitHub Actions、review/QA/delivery reports。
- 用户后续要求“把项目都接进去”；本地 catalog 已将三项目全部切为 available，并以 `8093/5275/5173` 消除 Recsys/Drools `8095` 冲突。

## Changed Files

- `project-portal/` — 新门户工程、catalog、tests、Docker/nginx 与工程文档。
- `.github/workflows/portal-ci.yml`、`dev.sh`、根 `README.md` — CI、一键启停与模块/端口说明。
- `docs/公开能力门户接入指南.md`、本目录五份交付工件 — 接入、设计、状态、审查、QA 和最终报告。
- `langchain4j-platform/capability-showcase-frontend` — portal launch adapter、redirect 清洗、tests、README。
- `recsys/console` — portal launch adapter、StrictMode/legacy tests、README。
- `drools-demo/frontend` 与根 README — allowlist launch adapter、纵深清洗、tests、接入说明。

逐文件最终变更与原有用户改动的边界见 `DELIVERY_REPORT.md` 和 `REVIEW_REPORT.md`。

## Verification Log

| Check | Result | Notes |
| --- | --- | --- |
| workspace/repository discovery | pass | 四个独立 Git 仓库；新门户规划归属 auth-platform |
| auth-console auth/route inspection | pass | viewer/admin-only，不适合作匿名门户 |
| LangChain4j route/LoginView/OIDC inspection | pass | 当前先到 `/login`，tenant+button 后才 `startOidcLogin` |
| Recsys RequireAuth/LoginPage/OIDC inspection | pass | 当前先到 `/login`，button 后才 `signInOidc` |
| Drools router/LoginView/auth client inspection | pass | 当前先到 `/ui/login`，选 client/button 后才 `beginLogin` |
| deployment URL/base inspection | pass | LangChain4j root/configurable base；Recsys root；Drools `/ui/` |
| worktree safety inspection | pass | LangChain4j/Drools 有用户部署改动；实施计划避免覆盖 |
| CI provider inspection | pass | auth-platform remote GitHub，当前无 workflow |
| `git diff --check` on planning artifacts | pass | 修订文档无 whitespace error |
| portal frozen install/test/typecheck/build | pass | 13 tests; 38 modules built |
| LangChain4j full frontend | pass | 65 files / 544 tests + type-check/build |
| Recsys full frontend | pass | 8 files / 49 tests + typecheck/build |
| Drools full frontend | pass | 13 files / 58 tests + typecheck/build |
| nginx runtime catalog A/B | pass | same final bundle, domains changed without rebuild |
| nginx health/cache/security headers | pass | 200; no-store/immutable; CSP/no-referrer/DENY/nosniff |
| root `dev.sh` portal lifecycle | pass | independent start, `PORTAL_PORT=5275`, clean Ctrl-C stop |
| browser viewport/keyboard | blocked | browser runtime returned no available instances |
| real three-target OIDC loop | blocked | requires deployed target configs and test identities |
| Docker multi-stage build | blocked locally | `node:22-alpine` missing; workflow will run in connected CI |

## Decisions And Deviations

- Material requirement correction: 门户由“受保护 auth-console 页面”改为“独立匿名静态前端”。
- Backend catalog、tenant/group visibility、auth-console guard/nav 改造全部移出范围。
- Runtime catalog 采用公开 JSON mount/ConfigMap；同一镜像跨环境。
- 为满足单击跳 Casdoor，范围新增三个目标前端的最小 launch adapter；PKCE 本体复用现有实现。
- 多租户无法由匿名门户猜测：有明确 public tenant/client 才自动跳，否则目标页选择。

## Blockers And Residual Risks

- No implementation blocker remains.
- Release conditions: production launch URLs/tenant/client/auth modes, real browser viewport/keyboard QA, real Casdoor callback loops, first remote portal CI including Docker build.
- Recsys/Drools current host `8095` conflict remains an operational input; guide requires overriding one port.
- Existing user deployment changes in LangChain4j and Drools were preserved.

## Next Action

Configure test-environment catalog as maintenance, run the release conditions in `QA_REPORT.md`, then enable projects one by one.
