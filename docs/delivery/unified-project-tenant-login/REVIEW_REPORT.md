# 统一项目租户登录 Review Report

## 结论

- Review verdict：Pass
- P0 未关闭：0
- P1 未关闭：0
- P2 未关闭：0（环境 QA 限制单列于 QA 报告，不是代码缺陷）

## 审查范围

- 门户本地 catalog、生产示例、目录契约测试与接入文档。
- Recsys OIDC 配置、租户纯函数、LoginPage、深链处理、组件测试和 ESLint 配置。
- Drools auth-config allowlist、LoginView 输入流程、portal auto 兼容分支、响应式视觉、真实 OIDC E2E 脚本与组件测试。
- LangChain4j 租户 allowlist、页面/store 双层校验、auto 兼容回归、构建参数、文档与 CI。
- 四仓测试、类型检查、构建、运行态端口和静态产物。

## 发现与处置

| ID | Severity | 发现 | 处置与证据 |
| --- | --- | --- | --- |
| R-01 | P0 | Recsys 网关固定 org=`recsys`，内部 token 无 tenant claim，数据层无多租户隔离证据；开放任意租户会造成安全假象 | 未实现动态多租户；`validateTenantSelection` 只接受配置的单租户，并校验 clientId org 后缀；测试覆盖错误租户、空配置和后缀漂移 |
| R-02 | P1 | 门户原目录含固定 tenant/clientId 与 `auto=1`，会跳过租户选择 | 本地与生产示例全部移除；17 项目录测试同时约束两份 catalog |
| R-03 | P1 | Drools 若把输入直接拼 clientId，可能绕过目标配置 | 只从后端 `/activity-marketing/auth-config.webClients` 精确查找 tenant，再取已配置 clientId；未知值不调用 `beginLogin` |
| R-04 | P1 | 深链参数可形成开放重定向 | 三项目继续使用站内单斜杠消毒；Recsys 正式 `returnTo` 新路径复用同一消毒函数并拒绝重复参数 |
| R-05 | P2 | Recsys `npm run lint` 会扫描本地 `.vite/deps`，产生依赖源码规则错误 | `eslint.config.js` 增加 `.vite` ignore；完整 lint 现为 0 error |
| R-06 | P2 | 直接删除旧 auto contract 会破坏外部书签 | 保留目标端受控 auto 解析和回归测试，仅官方 catalog/文档切换到租户选择入口 |
| R-07 | P0 | LangChain4j 原逻辑只校验 tenant 格式，`not-exists` 会被拼进 clientId 并跳 Casdoor | 新增 `VITE_CASDOOR_TENANTS`；LoginView 预检，auth store 二次强制校验；人工与 auto unknown 测试均证明 OIDC 零调用 |
| R-08 | P1 | Compose 若用 `${VAR:-acme}`，显式空 allowlist 会被默认值覆盖，无法按设计 fail-closed | 改用 `${VAR-acme}`；`docker compose config` 实测未声明时为 acme、显式空时保留空字符串 |
| R-09 | P1 | Drools 旧真实 OIDC E2E 仍点击已移除的 `login-{tenant}` 快捷按钮 | E2E 契约同步为填写 `#login-tenant` 并点击 `login-submit`；Node 语法检查通过 |

## 安全审查

- 门户不构造 authorize URL、不保存 token、不跨 origin 写 PKCE storage。
- Recsys 不根据用户输入动态构造 clientId；前端和网关双重固定 organization。
- Drools tenant/client 映射来源是后端 allowlist；clientId 仍作为 OAuth public metadata，不接收 secret。
- LangChain4j 只把 allowlisted organization 交给既有 clientId 派生函数；空值、格式非法、未知值和空 allowlist 均 fail-closed。allowlist 是公开部署元数据，不是授权凭据。
- 所有正式链接只携带目标站内深链，生产示例为 HTTPS。
- 没有数据库、SpiceDB、Casdoor 资源或密钥变更。

## 回归与可维护性

- 旧 auto-launch 聚焦测试全部继续通过。
- 新行为落在小型纯函数/目标登录组件，不修改业务 API 或权限模型。
- 文档明确区分“当前单租户确认”和“真实多租户”，避免运维误配置。
- CI 覆盖 portal/Recsys/Drools，并为 LangChain4j 前端补齐测试、类型检查和构建门禁。
- 生产依赖 audit 为 0；Docker 构建提示的 5 项告警位于开发依赖树，记录在 QA 非阻断项中。
