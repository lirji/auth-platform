# 授权工作区（多项目 SpiceDB）

auth-console 登录后不是跳进 Recsys/风控自己的业务控制台，而是进入**本管控台内的项目工作区**。每个工作区对应一份 SpiceDB；业务系统仍用各自的门户卡片登录。

## 入口

1. 门户卡片 `auth-platform` → `/login?returnTo=%2F`。
2. Casdoor 登录成功后到 `/`：可见工作区多于 1 个则展示选择页；只有 1 个则直进该项目的 `home` 页。
3. 工作区 URL：`/w/{id}/...`（如 `/w/knowledge/spaces`、`/w/recsys/grants`）。旧书签 `/grants` 会转到当前身份的第一个工作区。

## 准入

- JWT `owner` 命中工作区 `organization`（或 `organizations` 附加列表）即可进入。
- `built-in` + 组 `authz-admin` 视为平台运营，可进全部工作区。
- 读/写仍要 Casdoor 组 `authz-viewer` / `authz-admin`（与原来 admin 安全矩阵相同）。

## 请求契约

- 列表：`GET /admin/workspaces`（不切 SpiceDB 实例）。
- 其它 `/admin/*` 读写：请求头 `X-Authz-Workspace: knowledge|recsys|risk`。
- **例外**：`/admin/casdoor/**`（组/部门同步、webhook）始终打默认知识库 SpiceDB（`:8543`），不跟请求头切换，避免把知识库成员关系写进 recsys 库。

默认目录在 `auth-platform-admin` 的 `authz.workspaces`：

| id | 组织 | 默认落地页 | SpiceDB |
|---|---|---|---|
| `knowledge` | `built-in`，另允许 `acme`/`globex` | `spaces` | `authz.spicedb.endpoint`（:8543） |
| `recsys` | `recsys` | `grants` | `RECSYS_SPICEDB_HTTP`（:8544） |
| `risk` | `risk-platform` | `grants` | `RISK_SPICEDB_HTTP`（:8545） |

本机风控 SpiceDB 用 `risk-platform` 的 `./scripts/compose.sh --profile authz up -d risk-spicedb` 拉起（postgres + migrate + :8545），不要只 `docker start` 已退出的 `risk-spicedb` 容器，否则会因找不到 `risk-authz-postgres` 主机立刻退出。密钥必须与 `risk-platform/.env` 的 `RISK_SPICEDB_KEY` 一致（admin 默认占位 `risk_dev_key` 对不上本地生成值）。写入模型：`SPICEDB_HTTP=http://localhost:8545 SPICEDB_KEY="$RISK_SPICEDB_KEY" APPLY=1 bash deploy/risk-authz-fixture.sh`。

recsys :8544 同理需该项目自己的实例。要看 knowledge.zed 切「知识库与 HIS」（`:8543`）。

## 审计

grant/revoke 的审计 `detail` 带工作区前缀；`GET /admin/audit` 只返回当前请求头对应工作区的记录，避免跨项目串看授予细节。
