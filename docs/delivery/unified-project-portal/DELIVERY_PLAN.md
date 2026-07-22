# 公开能力门户 Delivery Plan

## Requirement

新建一套独立前端项目，作为公开、免登录的能力门户。任何访问者无需 Casdoor 会话即可看到当前提供的 LangChain4j、Recsys、Drools 等项目和核心能力；点击某个项目后，进入该项目自己的登录发起入口，由目标项目执行 Authorization Code + PKCE 并跳转 Casdoor。门户本身不登录、不持有 token，也不参与目标项目业务授权。

目标行为：

```text
公开访问 project-portal
  → 无登录即可浏览/搜索能力项目
  → 点击“进入项目”
  → 目标项目的 SSO launch route 建立本项目 PKCE state/verifier
  → 跳 Casdoor 登录
  → Casdoor callback 回目标项目
  → 目标项目继续校验 audience/tenant/业务权限
```

这里复用的是 Casdoor 身份会话，不是跨项目复用 access token。若浏览器已有有效 Casdoor 会话，IdP 可能直接回调而不再次要求输入密码，这是正常 SSO 行为。

## Repository Evidence

- 当前工作区根目录不是 Git 仓库，`auth-platform`、`langchain4j-platform`、`recsys`、`drools-demo` 是四个独立仓库。新门户放在 `auth-platform/project-portal/`，拥有独立构建和镜像，但随统一身份平台仓库维护，避免再创建一个无版本控制归属的工作区根项目。
- `auth-console` 是需要 `authz-viewer/admin` 的权限管控台，现有 `ProtectedRoute` 会对普通用户返回 403，不符合“公开免登录门户”；因此不复用或改造成门户（`auth-console/src/auth/ProtectedRoute.tsx:15-61`）。
- auth-console 已有 React/Vite/TypeScript/Ant Design 视觉基线，可作为新门户的样式参考，但新门户不依赖 auth-console runtime、admin API 或 OIDC client（`auth-console/package.json`、`auth-console/src/theme/`）。
- LangChain4j 当前未登录访问保护路由只会转到自身 `/login?redirect=...`，用户仍需填写 tenant 并点击“用 Casdoor 登录”；真正的 PKCE 发起函数是目标项目的 `startOidcLogin`（`capability-showcase-frontend/src/router/index.ts:57-78`、`src/modules/auth/LoginView.vue`、`src/auth/oidc.ts:131-138`）。
- Recsys 当前未登录访问只会转到 `/login`，OIDC 页仍需点击按钮调用 `signInOidc`（`console/src/router.tsx:35-46`、`console/src/pages/LoginPage.tsx`、`console/src/hooks/useAuth.tsx:113-121`）。
- Drools 当前未登录访问会转到 `/ui/login?returnTo=...`，用户仍需选择 tenant/client 后点击，才由 `beginLogin` 跳 Casdoor（`frontend/src/router/index.ts:40-53`、`frontend/src/views/LoginView.vue`）。
- 因此，若要求“点击门户卡片后直接跳 Casdoor”，门户不能自己拼 Casdoor authorize URL：PKCE verifier 必须在目标 origin 中生成和保存。正确实现是给每个目标前端增加受控的 portal auto-launch 参数/路由，由目标项目调用既有 OIDC 方法。
- 三个项目生产域名和 base path 不同：LangChain4j base 可配置且本地 compose 是 `:8093`，Recsys 根路径本地常为 `:8095`，Drools 路径带 `/ui/` 且当前本地也默认占 `:8095`。门户 launch URL 必须在运行时配置，不能烘焙进 Vite bundle。
- `auth-platform` 当前 `main` 工作树在本次规划前干净、远端是 GitHub、无现有 workflow。LangChain4j 的 compose 和 Drools 的部署文件已有用户改动；实施必须保留，不重写这些部署文件。

## Feasibility

- Verdict: **conditional-go**
- Implementation is feasible without a new backend or database.
- Release conditions:
  - 部署方提供每个环境的浏览器可达 launch URL；生产只使用 HTTPS。
  - 每个目标应用启用自己的 Casdoor OIDC/auth 档，并登记精确 callback/logout URI。
  - 要实现零二次点击自动跳 Casdoor，运行时 catalog 必须为需要 tenant/client 的项目提供公开的 tenant/clientId 提示；它们是 OAuth public client 元数据，不得包含 secret。
  - 如果某项目存在多个 tenant 且无法预选，目标项目应显示自身 tenant 选择页，不能由公开门户猜测用户租户。
- Constraints:
  - 门户页面加载期间不访问 Casdoor、不读取登录状态、不调用 auth-platform-admin。
  - 门户 catalog 是公开信息，只能包含允许匿名用户看到的项目说明、能力标签、公开域名和 public client 元数据。
  - 门户不能直接生成目标应用的 authorize URL，不能传 token，不能跨 origin 写目标项目的 PKCE storage。
  - 入口可见不代表目标应用授权；所有真正权限继续由目标应用后端检查。
- Risks and mitigations:
  - 公开 catalog 泄露内部系统：上线 catalog 由部署配置审查；内部/未开放项目使用 `enabled=false`，不进入公开 JSON。
  - 恶意 URL/XSS：catalog parser 只接受 `https:`，或开发模式显式允许 loopback `http:`；禁止 userinfo、`javascript:`/`data:`；React 文本转义，图标使用受控本地 key。
  - portal 参数形成开放重定向：目标项目只接受站内 `returnTo`，沿用/补强现有 sanitize；tenant/clientId 必须匹配目标项目自身公开配置/allowlist。
  - 单击自动登录在多租户场景误选组织：只有 catalog 明确配置 tenant/clientId 时 auto-launch；否则停在目标项目的选择页。
  - 生产域名变化导致重构建：catalog 独立挂载为运行时 JSON，index/assets 与环境地址解耦。
  - 目标不可用拖垮门户：门户不探测/fetch 目标域名，项目故障只影响该链接。
  - Recsys 与 Drools 本地 `8095` 冲突：联合 QA 前为其中一个编排覆盖宿主端口，再写入本地 catalog。

## Product Design

- Actors:
  - 匿名访问者：无需登录，了解系统当前提供哪些能力。
  - 已有 Casdoor 用户：点击项目，在目标应用完成或复用 SSO。
  - 平台部署人员：维护各环境公开 catalog，无需重建门户镜像。
  - 目标项目负责人：维护本项目 SSO launch contract、callback 和业务授权。
- Primary flow:
  1. 访问门户根路径，直接看到项目与能力卡片。
  2. 搜索名称、描述、标签或按类别筛选。
  3. 点击 available 项目的“进入项目”。
  4. 浏览器打开目标项目 `launchUrl`；门户不生成/携带 token。
  5. 目标项目校验 `source=portal`、`auto=1`、tenant/clientId/returnTo，生成 PKCE 后跳 Casdoor；无法安全自动选择 tenant 时显示目标项目自己的选择页。
  6. 登录完成后回目标项目深链。
- Scope:
  - 新建独立 `project-portal` 前端、Docker/nginx、运行时 catalog、公共能力首页。
  - 项目搜索、分类、能力标签、状态、外链域名与响应式/无障碍状态。
  - LangChain4j、Recsys、Drools 三个目标前端增加最小 portal auto-launch 适配和测试。
  - 运行、接入、SSO、生产域名、发布和回滚文档。
  - portal 的测试/build/container CI，以及三个目标项目的聚焦回归验证。
- Out of scope:
  - 不改 auth-console 路由或权限；不新增 auth-platform 后端 API。
  - 不做应用目录后台、数据库、用户组过滤、点击审计或实时健康检查。
  - 不把目标项目嵌入 iframe/微前端，不代理其 API/静态资源。
  - 不在门户显示内部权限、用户、租户数据或 target secret。
  - 不承诺每次都显示 Casdoor密码页；已有 IdP 会话可直接 SSO 回调。
- Business rules:
  - `enabled=false` 不显示；`available` 可进入，`maintenance`/`coming-soon` 展示但禁用。
  - catalog 按 `order`、`name`、`id` 稳定排序；能力标签只描述公开可知能力。
  - `launchUrl` 指向目标应用控制的 login/launch route，不得直接指向 Casdoor authorize endpoint。
  - `autoLogin=true` 仅作为门户展示/检查元数据；真正的 auto-login 参数已经包含在目标 `launchUrl` 并由目标校验。
  - 公开门户不按用户隐藏项目；如未来需要按身份展示，另立需要登录的“个人工作台”，不污染当前匿名边界。
- Success signals:
  - 门户静态资源和 catalog 2xx、首屏无登录依赖。
  - 各项目 launch → Casdoor → callback 的成功率由目标应用和 Casdoor 日志观察。
  - 本期不采集个人点击；catalog 变更通过 Git/ConfigMap/部署审计追踪。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | 未登录、无 cookie、Casdoor 不可用时仍能加载门户并浏览 catalog | P0 | browser + network QA |
| AC-02 | 门户首屏不请求 Casdoor、auth-platform-admin 或任一目标 API，也不创建/读取 access token | P0 | network inspection + code review |
| AC-03 | 页面展示项目名称、公开说明、能力标签、类别、状态和目标域名，并支持搜索/分类筛选 | P0 | component tests + browser QA |
| AC-04 | available 使用安全真实链接；maintenance/coming-soon 不可打开且有文字状态 | P0 | DOM parameterized tests |
| AC-05 | catalog 在容器运行时挂载；替换生产域名无需重新执行 Vite build | P0 | same image + two catalog mounts smoke |
| AC-06 | 非法 scheme、userinfo、重复 id、缺失字段不会生成可点击链接；页面给出可恢复的配置错误态 | P0 | parser tests |
| AC-07 | 点击配置完整的项目后由目标 origin 建立 PKCE 并跳 Casdoor，门户 URL/token 不进入 authorize 参数 | P0 | target focused tests + local OIDC QA |
| AC-08 | LangChain4j 可按允许的 tenant 自动调用既有 `startOidcLogin`，非法/缺失 tenant 不自动跳 | P0 | Vue component tests |
| AC-09 | Recsys 在 OIDC 模式下可由受控 portal 参数自动调用 `signInOidc`，legacy 模式不误触发 | P0 | React tests |
| AC-10 | Drools 仅在 clientId 属于后端下发 `webClients` allowlist 时自动调用 `beginLogin`，否则保留选择页 | P0 | Vue component tests |
| AC-11 | 普通直接访问三个项目的既有登录流程保持不变，现有 redirect/returnTo 防开放重定向规则不回归 | P0 | existing + new regression tests |
| AC-12 | 390/768/1440px 无横向溢出，键盘可搜索、筛选和打开链接，状态不只依赖颜色 | P1 | viewport/keyboard QA |
| AC-13 | CI 在干净环境运行 portal install/test/build/container build；目标项目聚焦测试命令在文档和 QA 中有证据 | P1 | workflow + local commands |

## UI/UX Design

- Brand: `能力门户`；副标题：`统一查看并访问当前可用的 AI、推荐与规则能力。`
- 页面不展示登录按钮、头像或“退出登录”，避免让用户误以为门户本身建立身份会话。
- Layout:

```text
Public App Shell
├─ Header: 品牌 + “无需登录即可浏览”
├─ Hero: 标题、简介、搜索框
├─ Category filters
├─ Project grid
│  └─ ProjectCard
│     ├─ controlled local icon + name + status
│     ├─ public summary
│     ├─ capability chips
│     ├─ target host + “目标项目将要求统一登录”
│     └─ <a>进入项目 ↗
└─ Footer: “登录与权限由各目标项目及 Casdoor 管理”
```

- State matrix:

| State | Behavior | Recovery |
| --- | --- | --- |
| loading catalog | 卡片 skeleton，不出现登录提示 | fetch 完成 |
| success | 项目网格，按 catalog 稳定顺序 | 搜索/筛选 |
| empty | “当前尚未发布公开能力” | 部署方更新 catalog |
| filter empty | “没有匹配项目” | 清除筛选 |
| invalid item | 该项不生成链接；若有其它合法项则展示合法项并给非阻塞提示 | 修复 catalog |
| catalog fetch error | 明确“能力目录暂时无法加载”与重试按钮 | 重试，不跳登录 |
| maintenance/coming soon | 卡片可浏览但按钮禁用 | 等待状态更新 |

- Responsive: `<640px` 一列，`640-1023px` 两列，桌面三列；长域名/标签换行，不做 masonry。
- Accessibility: 真实 `<a>` 为主操作；新标签时 `target=_blank rel="noopener noreferrer"` 和可见 `↗`；搜索有 label；状态带文字；焦点可见；遵循 reduced-motion。
- Visual language: React + TypeScript + 轻量本地 CSS，复用 auth-console 的蓝色/中性色和圆角尺度；不引入远程图片、远程字体或运行时第三方 UI CDN。

## Technical Solution

### Chosen approach

新建独立纯静态 SPA `auth-platform/project-portal/`：

```text
Browser ──GET /, /assets/*──────────────▶ project-portal nginx
        └─GET /config/catalog.json─────▶ runtime-mounted public JSON

ProjectCard <a href="target launchUrl">
        └─▶ target origin login/launch route
                ├─ validate portal params + tenant/client allowlist
                ├─ create PKCE state/verifier in target sessionStorage
                └─ signinRedirect / beginLogin ──▶ Casdoor
```

门户没有后端依赖。运行时 JSON 解决生产域名不同；目标 launch route 解决 PKCE 必须由目标 origin 发起的问题。

### Alternatives rejected

| Alternative | Decision | Reason |
| --- | --- | --- |
| 复用 auth-console | rejected | 现有产品是权限管理台且强制 viewer/admin，不符合匿名公共门户。 |
| 门户调用公共后端目录 API | rejected for v1 | catalog 是公开低频部署数据，API/服务/鉴权只增加故障面。 |
| 门户直接拼 Casdoor authorize URL | rejected | 无法在目标 origin 保存 PKCE verifier，且容易错 client/tenant/redirect URI。 |
| 只链接目标受保护页，不改目标项目 | rejected for requested one-click behavior | 当前三个项目都会先停在自己的登录页，需要再次点击或选择。 |
| build-time `VITE_*` 地址 | rejected | 生产域名变化需要重构建，同一镜像不能跨环境。 |
| iframe/统一反代/micro-frontend | rejected | base path、cookie、CSP、发布节奏和技术栈差异使耦合与风险过高。 |
| DB/CRUD/service discovery/健康探测 | rejected | 当前需求无动态管理或私有过滤证据，并会引入迁移、SSRF和运维成本。 |

### Public runtime catalog contract

`GET /config/catalog.json`，`Cache-Control: no-store`：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-07-22T00:00:00Z",
  "projects": [
    {
      "id": "langchain4j",
      "name": "LangChain4j 能力平台",
      "summary": "对话、RAG、Agent 与多模态能力展示",
      "category": "AI 平台",
      "capabilities": ["LLM 对话", "混合 RAG", "Agent 编排", "多模态"],
      "tags": ["LangChain4j", "RAG", "Agent"],
      "icon": "ai",
      "status": "available",
      "launchUrl": "https://ai.example.com/login?source=portal&auto=1&tenant=acme&redirect=%2F",
      "openMode": "new-tab",
      "order": 10
    },
    {
      "id": "recsys",
      "name": "推荐系统控制台",
      "summary": "推荐、搜索广告、实验和离线评估",
      "category": "数据智能",
      "capabilities": ["多路召回", "排序重排", "广告竞价", "A/B 实验"],
      "icon": "recommendation",
      "status": "available",
      "launchUrl": "https://recsys.example.com/login?source=portal&auto=1&returnTo=%2Foverview",
      "openMode": "new-tab",
      "order": 20
    },
    {
      "id": "drools",
      "name": "规则引擎控制台",
      "summary": "规则建模、活动决策与 Drools 教学演示",
      "category": "规则与决策",
      "capabilities": ["活动规则", "决策服务", "Drools 18 Steps"],
      "icon": "rules",
      "status": "available",
      "launchUrl": "https://rules.example.com/ui/login?source=portal&auto=1&clientId=activity-acme-web-cid&returnTo=%2Fhome",
      "openMode": "new-tab",
      "order": 30
    }
  ]
}
```

Validation rules:

- `schemaVersion=1`；id 唯一且匹配 `[a-z0-9][a-z0-9-]{0,63}`。
- `name/summary/category/capabilities/tags` 有长度和数量上限，防异常配置拖垮布局。
- `status` 仅 `available|maintenance|coming-soon`；`openMode` 仅 `same-tab|new-tab`。
- available 必须有绝对 URL；生产只允许 HTTPS，开发开关只允许 loopback HTTP；禁止 userinfo。
- catalog 不含 secret、token、内部 service DNS、管理地址或个人数据。

### Target SSO launch contract

共同参数：

- `source=portal`：明确来源；缺失时完全保持当前登录页行为。
- `auto=1`：请求自动发起；只有目标校验所需 client/tenant 成功时生效。
- `returnTo`/`redirect`：仅接受站内单斜杠绝对路径，拒绝 `//`、scheme、反斜杠和控制字符。

Target-specific:

- LangChain4j: `/login?source=portal&auto=1&tenant=<org>&redirect=<internal-path>`；tenant 经过现有格式限制后交给 `startOidcLogin`。缺失/非法 tenant 时预填安全值或停留登录页，不自动跳。
- Recsys: `/login?source=portal&auto=1&returnTo=<internal-path>`；仅 `AUTH_MODE=oidc` 时一次性调用 `signInOidc`，React StrictMode 下必须有 ref/state 防双跳；legacy 模式保持原页。
- Drools: `/ui/login?source=portal&auto=1&clientId=<public-client>&returnTo=<internal-path>`；先加载 auth-config，只在 clientId 精确命中 `webClients` 且 auth enabled 时调用 `beginLogin`。
- 参数均为 public OAuth metadata；目标端绝不接受 client secret。

### Modules and anticipated file map

New portal in `auth-platform`:

- `project-portal/package.json`, `pnpm-lock.yaml`, `tsconfig*.json`, `vite.config.ts`, `index.html` — 独立 React/Vite/TS 工程。
- `project-portal/src/main.tsx`, `App.tsx`, `styles.css` — 公共应用壳和能力门户页面。
- `project-portal/src/catalog/types.ts`, `parseCatalog.ts`, `useCatalog.ts` — schema、URL校验、no-store fetch/retry。
- `project-portal/src/components/ProjectCard.tsx`, `SearchFilters.tsx`, `AsyncState.tsx`, `icons.tsx` — 卡片、筛选、状态和本地图标。
- `project-portal/src/**/*.test.ts(x)`, `vitest.config.ts`, `test/setup.ts` — parser、filter、链接、状态和无障碍测试。
- `project-portal/public/config/catalog.json` — 安全空目录/本地开发默认，不含生产域名。
- `project-portal/config/catalog.example.json` — 三项目示例。
- `project-portal/Dockerfile`, `nginx.conf` — node build → nginx；catalog 可被 volume/ConfigMap 覆盖；`/healthz`、SPA fallback、安全头与缓存策略。
- `project-portal/README.md` — 开发、构建、挂载配置、域名和排障。
- `auth-platform/dev.sh`, `README.md` — 可选 portal 起停和端口 `8203`，不改变现有 auth-console `8202`。
- `auth-platform/.github/workflows/portal-ci.yml` — Node 22/pnpm test/build + image build，不部署。

LangChain4j target:

- `capability-showcase-frontend/src/modules/auth/LoginView.vue` — 解析受控 portal 参数、一次性 auto-start。
- `capability-showcase-frontend/src/modules/auth/LoginView.test.ts` — 合法 tenant auto、缺失/非法不跳、direct login 回归。
- 复用 `src/router/index.ts::sanitizeRedirect` 与 `src/auth/oidc.ts::startOidcLogin`；除非测试证明现有 sanitize 不足，不新建第二套规则。
- `capability-showcase-frontend/README.md` 或相关 SSO 文档 — launch URL 约定。

Recsys target:

- `console/src/pages/LoginPage.tsx` — 解析 `source/auto/returnTo`，OIDC-only 单次 auto-start。
- `console/src/pages/__tests__/LoginPage.test.tsx` — StrictMode 单飞、legacy 不跳、非法 returnTo 回退。
- `console/src/auth/oidc.ts` 或独立纯函数文件 — 只在现有 sanitize 不可复用时补共享 returnTo 校验。
- `console/README.md` — launch URL 与 OIDC 部署要求。

Drools target:

- `frontend/src/views/LoginView.vue` — auth-config 完成后校验 allowlisted clientId 并一次性 `beginLogin`。
- `frontend/src/views/LoginView.test.ts`（若现有测试路径不同则按现有约定放置）— allowlist/缺失/非法/direct flow。
- 复用 `frontend/src/auth/authClient.ts` 和 store 的 `beginLogin`；不修改 PKCE 实现。
- `frontend/README.md` 或 `README.md` — `/ui/login` portal launch 约定。

Cross-repo delivery docs:

- `auth-platform/docs/公开能力门户接入指南.md` — catalog schema、三项目 launch URL、Casdoor callback、生产域名、端口冲突、发布/回滚。

### Security and reliability

- 门户是匿名静态站，不设置认证 cookie、不接收 callback、不存 token。
- `Referrer-Policy: no-referrer`，新标签链接 `noopener noreferrer`；CSP 限制自身资源和禁止 framing。
- runtime catalog 由 parser 降权为不可信输入；错误项不生成 href。
- portal 参数在目标项目二次校验，不能覆盖 issuer/client base/callback；clientId 必须来自目标 allowlist。
- PKCE verifier/state 始终由目标项目现有 OIDC 实现生成和存储。
- 门户不访问目标 URL，无 SSRF/跨域/CORS依赖；目标项目内部 API 继续按原同源/CORS配置。
- 无数据库、事务、缓存一致性或数据迁移。

### Compatibility

- auth-console、auth-platform-admin、SpiceDB schema 和现有管理权限不变。
- 三个目标登录页只有 `source=portal&auto=1` 时新增自动分支；普通访问和现有 guard 流程不变。
- auto-launch 失败必须停留在目标登录页并显示错误，不循环重定向。
- 旧门户镜像与新 catalog 通过 `schemaVersion` fail-closed；未知版本展示配置错误，不猜字段。

## Implementation Sequence

1. **Slice 1 — 独立公开门户（AC-01~06/12）**：工程、catalog parser、页面、状态、tests/build、nginx image；不接触任何登录代码。
2. **Slice 2 — LangChain4j launch adapter（AC-07/08/11）**：portal 参数纯函数/组件分支、单测、typecheck/test/build。
3. **Slice 3 — Recsys launch adapter（AC-07/09/11）**：OIDC-only 单次 auto-start、returnTo 清洗、单测和现有前端检查。
4. **Slice 4 — Drools launch adapter（AC-07/10/11）**：auth-config allowlist 后 auto-start、单测和既有 typecheck/test。
5. **Slice 5 — 部署/文档/CI（AC-05/13）**：运行时配置示例、Docker/health/cache/security headers、接入指南、portal CI。
6. **Quality phases**：逐仓检查实际 diff与用户已有改动 → review/repair → localhost QA → 文档同步 → CI本地等价命令 → final reports。

## Verification Plan

| AC/Risk | Level | Command/case | Evidence |
| --- | --- | --- | --- |
| AC-01~06/12 | portal unit/build | `corepack pnpm --dir project-portal test:run`、`build` | parser/DOM tests + 0 failures |
| AC-05 | container | 同一镜像分别挂载 catalog A/B，curl `/`, `/config/catalog.json`, `/healthz` | 无重构建且 host 改变 |
| AC-02 | browser network | 无 cookie访问、Casdoor 停止，检查 Network | 只请求 portal origin 的静态资源/catalog |
| AC-07/08 | LangChain4j focused | `npm test -- LoginView.test.ts` + `npm run type-check` | auto/direct/invalid矩阵 |
| AC-07/09 | Recsys focused | `npm run test:run -- LoginPage` + `npm run typecheck` | OIDC/legacy/StrictMode矩阵 |
| AC-07/10 | Drools focused | `npm test -- LoginView` + `npm run typecheck` | allowlist/auth-disabled矩阵 |
| AC-11 | target regression | 各目标前端既有 tests/build 的最小充分集合 | 普通登录流程不变 |
| AC-07 E2E | localhost browser | portal → target launch → Casdoor → callback | 三项目观察记录；缺凭据项标 blocked，不伪称通过 |
| URL security | adversarial | javascript/data/http-prod/userinfo/duplicate/unknown schema | parser拒绝、无危险 href |
| CI | local parity/workflow | portal install/test/build/docker build | 本地通过；remote run 待 push 后确认 |

## Documentation Plan

- `project-portal/README.md`: 开发、构建、容器、运行时 catalog、缓存和安全头。
- `docs/公开能力门户接入指南.md`: 新项目接入步骤、公共信息边界、launch contract、PKCE 原因、callback/logout、HTTPS与base path。
- 三个目标 README: 各自 portal launch URL、auto 条件、回退到手动选择页、OIDC/auth 开关。
- 根 README/dev 文档: portal `8203`，与 auth-console `8202` 分工；公开门户不等于权限管控台。

## CI Plan

- `auth-platform` GitHub Actions 只覆盖新门户真实构建链：Node 22、corepack、pnpm cache、frozen install、test、build、Docker build。
- 不在 auth-platform workflow 跨 checkout 三个独立仓库；目标 adapter 使用各自仓库既有 CI/本地命令验证，若某仓库无匹配 CI，再在该仓按现有 provider 最小增补。
- 不部署、不访问生产、不放 secret、不做 Casdoor 真实登录。

## Rollout And Rollback

Rollout:

1. 构建/发布 portal 空 catalog 镜像，验证匿名访问、缓存和安全头。
2. 先发布三个目标 auto-launch adapter，但 catalog 项保持 maintenance；验证普通登录不回归。
3. 测试环境挂载真实 catalog，逐项目完成 portal→Casdoor→callback。
4. 生产配置 HTTPS launch URL、callback/logout URI和目标 OIDC/auth 开关；逐项从 maintenance 切 available。
5. 观察目标登录失败/循环/401/403，不把目标故障归因成门户故障。

Rollback:

- 单项目：catalog 设 maintenance 或 `enabled=false`，无需重建门户。
- portal UI：回滚静态镜像；catalog 独立保留或挂回空目录。
- target adapter：回滚目标前端；普通登录路径本来不依赖 portal。回滚期间对应项目设 maintenance。
- 无后端/DB/schema 变更，无数据回滚。

## Adversarial Design Review

- 用户澄清后已撤销原“auth-console + 后端目录 API”方案；它要求登录且产品语义不符。
- 已核实三个现有目标都不会在第一次跨站点击后自动跳 Casdoor，只会到自身登录页；要满足单击跳转必须增加目标-owned launch adapter。
- 门户不能直接跳 Casdoor authorize URL，因为目标 origin 没有 PKCE verifier；这是安全/协议限制，不是实现偏好。
- 多租户无法由匿名门户推断。catalog 只有在部署方明确选择公开 tenant/client 时才能零点击；否则目标页必须让用户选择。
- runtime JSON 优于 build-time env：公开低频数据无需后端，并真正支持同一镜像跨环境。
- 已排除健康探测和远程图标，避免 SSRF、慢依赖、跟踪和 XSS面。

## Assumptions And Open Decisions

- Assumption A1: 新前端项目名为 `project-portal`，作为 auth-platform 内独立 package/image；不与 auth-console 合并。
- Assumption A2: 门户展示内容允许匿名访问，不包含内部敏感项目或用户相关可见性规则。
- Assumption A3: “点击后跳 Casdoor”按目标项目发起 PKCE理解；若已有 Casdoor 会话，可能无密码页直接回调。
- Assumption A4: 对可自动选择的部署，tenant/clientId 可作为 public OAuth metadata 出现在 launch URL；secret 永不出现。
- Open O1 (rollout input): 三个项目各环境最终公网/内网浏览器域名、base path和默认 returnTo。
- Open O2 (rollout input): LangChain4j 和 Drools 生产是单一预选 tenant/client，还是必须让用户在目标登录页选择。
- Open O3 (external validation): Recsys生产 OIDC 与 Drools auth 档的启用状态；未确认时 catalog 必须 maintenance。

## Approval

- Status: **approved**
- Approved scope: revised public-portal plan, including independent `project-portal`, runtime public catalog, and the three target-owned portal auto-login adapters
- Evidence: user message on 2026-07-22 — “批准修订后的设计”
