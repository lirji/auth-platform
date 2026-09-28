# P1-02 独立验证

slice Gate PASS，范围仅 Token 验证和显式 LoginIdentity 适配。绑定当前源码摘要 `b48ffb7301068ac705cf48e9ae42c87052c8629a90c452520f323b4b029aa2d6`，详细路径与摘要见 P1-02-evidence-index.json；结构化结果见 P1-02-test-results.json。

| 验收 | 实际方法 | 结果 |
|---|---|---|
| 错签名/算法/issuer/aud/用途/过期/未来 nbf/缺失声明拒绝 | CasdoorAccessTokenVerifierTest，真实 RSA+本机 HTTP，共 23 案例 | PASS |
| active/client/iss/sub/exp/aud/type 严格一致；无正向认证缓存 | 同套件；前次成功后 active=false 立即拒绝 | PASS |
| 版本降级/未知、超时/畸形/超大/HTTP 重定向、JWKS 故障与容量饱和 | 同套件，错误码为依赖错误并拒绝；完成后释放容量 | PASS |
| 真实 v4.11.0 的三种格式 Access/ID 分离；过期/错应用/错issuer-aud/篡改拒绝 | CasdoorIdentityIT 5 项，真实码流程 Token + PostgreSQL 16.15 | PASS |
| 精确 issuer/sub 绑定；外部未知身份不创建；有效旧 JWT 无法保留停用成员 | 同套件，真实 SQL 当前状态与记录计数断言 | PASS |
| 实际 v4.3.0 元数据及 Access==ID 时拒绝启用 | CasdoorLegacyIT 1 项，专用旧版夹具与只读 metadata | PASS |
| 数据完整性和旧模块回归 | `-Pgovernance-it verify`，全 reactor 149 单测 + 11 PG IT，无失败/错误/跳过 | PASS |
| FORMAT / STATIC_ANALYSIS / CODE_HYGIENE / REPO_CONVENTION / DIFF_HYGIENE | Hygiene+diff；没有配置 formatter（SKIPPED: TOOL_NOT_AVAILABLE），无独立 analyzer（N/A），其余 PASS | PASS_WITH_LIMITATIONS |
| 工具可重跑和不接管其他 Owner | 固定隔离实例重跑 PASS；Python 编译；私密文件/显式 stage 范围核验 | PASS |

验证过程没有替换产品代码或降低测试断言。实施修复后再验证，保留初次缺 iat 失败 XML 和初次 Hygiene 结果于忽略 `.local/governance/p1-02`。最终 verify / identity-it / legacy-it 日志和报告在同目录。无现有 formatter 是唯一 Hygiene 限制，不为门禁新增格式化器；独立漏洞扫描、容量承诺、浏览器回调、旧库升级和生产部署未运行。

**共享 IdP 升级另行 HOLD**：完整 code-flow 负例实测错误 verifier 和重放 code 被拒绝，但不匹配 redirect_uri 未拒绝，工具退出 1；见 CASDOOR_COMPATIBILITY_UPGRADE.md。此项是额外升级/登录发现，不是已批准 P1-02 的 Token/身份适配验收；不能以本片 PASS 宣称候选整体可升级。正式版本证明最小权限凭据仍待落实，治理 HTTP 新路径尚未启用。

```yaml
SKILL_HANDOFF:
  protocol: skill-contract/v1
  skill: implementation-validation
  slice_id: P1-02
  status: COMPLETED
  gate: PASS
  produced: [P1-02_TEST_RESULT.md, P1-02-test-results.json]
  verification:
    - check: 必需 Token/身份绑定验收均有实际执行证据
      result: PASS
    - check: 未运行/额外失败不宣称通过
      result: PASS
  downstream_requirements:
    - update-progress-docs 标记 P1-02 DONE，P1-03 READY
    - 共享 IdP 替换保持 HOLD，先整改完整登录升级 Gate
  recommended_next: [update-progress-docs]
```
