# P1-02 Token 与登录身份适配实施证据

唯一 slice P1-02，needs P1-01 DONE，契约 CONTRACTS_P1；工作树 feat/oa-auth-p1-identity，基线 e5ff625。Allowed Change Set 为 governance 认证/测试、固定隔离 Casdoor 工具、CI 和对应文档；不改 OA/commerce、旧客户端、共享 IdP 或既有 V1 迁移。

新增 TokenAuthority、VerifiedLogin、CasdoorAccessTokenVerifier、AuthenticatedIdentityReader。固定配置的发行方/受众与 RS256 公钥验签；原始 sub/iat/exp 必填；ID Token 通过 tokenType 和实时 Access hint introspection 双重拒绝。应用客户端和版本探针客户端分开；未知/降级/读取失败不装配或不认证，不能降级到旧验证器。公钥缓存不缓存 active/member/ALLOW；有界响应、连接/read timeout、并发上限，不自动重试。显式绑定消费当前治理状态，未知外部身份不自动开户。

实施窄验证及最终独立结论分开：25 governance 单测（其中 23 个认证案例）、真实 Casdoor+PG 五项 IT、旧版一项 IT；全 reactor 149 单测、11 PG IT 通过。源码摘要见 P1-02-evidence-index.json。全部生产公共方法和关键失败原因有中文说明；无新增数据库迁移或业务 SQL。

保留失败：初次缺 iat 测试发现 Spring Jwt builder 自动补时间；改为在 claim 转换前检查原始声明，相关负例重新通过。初次 Hygiene 检出 CLI phase 魔法值与复用 failsafe 未登记；CLI 使用稳定 Enum、构建插件归属已记录，最终门禁为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无现有 formatter）。

额外固定版本码流测试：错误 verifier 与 code 重放拒绝；错误 redirect_uri 未拒绝，隔离工具按 code-flow Gate 非零退出。这个缺陷属于完整 IdP 升级/前端登录 Gate，未放宽断言；共享升级 HOLD，候选不能直接部署。版本探针使用自身隔离 built-in M2M 的最小权限替代仍未定。未执行共享升级、旧库迁移或浏览器全链路。

```yaml
SKILL_HANDOFF:
  protocol: skill-contract/v1
  skill: backend-implementation
  slice_id: P1-02
  status: COMPLETED
  gate: PASS
  produced: [SOURCE_CODE, P1-02_IMPLEMENTATION_EVIDENCE.md]
  applied_policies: [config, resilience, authz-mapper]
  unresolved: [IDP-REDIRECT-01, formal-version-probe-least-privilege]
  downstream_requirements:
    - 独立验证当前源码，P1-02 Token Gate 与共享 IdP 升级 Gate 分开判定
    - P1-03 默认关闭并使用隔离候选，不接管旧路由
  recommended_next: [implementation-validation]
```
