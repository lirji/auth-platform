# project-portal

公开、免登录的统一能力门户。页面只读取同源 `/config/catalog.json`，不会请求 Casdoor、auth-platform-admin 或目标项目 API；点击项目后先进入目标项目自己的租户选择页，再由目标项目建立 PKCE 并跳转 Casdoor。

## 开发与验证

```bash
corepack pnpm install
corepack pnpm dev       # http://localhost:5274
corepack pnpm test:run
corepack pnpm build
```

本地默认目录在 `public/config/catalog.json`。当前入口使用 LangChain4j `8093`、Recsys Vite `5275`、Drools Docker 网关 `8095/ui/`、Risk Console `15173`；更换启动端口时必须同步 catalog。

每个配置为 `available` 的项目可设置与 `launchUrl` 同源的 `healthUrl`。门户会用不携带凭据的跨域请求检测目标是否可达：检测中暂时禁用入口，连接失败显示“当前不可用”，每 30 秒及页面重新可见时自动复检；未配置 `healthUrl` 时仍按静态状态展示。

## 运行时目录

生产镜像不烘焙真实域名。复制 `config/catalog.example.json`，填入浏览器可达的 HTTPS launch URL，然后挂载到容器：

```bash
docker build -t auth-platform/project-portal ./project-portal
docker run --rm -p 8203:80 \
  -v "$PWD/project-portal/config/catalog.prod.json:/usr/share/nginx/html/config/catalog.json:ro" \
  auth-platform/project-portal
```

catalog 是匿名公开内容，不得写入 token、client secret、内部 service DNS、管理地址或个人数据。正式入口不预选 tenant/clientId，避免公开门户替用户猜测组织；租户与 client 的映射由目标项目自己的配置校验。

更新 catalog 不需要重新构建 Vite bundle或镜像。文件由 ConfigMap/volume 更新后，新页面加载会以 `no-store` 重新获取。

## 发布约束

- 生产 launch URL 只允许 HTTPS；`allowHttpLocalhost` 只用于本地 loopback 开发。
- launch URL 指向目标项目自己的 `/login` 租户选择页，可携带经过消毒的站内 `returnTo`/`redirect`，不能直接指向 Casdoor authorize endpoint。
- `available` 才能点击；尚未完成目标 OIDC/auth 配置的项目保持 `maintenance`。
- nginx 对 catalog 禁止缓存，对带 hash 的 assets 使用 immutable 缓存，并设置 CSP、`Referrer-Policy: no-referrer` 和防 framing 安全头。
