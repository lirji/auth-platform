# 统一项目租户登录 Delivery Plan

## 决策与批准

- 决策日期：2026-07-22
- 可行性：conditional-go
- 用户已在修订设计后明确回复“批准修订后的设计”，并以“开始执行吧”授权实施。
- 选定方案：统一门户只负责进入目标项目登录页；目标项目收集并校验租户后，使用目标 origin 建立 OIDC Authorization Code + PKCE，再跳转 Casdoor。

```text
匿名统一门户
  → 目标项目 /login（不携带固定 tenant/client，也不 auto）
  → 目标项目租户选择/确认
  → 目标项目生成 PKCE state/verifier
  → Casdoor 登录
  → 目标项目 callback
  → 目标后端继续校验 audience、organization 与业务权限
```

## 目标与非目标

目标：

- LangChain4j、Recsys、Drools 从统一门户进入时都先停留在自己的租户选择页。
- 门户 catalog 不替用户预选租户，也不暴露固定 public clientId。
- LangChain4j 继续使用现有租户输入；Drools 根据后端 `webClients` 提供 allowlist 租户输入；Recsys 只允许当前安全租户 `recsys`。
- 三个目标都由自己的 origin 创建 PKCE，不由门户拼 Casdoor authorize URL。
- 生产域名仍由运行时 catalog 配置，同一门户镜像可跨环境使用。

非目标：

- 本期不把 Recsys 改造成真实多租户。其网关仍固定校验 organization=`recsys`，内部 token 和数据层也不新增 tenant 隔离。
- 不删除既有受控 `source=portal&auto=1` 兼容分支，避免破坏已有书签或外部集成；官方 catalog 和文档不再使用它。
- 不改 Casdoor 用户、组织、应用或回调白名单，不传递 token/client secret。
- 不改业务 API、数据模型或后端授权规则。

## 路由与页面流

| 项目 | 门户正式入口 | 租户页行为 | Casdoor 发起条件 |
| --- | --- | --- | --- |
| LangChain4j | `/login?redirect=%2F` | 输入 organization，并显示部署 allowlist | 格式合法且精确命中 `VITE_CASDOOR_TENANTS`；页面/store 双层校验 |
| Recsys | `/login?returnTo=%2Foverview` | 输入/确认 `recsys` | OIDC 模式、输入精确匹配配置租户、clientId 与租户后缀一致 |
| Drools | `/ui/login?returnTo=%2Fhome` | 输入 `acme`/`beta` 等后端下发租户 | tenant 精确命中 `/auth-config.webClients` allowlist |

登录成功后的深链只接受站内单斜杠绝对路径，拒绝 scheme、`//`、反斜杠、控制字符和重复控制参数。

## 组件树与交互状态

```text
project-portal ProjectCard
└─ target login route
   ├─ LangChain4j LoginView
   │  ├─ tenant input
   │  └─ Casdoor submit
   ├─ Recsys LoginPage / OIDC panel
   │  ├─ tenant input (allowed: configured organization)
   │  ├─ inline validation
   │  └─ Casdoor submit
   └─ Drools LoginView
      ├─ auth-config loader
      ├─ tenant input + available tenant hints
      ├─ allowlist validation
      └─ Casdoor submit
```

状态：初始、输入中、非法/未知租户、认证配置加载失败、跳转中、OIDC 发起失败。跳转中禁止重复提交；错误可修改租户后重试；Casdoor 不可用不会让门户本身失效。

## 配置与契约

- 门户：`public/config/catalog.json` 和 `config/catalog.example.json` 只放浏览器可达的目标登录 URL；生产必须 HTTPS。
- Recsys：新增 `VITE_CASDOOR_ORGANIZATION`，默认 `recsys`。它必须与网关 `CASDOOR_ORG` 及 `VITE_CASDOOR_CLIENT_ID` 的 `-org-<organization>` 后缀一致。
- Drools：继续以目标后端 `/activity-marketing/auth-config` 的 `webClients[] = {tenant, clientId}` 为唯一 allowlist，不接受任意 clientId。
- LangChain4j：继续使用现有 `<base-client-id>-org-<tenant>` 规则，但只允许部署 allowlist 中已开通的 tenant；匿名页不依赖需登录的 Casdoor 组织 API。

## 文件级变更

- `auth-platform/project-portal/public/config/catalog.json`：移除 `auto`、固定 tenant/clientId。
- `auth-platform/project-portal/config/catalog.example.json`：生产示例同步改为租户选择入口。
- `auth-platform/project-portal/src/catalog/defaultCatalog.test.ts`、`package.json`：增加默认目录契约回归。
- `auth-platform/project-portal/README.md`、`docs/公开能力门户接入指南.md`：改为选择页优先的正式接入说明。
- `recsys/console/src/config/auth.ts`、`.env.example`：声明固定 organization。
- `recsys/console/eslint.config.js`：忽略 Vite 依赖预构建缓存，保证本地完整 lint 可重复执行。
- `recsys/console/src/auth/tenantSelection.ts` 及测试：单租户与 clientId 一致性校验。
- `recsys/console/src/pages/LoginPage.tsx` 及测试：OIDC 面板增加租户输入和安全 returnTo。
- `recsys/console/README.md`：说明 Recsys 当前仍是单租户安全边界。
- `drools-demo/frontend/src/views/LoginView.vue` 及测试：改为 tenant 输入并使用后端 allowlist 映射 clientId。
- `drools-demo/README.md`：门户正式入口与兼容 auto 分支分开说明。
- `langchain4j-platform/capability-showcase-frontend/README.md`：正式入口改为人工选择；保留兼容 contract 说明。
- `langchain4j-platform/.github/workflows/capability-showcase-frontend-ci.yml`：补齐测试、类型检查和构建门禁。

## 实施顺序

1. 固化本计划与验收标准。
2. 先写门户目录契约测试，再修改本地/生产示例入口。
3. 为 Recsys 写租户纯函数测试，再接入 OIDC 页面。
4. 将 Drools 登录页改为 tenant 输入，继续依赖后端 allowlist。
5. 同步三项目和统一门户文档、CI 命令。
6. 分仓执行单测、类型检查、构建和本地浏览器 QA。
7. 完成代码审查、QA 报告、交付报告和回滚说明。

## 验收标准

| ID | 可观察行为 | 验证 |
| --- | --- | --- |
| AC-01 | 门户三条正式 launch URL 均不含 `auto`、`tenant`、`clientId` | catalog contract test |
| AC-02 | 点击 LangChain4j 后停在租户输入页，未提交前不跳 Casdoor | browser QA + existing component test |
| AC-03 | 点击 Recsys 后显示租户输入；非 `recsys` 被阻止，`recsys` 才调用 `signInOidc` | React test + browser QA |
| AC-04 | Recsys clientId 与 organization 配置不一致时 fail-closed | pure function test |
| AC-05 | 点击 Drools 后显示租户输入；未知租户不调用 `beginLogin`，allowlist 租户映射正确 clientId | Vue test + browser QA |
| AC-06 | 三项目 Casdoor 发起仍由目标 origin 创建 PKCE；门户不请求 Casdoor/目标 API | code review + browser network QA |
| AC-07 | 安全深链可恢复，危险 returnTo/redirect 仍被拒绝 | focused tests |
| AC-08 | 既有受控 auto-launch 回归测试继续通过 | existing focused tests |
| AC-09 | 门户、Recsys、Drools、LangChain4j 聚焦测试/类型检查/构建通过 | CI-equivalent local commands |
| AC-10 | Recsys 文档明确当前不是多租户，不能配置任意 organization | docs review |

## 风险与回滚

- 风险：用户输入不存在的租户。缓解：Lang 构建期 allowlist + 双层校验，Drools 后端 allowlist，Recsys 精确单值校验。
- 风险：Recsys 配置漂移。缓解：前端提交前校验 clientId 的 org 后缀；网关继续固定 organization/audience 校验。
- 风险：多一步操作降低直达速度。接受该取舍，以避免匿名门户错误预选租户；已有 Casdoor 会话仍可在提交后直接 SSO 回调。
- 风险：旧外部链接依赖 auto。缓解：保留兼容解析和测试，只修改官方目录。
- 回滚：只需把运行时 catalog 恢复到上一版本即可恢复旧入口；Recsys/Drools UI 可独立回滚，不涉及数据库、后端 API 或不可逆迁移。

## Phase 2：未知租户硬化与 Drools 登录页视觉统一

### 用户反馈与批准

- 用户反馈：输入一个不存在但格式合法的租户时仍然会跳转；期望在目标登录页直接阻止。
- 用户要求：活动引擎控制台登录页面与 LangChain4j、Recsys 的登录样式对齐。
- 批准证据：用户明确提出以上两项作为当前交付的下一个问题，允许连续执行。

### 根因与可行性

- Recsys 已通过 `validateTenantSelection` 精确限制为配置租户 `recsys`。
- Drools 已把 tenant 精确映射到后端 `/activity-marketing/auth-config.webClients`，未知租户不会调用 `beginLogin`。
- LangChain4j 只用正则校验 tenant 的字符串格式，随后直接构造 `<base>-org-<tenant>`；因此 `not-exists` 这类合法格式会进入 Casdoor，直到 IdP 才失败。
- Casdoor 的组织列表 API 登录前不可匿名读取，前端不能把它作为预登录动态验证源。本阶段采用部署可控的显式 allowlist，默认只开放已经用于门户和本地 OIDC 的 `acme`；新租户必须先在 Casdoor 开通，再更新 `VITE_CASDOOR_TENANTS` 并重建前端。
- 可行性结论：go。无后端、数据库或 Casdoor schema 变更。

### Phase 2 产品与安全规则

1. 空租户、格式非法租户、未在 allowlist 的租户都必须在目标页显示错误，且 OIDC 方法调用次数为 0。
2. LangChain4j 页面层给即时人话错误；auth store 再做一次强制校验，防未来其它调用点绕过页面。
3. 旧 portal auto 链接也必须经过相同 allowlist；未知 tenant 停留登录页。
4. allowlist 是 public deployment metadata，不包含 secret；显式配置为空时 fail-closed，不回退任意租户。
5. Drools 继续以服务端 webClients 为唯一真相源，不改成纯前端列表。

### Phase 2 UI/UX

Drools 登录页沿用本项目设计 token，但采用另外两个项目已经建立的登录结构：全屏柔和渐变/光晕、桌面左右双栏玻璃卡片、左侧品牌能力说明、右侧统一身份登录表单、全宽渐变主按钮、内联错误态和底部安全说明。

```text
LoginPage
├─ Aurora background
└─ Glass card
   ├─ Brand panel（桌面）
   │  ├─ 活动引擎控制台
   │  └─ 活动规则 / 决策服务 / Drools 18 Steps
   └─ Form panel
      ├─ Compact brand（窄屏）
      ├─ 统一身份登录
      ├─ tenant input + allowlist chips
      ├─ config/loading/form error
      └─ full-width Casdoor button
```

- `<760px` 隐藏左侧品牌区，保留紧凑品牌头；卡片单栏、无横向溢出。
- 加载 auth-config 时显示加载提示并禁用表单；配置失败与租户校验错误分离，输入不会误清配置故障。
- input 有显式 label、`aria-describedby`；错误使用 `role=alert`；按钮有 loading/disabled 状态；遵循全局 reduced-motion。

### Phase 2 技术变更

- LangChain4j：
  - `src/auth/tenantSelection.ts` 与测试：解析/去重 allowlist、精确验证和专用错误。
  - `src/config.ts`、`env.d.ts`：导出 `CASDOOR_TENANTS`，读取 `VITE_CASDOOR_TENANTS`。
  - `src/stores/auth.ts` 与 OIDC store 测试：调用 OIDC 前强制验证。
  - `src/modules/auth/LoginView.vue` 与测试：显示可用租户，人工/portal auto 未命中时不跳。
  - `.env.example`、`Dockerfile`、`deploy/docker-compose.yml`、README：补充 allowlist 配置和开租顺序。
- Drools：
  - `frontend/src/views/LoginView.vue`：重构模板、状态拆分与 scoped CSS，不改变 auth-config/PKCE contract。
  - `frontend/src/views/LoginView.test.ts`：覆盖 loading、配置错误、未知租户、allowlist 租户和关键视觉结构。
- 统一交付文档：更新状态、审查、QA、最终报告。

### Phase 2 验收标准

| ID | 可观察行为 | Priority | Verification |
| --- | --- | --- | --- |
| AC-11 | LangChain4j 输入格式合法但未开放的租户时不调用 OIDC，并显示“租户不存在或未开放” | P0 | helper/store/component tests |
| AC-12 | LangChain4j portal auto 未知租户同样不跳；allowlisted `acme` 兼容通过 | P0 | component tests |
| AC-13 | `VITE_CASDOOR_TENANTS` 能解析、去重、过滤非法项；显式空列表 fail-closed | P0 | pure function tests + build config inspection |
| AC-14 | Drools 未知租户仍不调用 `beginLogin`；配置失败与表单错误可独立恢复 | P0 | component tests |
| AC-15 | Drools 登录页具备双栏品牌卡、紧凑移动头、租户输入、可用租户、主按钮和错误区域 | P1 | DOM structure tests + production asset inspection |
| AC-16 | LangChain4j/Drools 完整测试、类型检查、生产构建通过，Drools 本地镜像更新 | P0 | CI-equivalent commands + runtime HTTP/static checks |

### Phase 2 回滚

- LangChain4j 回滚 allowlist 相关前端文件和构建参数即可；没有服务端数据迁移。
- Drools 只回滚 `activity-frontend` 镜像；console、decision、MySQL 无需回滚。
- 如果误漏合法租户，先把项目 catalog 设为 maintenance，再把 tenant 加入部署变量并重建；不要临时恢复“任意字符串都跳转”。
