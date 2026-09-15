# 本地 Casdoor 账号（能力门户）

本机 Docker 卷重建后，用 `deploy/portal-casdoor-restore.sh` 按能力门户中需要 Casdoor 的项目重新开通身份。脚本幂等。Casdoor 控制台：`http://localhost:8000`，管理员 `admin` / `123`。

共享应用：`rag-shared`，base client_id `ragshared0client00000001`。浏览器登录组织名（不是货主业务租户）。

| 门户项目 | 登录组织 | 业务租户 / 隔离键 | 账号 | 口令 | client_id |
|---|---|---|---|---|---|
| 统一权限平台 | `built-in` | 需组 `authz-admin` | `admin` | `123` | `auth-console` |
| LangChain4j | `acme` | org=`acme` | `alice` | `Alice@12345` | `ragshared0client00000001`（登录页再填组织） |
| LangChain4j | `globex` | org=`globex` | `bob` | `Bob@12345` | 同上 |
| 推荐系统 | `recsys` | org=`recsys` | `radmin`（组 admins） | `Radmin@12345` | `ragshared0client00000001-org-recsys` |
| 推荐系统 | `recsys` | org=`recsys` | `rowner1`（组 advertisers） | `Rowner1@12345` | 同上 |
| 规则引擎 | `acme` / `beta` | token `aud` 反解租户 | `act-alice` / `act-bob` | `act-alice-dev-pass-01` / `act-bob-dev-pass-02` | `activity-acme-web-cid` / `activity-beta-web-cid` |
| 风控 | `risk-platform` | org=`risk-platform` | `risk-e2e-admin` | `Risk@12345` | `ragshared0client00000001-org-risk-platform` |
| 流程中台 | `workflow-platform` | JWT `tenant_id`：管理员 `benefit-center`，药师 `his` | `workflow-admin` / `workflow-pharmacist` | `Workflow@12345` / `Pharmacist@12345` | `workflow-console-app-001` |
| 对账 | `recon-platform` | org=`recon-platform` | `recon-e2e-admin` | `Recon@12345` | `ragshared0client00000001-org-recon-platform` |
| 权益 | `benefit-center` | `properties.tenant_id=dev-tenant` | `benefit-e2e-admin` | `Benefit@12345` | `ragshared0client00000001-org-benefit-center` |
| 营销 | `marketing-platform` | `properties.tenant_id=retail-cn` | `marketing-e2e-admin` | `Marketing@12345` | `ragshared0client00000001-org-marketing-platform` |
| 交易中心 | `transaction-center` | JWT `tenant_id=900001` | `trade-demo-admin` | 见交易仓 `deploy/.env.casdoor.json` | `transaction-center` |
| WMS | `wms-platform` | `enterprise_id=ENT-DEMO` | `wms-ops` | 见 WMS 仓 `deploy/.env.casdoor.json` | `wms-platform` |
| OA | `built-in` | JWT `sub` 为 Casdoor 用户 id；需 SUPER_ADMIN | `admin` | `123` | `oa-platform-local` |

机器身份（client_credentials，不用于门户登录）：

- 风控：`risk-bank-client` / `risk-runtime-client`，secret 由开通脚本注入（本机重建默认 `risk-bank-local-secret-20260914`、`risk-runtime-local-secret-20260914`）。
- 权益←营销：`benefit-marketing-client`，secret `benefit-marketing-local-client-secret-2026`。

营销仓库默认 Compose 仍是 DEV + Keycloak；门户 OIDC 组织已按上表开通，叠加 `--secure` 后才走 Casdoor。

重新开通：

```bash
bash deploy/portal-casdoor-restore.sh
```
