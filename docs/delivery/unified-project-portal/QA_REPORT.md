# QA Report

## Verdict

**conditional-pass** — 所有可在当前环境执行的单元、组件、类型、构建、脚本和 nginx 运行态检查均通过；真实 Casdoor 登录回环、可视化多 viewport 和完整 Docker multi-stage build 受外部环境限制，已明确列为发布前条件，未伪称通过。

## Environment

- Date/timezone: 2026-07-22, Asia/Taipei
- Host: macOS workspace `/Users/liruijun/personal/LLM`
- Local Node: 24.12.0；CI/Docker 目标 Node 22（portal 要求 `>=22.6`）
- Package managers: pnpm 9.15.9；目标项目既有 npm lock/install
- Docker runtime image available: `nginx:1.27-alpine`; unavailable locally: `node:22-alpine`
- Browser runtime discovery: no available browser instance (`[]`)

## Cases And Evidence

| Case | Expected | Actual / evidence | Verdict |
| --- | --- | --- | --- |
| Portal frozen install | lockfile 可在 CI 同等模式安装 | `pnpm install --frozen-lockfile --offline` success | pass |
| Catalog parser/security | 拒绝危险 URL/重复/未知 schema；安全项目可用 | `pnpm test:run`: 13 tests, 0 failures | pass |
| Portal type/build | TS 与生产 bundle 成功 | `pnpm typecheck && pnpm build`; 38 modules, build success | pass |
| LangChain4j full regression | auto/direct/redirect 与既有功能不回归 | 65 files, 544 tests pass；type-check/build pass | pass |
| Recsys full regression | OIDC/legacy/StrictMode 与既有功能不回归 | 8 files, 49 tests pass；typecheck/build pass | pass |
| Drools full regression | allowlist/direct/PKCE 与既有功能不回归 | 13 files, 58 tests pass；typecheck/build pass | pass |
| Drools post-repair | config error 与纵深清洗修复通过 | 3 files, 17 tests pass；typecheck/build pass | pass |
| Recsys scoped lint | 新改文件无 ESLint 错误 | `npx eslint` on four changed source/test files: exit 0 | pass |
| Runtime catalog swap | 同一镜像/静态 bundle 换域名不重建 | 同一 mounted `dist` 分别读本地和三生产示例；`cmp` reports `SAME_FINAL_BUNDLE` | pass |
| nginx health/cache/security | health 200，catalog no-store，asset immutable，安全头完整 | curl `/healthz`, `/index.html`, asset, catalog；均 200，headers 符合 | pass |
| Root launcher | 可独立启动/停止，支持端口覆盖 | `PORTAL_PORT=5275 ./dev.sh up --no-infra --no-backend --no-frontend -f`; HTTP 200；Ctrl-C clean stop | pass |
| Diff hygiene | 无 whitespace error | 四仓 `git diff --check` exit 0 | pass |
| Browser viewport/keyboard | 390/768/1440 无溢出，原生键盘交互可用 | browser runtime 无可用实例，无法生成视觉/交互证据 | blocked |
| Real OIDC loop | portal → target → Casdoor → callback | 缺三目标运行态生产等价配置/测试账号；Recsys/Drools catalog 保持 maintenance | blocked |
| Full Dockerfile build | Node build stage + nginx stage 成功 | 本机缺 `node:22-alpine`；未联网拉取。nginx runtime mount smoke 已通过 | blocked |

## Defects And Retests

1. 首轮 nginx smoke 暴露 index/assets 安全头继承缺失；修复后重新启动容器，index/assets/catalog 均含目标头。
2. 首轮 `dev.sh` 日志出现 HTTP `000000` 被误判 ready；修复 code 规范化后，脚本实际等待到 HTTP 200，并以 5275 覆盖端口复测。
3. 审查发现 catalog invalid-first duplicate 和 Drools config error/returnTo 纵深问题；均新增/更新测试后通过。

## Warnings

- Recsys 全量测试有既有 React `act(...)` warning 与 React Router v7 future warning，不影响 49 项通过，且不由本功能引入。
- LangChain4j build 有既有动态+静态 import chunk warning，不影响构建。
- pnpm 在本机 Node 24 输出其内部 `url.parse()` deprecation warning；CI 固定 Node 22，不影响 install/test/build。

## Release QA Conditions

1. 在测试环境填真实 HTTPS catalog 与明确 tenant/client，先保持 maintenance。
2. 分别完成三项目 portal → Casdoor → callback，并确认 callback 后到安全 return path。
3. 用真实浏览器复验 390/768/1440、Tab/Enter、搜索/分类、空态和维护态。
4. 观察首个 GitHub Actions `Project Portal CI` 成功，包括 Docker build，再将对应项目切为 available。
