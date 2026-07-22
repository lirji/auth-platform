# 统一项目租户登录 QA Report

## 结论

状态：Pass with documented browser-environment limitation。

代码、组件交互、完整前端回归、类型、lint、生产构建、容器构建和本地运行态检查均通过。当前会话没有可连接的浏览器实例，因此没有产出可视点击或截图证据；发布前需补一次真实浏览器登录回环。

## 自动化结果

| 工程 | 命令/范围 | 结果 |
| --- | --- | --- |
| Portal | `pnpm test:run` | 17/17 passed；同时检查本地与生产示例无 auto/tenant/clientId |
| Portal | `pnpm typecheck && pnpm build` | passed |
| Portal | `docker build -t project-portal:tenant-login-qa .` | passed |
| Recsys | `npm run test:run` | 9 files，54/54 passed |
| Recsys | `npm run typecheck` | passed |
| Recsys | `npm run lint` | passed，0 error；17 个既有 warning |
| Recsys | 变更文件 Prettier + `npm run build` | passed |
| Drools | `npm test -- --run` | 13 files，62/62 passed |
| Drools | `npm run typecheck && npm run build` | passed |
| Drools OIDC E2E contract | `node --check frontend/e2e/e2e-oidc-v2.mjs` | passed；脚本已改用 tenant input + submit，真实浏览器回环受当前环境限制未重跑 |
| LangChain4j | `npm test -- --run` | 66 files，552/552 passed |
| LangChain4j | `npm run type-check && npm run build` | passed；保留一个既有动态/静态 import chunk warning |
| LangChain4j production dependencies | `npm audit --omit=dev --json` | 0 vulnerabilities |
| LangChain4j CI | Ruby YAML parse | passed |

总计：Portal 17 项 + 三个目标前端 668 项 = 685 项，全部通过。

## 运行态检查

- `http://localhost:5274/config/catalog.json` 返回三条正式入口：
  - `http://localhost:8093/login?redirect=%2F`
  - `http://localhost:5275/login?returnTo=%2Foverview`
  - `http://localhost:8095/ui/login?returnTo=%2Fhome`
- 三条 URL 都是 HTTP 200，Casdoor `http://localhost:8000/` 是 HTTP 200。
- Recsys Vite 运行配置为 `VITE_AUTH_MODE=oidc`；运行模块包含 `oidc-tenant`、单租户提示和 `validateTenantSelection`。
- LangChain4j `8093` 容器已重建；运行 JS 明确包含“租户不存在或未开放”“当前可用租户”“当前未配置可用租户”三种状态。
- Drools `/activity-marketing/auth-config` 返回 `authEnabled=true` 与 acme/beta 两个 web client；重建后的 `LoginView` 静态 chunk 包含双栏品牌结构、tenant input、配置加载、可用租户和未知租户错误态。
- Drools `gateway` 容器以新 `activity-frontend:latest` 重建并为 healthy。

## 验收矩阵

| AC | 结果 | 证据 |
| --- | --- | --- |
| AC-01 | Pass | 两份 catalog contract test |
| AC-02 | Pass | Lang LoginView 回归 + 运行 URL/静态产物 |
| AC-03 | Pass | Recsys 组件测试：错误租户零调用、recsys 调用且保留深链 |
| AC-04 | Pass | tenantSelection 纯函数配置漂移测试 |
| AC-05 | Pass | Drools 组件测试：unknown 拒绝、beta → 精确 clientId |
| AC-06 | Pass | 代码审查确认三个项目调用各自 OIDC/PKCE client；门户无 OIDC 依赖 |
| AC-07 | Pass | portalLaunch/guard/组件安全回归 |
| AC-08 | Pass | 三项目既有 auto contract 测试继续通过 |
| AC-09 | Pass | 完整测试、类型检查、构建结果见上表 |
| AC-10 | Pass | Recsys README/.env/交付文档明确单租户边界 |
| AC-11 | Pass | Lang helper/store/LoginView 测试：`not-exists` 显示错误且底层 OIDC/页面调用均为 0 |
| AC-12 | Pass | Lang portal auto unknown 零调用；allowlisted `acme` 仍单次调用 |
| AC-13 | Pass | allowlist 解析测试 + 空列表组件禁用 + Compose 显式空值 config 检查 |
| AC-14 | Pass | Drools unknown 零调用；config loading/failure 与 form error 分离 |
| AC-15 | Pass | Drools DOM 结构测试 + 生产 chunk/CSS 构建检查 |
| AC-16 | Pass | Lang/Drools 全量测试、类型、构建、镜像重建和 HTTP/static 检查 |

## 非阻断警告

- Recsys 完整测试输出既有 React `act(...)` 和 React Router v7 future flag warning，不影响断言结果。
- Recsys lint 仍有 17 个既有 warning，但为 0 error，且本次变更文件定向 lint/Prettier 通过。
- LangChain4j 构建有既有 OIDC 模块同时静态/动态 import 的 chunk warning，构建成功。
- LangChain4j Docker 构建的完整开发依赖树报告 5 项 audit 告警；生产依赖专门检查为 0，未使用 `npm audit fix --force` 做破坏性升级。
- 可视浏览器 QA 未执行；这是当前工具环境限制，不是应用故障。生产放量前需手工验证 portal → tenant → Casdoor → callback。
