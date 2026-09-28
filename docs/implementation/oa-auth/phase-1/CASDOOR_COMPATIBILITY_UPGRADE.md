# Casdoor 固定版本兼容验证与升级执行计划

状态：隔离 Token 适配 PASS；共享升级 HOLD。用户已选择隔离验证和升级准备，尚未授权替换共享实例。P1-02 后端验收与 IdP 整体升级是两个 Gate。

## 真实基线与候选

| 项目 | 共享基线 | 隔离候选 |
|---|---|---|
| 实测版本 | v4.3.0，Basic `/api/get-version-info` | v4.11.0，相同端点；与镜像一致 |
| 镜像 | `casbin/casdoor@sha256:1b479655bf51b1c630f2a3ea93ec1ef58388e1d5861173377269266ea321537e` | `casbin/casdoor@sha256:138b5e46d49ad678ea2d53487fa74f8aae6e3ee3a670fc1b62ebe981c2a9d964` |
| 地址 | `http://localhost:8000` | `http://localhost:18090`，仅回环暴露 |
| 数据 | 现有 Casdoor 数据保留 | 独立 `auth_casdoor_p1_test_<任务后缀>`，非超级用户；没有复制/改写共享数据 |
| Token 格式 | JWT、JWT-Standard、JWT-Custom 全部 Access==ID | 三种格式均 Access!=ID；`tokenType` 区分 |
| Access introspection | 两种字段均 active=true，无法识别用途 | Access active=true，ID active=false；真实过期凭据 false |
| 全局版本读取 | 组织管理客户端可读取 | 组织客户端被拒绝；自身 built-in 运维客户端可读取 |

官方用途修复提交与候选版本可追溯：[修复](https://github.com/casdoor/casdoor/commit/f8eb7273a8ec16bf6caa2d09c8e8068d578288b0)、[v4.11.0 发布](https://github.com/casdoor/casdoor/releases/tag/v4.11.0)。源代码不能替代安装版验证；本表来自实际 API/码流程。Docker 语义 tag `v4.11.0` 探针不存在，因此按已验证摘要拉取，没有覆盖共享 latest。

## 已验证和未通过的边界

固定发行方的 RS256/JWKS、issuer、aud、nbf/exp、原始 sub/iat/exp、Token 用途和实时 introspection 共同验证。未知身份不自动建主体，仍有效 JWT 不能保留停用成员。具体结果以 P1-02_TEST_RESULT 为准。

额外码流验证使用每个负例的新 code，禁止因已消费 code 获得伪通过：错误 S256 verifier、重放 code、不匹配 redirect_uri。实际发现 v4.11.0 不拒绝不匹配 redirect_uri，**升级 Gate FAIL**。对应版本的 `controllers/token.go` 没有把 redirect_uri 传入 Token 兑换逻辑，与实测一致。`code-flow-probe.json` 是完整结果，工具以非零退出保留失败；不降低断言。ID Token nonce 匹配已验证；SPA state、浏览器回调、CORS、Cookie、登出、refresh/已有会话、旧库迁移、旧管理客户端和全部应用行为尚未验证。

CI 的 `--phase tokens` 只准备 P1-02 的真实签发夹具与 Token/绑定验证，不能代表完整登录升级 Gate。`--phase code-flow` 明确检查额外登录边界并在失败时返回非零。两种证据分开保存。

## 可重复的隔离验证

沿用本任务工作树，先读取 `.local/governance` 私密检查点；文件 0600、目录 0700。命令不使用默认管理员口令、不启用 password grant、不覆盖旧应用、不回显 Token/secret。这个本机隔离实例有 Casdoor 初始化的管理数据，只用于测试，不能直接作为正式平台。

```sh
python3 deploy/governance-test-db.py
docker pull casbin/casdoor@sha256:138b5e46d49ad678ea2d53487fa74f8aae6e3ee3a670fc1b62ebe981c2a9d964
python3 deploy/governance-casdoor-isolation.py
python3 deploy/governance-casdoor-fixture.py --base http://localhost:18090 --phase tokens \
  --directory .local/governance/casdoor-isolated \
  --management-config .local/governance/casdoor-isolated/management-client.json
GOVERNANCE_TEST_CONFIG="$PWD/.local/governance/database.properties" \
GOVERNANCE_IDENTITY_FIXTURE="$PWD/.local/governance/casdoor-isolated" \
  ./mvnw -B -pl auth-platform-governance -am -Pgovernance-identity-it verify
python3 deploy/governance-casdoor-fixture.py --base http://localhost:18090 --phase code-flow \
  --directory .local/governance/casdoor-isolated \
  --management-config .local/governance/casdoor-isolated/management-client.json
```

最后一步目前预期 FAIL，需解决缺陷后才可改为 PASS。所有工具只持有自己的 namespace/镜像；Owner/配置不一致即拒绝接管。没有自动 teardown，进行中的私密凭据/夹具与隔离容器保留；后续清理需明确授权，不能删除其他工作树或共享组件。

## 兼容升级的执行顺序

| 步骤 | 具体工作及产物 | 推进条件 |
|---|---|---|
| U1 候选整改 | 解决 redirect_uri 校验：验证包含修复的固定上游版本，或评审基于固定源码的最小补丁；不得仅让客户端总传正确 URI 就声称服务端已拒绝错误 URI。重新保存独立负例和源码/镜像摘要 | 码流全部负例 PASS；state/nonce/回调真实浏览器验证 PASS |
| U2 凭据治理 | 为版本证明选定只读、最小权限方式；验证组织隔离和 API 范围。introspection 应用 secret 与探针 secret 分开，由后端受控配置持有，禁止发给 SDK/SPA | 正式运维 Owner 明确；不依赖隔离 built-in 全局权限作为最终方案 |
| U3 旧库升级演练 | 明确共享 Casdoor 实际库/表与 SpiceDB 共库边界；先生成受控私密备份，再恢复到第三个隔离数据库。新镜像只连接恢复库，核对记录计数、用户/组织/应用、凭据、证书和自动 schema 迁移。保留迁移前后差异与恢复证据 | 不访问新旧混用的共享库写路径；恢复/升级失败不影响原实例 |
| U4 应用与会话回归 | 以恢复库逐一核验 auth-console、project-portal、OA、commerce 与已接入应用：授权码+PKCE、固定 issuer/JWKS/aud、Basic 同步、用户信息、refresh/登出、旧会话和错误语义。新增治理入口继续默认关闭 | 每个实际客户端真实回归 PASS；权限强化造成的管理调用拒绝均有明确整改 |
| U5 切换准备 | 保留共享原 issuer/回调/证书映射，固定新镜像摘要和配置；确定停写/观察窗口、备份点、业务 Owner 与中止条件。历史 Token 用途存在歧义，不能在新治理入口继续接受；明确重新登录/会话失效策略 | 升级报告、负例、旧库演练、凭据范围、回退手册齐备；用户批准具体共享替换 |
| U6 获准后切换 | 只在明确目标执行替换，观察签发/校验失败、跨组织拒绝、同步/登录可用性。治理新入口按独立应用配置启用，不用全局 isAdmin 授予角色 | 需另行实际操作授权；本次未执行 |

若新版本改变数据库结构，回退镜像不等于恢复：必须验证旧镜像能读新结构，否则恢复到已演练的备份数据库，并说明切换后新增用户/客户端/会话的恢复或补偿。不能回滚治理数据库的停用事实来恢复登录；新治理入口遇到旧 IdP 版本返回 503、继续拒绝。共享 SpiceDB 不清库、不改 schema、不重建图。

## 当前结论

候选解决了 Access/ID Token 混用，可在隔离环境验证新后端身份能力；共享替换仍 HOLD。U1/U2/U3/U4 未全部完成，不把本报告写成升级完成或生产就绪。P1-03 等后端片继续使用隔离候选；正式切换前保持新入口默认关闭。
