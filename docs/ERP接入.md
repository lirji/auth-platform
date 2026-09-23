# ERP 身份接入

2026-09-23 · 用户授权 ERP 自行按本项目对接方案实施。ERP 只接身份层①，组织、权限、数据范围由 ERP IAM 负责，无新增 SpiceDB。

- 本地 issuer：`http://localhost:8000`；独立应用/client/org：`erp-platform`。
- 开通：`python3 deploy/erp-platform-provision.py`；仅本地环境，默认登记 `http://localhost:8500/auth/callback`；隔离验证可传 `ERP_PUBLIC_BASE_URL=http://localhost:18500`。
- 授权模式：authorization_code + refresh_token，浏览器 PKCE S256；未开放 password grant、自助注册或机器身份。
- JWT：RS256，包含稳定 sub、owner；ERP 强制 issuer/aud/时间/身份校验，按 issuer/owner/sub 显式映射，不自动按用户名认领。
- 凭据引用：`~/.config/erp-platform/oidc-local.json`（0600）；真实密码及 secret 不入 Git，不进入 ERP 配置或前端。开通工具重跑保留密码，应用/用户不匹配则拒绝覆盖。
- 验证：开通及重复开通、真实 Chrome PKCE 登录/回调/refresh、ERP 200/401、未绑定账号拒绝及退出。ERP 证据位于 `.engineering/gates/TEST_RESULT-P1-OIDC.json`。
- ERP 操作文档：兄弟仓库 `erp-platform/docs/security/OIDC.md`；受控 crosswalk 脚本 `erp-platform/deploy/sql/bind-oidc-identity.sql`。
- 生产域名尚未提供，未登记虚构的生产回调、未执行生产部署。生产另行受控开通 HTTPS issuer/origin 和账号映射。

该脚本复用 `wms-platform-provision.py` 的管理 API/本机凭据读取函数，只写 ERP 命名空间；不会重新开通 WMS。
