# Review Report

## Verdict

**pass** — 已按最终实际 diff 完成产品边界、认证协议、安全、错误处理、部署和用户既有改动保护审查。发现的问题均已在批准范围内修复并复测；没有遗留 P0/P1 代码缺陷。

## Scope

- `auth-platform`: 新 `project-portal`、运行时 catalog、nginx/Docker、`dev.sh`、文档、CI。
- `langchain4j-platform`: 登录页 portal adapter、站内 redirect 清洗、测试与文档。
- `recsys`: OIDC-only portal adapter、StrictMode 单飞、测试与文档。
- `drools-demo`: auth-config client allowlist adapter、PKCE returnTo 纵深清洗、测试与文档。
- 审查时明确排除并保留用户在 LangChain4j/Drools 的既有部署改动。

## Confirmed Findings And Repairs

| ID | Severity | Finding | Repair | Retest |
| --- | --- | --- | --- | --- |
| R-01 | P1 | nginx 子 location 添加缓存头后不会继承 server 级安全头；初次 curl 证明 `index.html` 缺 CSP/防 framing | 在 catalog/assets/index location 显式补齐安全头，assets 去掉重复 Cache-Control 来源（`project-portal/nginx.conf:19`） | nginx 容器对 index/assets/catalog 复验，CSP、DENY、no-referrer、nosniff 均存在 |
| R-02 | P1 | `dev.sh` 原 `curl -w 000 || echo 000` 在连接失败时形成 `000000`，会被误判为已就绪 | 只接受严格三位 HTTP code（`dev.sh:95`、`:221`） | 真实等待一轮后 HTTP 200；自定义 `PORTAL_PORT=5275` 启停通过 |
| R-03 | P2 | catalog 中前一个字段无效但 id 合法的项目会提前占用 id，遮蔽后续合法项 | 仅在项目全部校验成功后写入 `seenIds`（`parseCatalog.ts:68`、`:110`） | 新增回归用例，portal 13/13 通过 |
| R-04 | P1 | Drools UI 已清洗 returnTo，但 PKCE client 本体只做部分字符判断，未来调用方可能绕过 UI | `authClient.login` 复用完整 `sanitizeInternalPath`，非法值回退 `/home`（`authClient.ts:119`） | authClient + portal + LoginView 聚焦 17/17 通过 |
| R-05 | P2 | Drools `/auth-config` 加载失败时 onMounted rejection 没有用户可见错误 | 捕获并显示错误，停止 auto-login（`LoginView.vue:15`） | 新组件用例验证错误态和零 beginLogin |
| R-06 | P2 | Recsys 重复 query 参数可能产生代理/浏览器解析歧义；每次渲染还会重建 launch object | 拒绝重复 `source/auto/returnTo`，以 `useMemo` 稳定解析结果（`portalLaunch.ts:16`、`LoginPage.tsx:267`） | pure/component 13/13，含 StrictMode 与 legacy |

## Security Review

- 门户只 fetch 同源 `/config/catalog.json`；源码无 Casdoor/auth API、cookie、storage 或 token 逻辑。
- Catalog URL 只接受 HTTPS；显式本地开关仅放行 loopback HTTP；拒绝 userinfo、危险 scheme、未知 schema、重复 id。
- 新标签链接含 `noopener noreferrer`，nginx 发送 `Referrer-Policy: no-referrer`。
- 三个目标都要求显式 `source=portal&auto=1`，且 return path 只接受站内单斜杠绝对路径。
- PKCE/state/issuer/callback 仍由目标项目现有 OIDC 实现控制；门户没有拼接 authorize endpoint。
- Drools clientId 必须精确命中后端 `webClients`；LangChain4j tenant 受现有 Shared Application 命名格式约束，最终 audience/owner 仍由 Casdoor 和 edge 校验。
- Catalog 示例只有 public OAuth metadata，没有 secret/token/个人数据。

## Rejected Suspicions

- “门户需要 CORS 才能跳不同生产域名”：否，使用浏览器顶层导航，不发跨域 fetch。
- “可直接从门户跳 Casdoor”：否，这会让目标 origin 缺少 PKCE verifier；当前实现正确地先进入目标登录路由。
- “maintenance 项仍可打开”：否，view model 对非 available 返回无链接动作。
- “React StrictMode 会双发 OIDC”：组件测试证明 ref 单飞为一次。
- “本次覆盖了用户部署改动”：否；LangChain4j `deploy/docker-compose.yml` 与 Drools 既有部署/README 改动均保留，未据此重写或回滚。

## Residual Risks

- 生产 tenant/client、域名、base path 和 Casdoor redirect URI 是发布输入，不能从代码安全推断。
- 真实 portal → 三目标 → Casdoor → callback 回环尚需在具备目标服务、生产等价配置和测试账号的环境执行。
- 本机没有可用浏览器实例，响应式/键盘只能保留为发布前人工验收项。
- 本机缺 `node:22-alpine` 且本轮不联网拉镜像；Dockerfile 的完整 build 留给新增的 GitHub Actions 或有镜像缓存的环境。
