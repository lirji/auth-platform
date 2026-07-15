# 候选方案 B：蓝绿身份栈与流量切换

## 1. 架构

复制一套“绿”入口（独立 edge deployment + oidc frontend build），指向准备好的 Casdoor，同时“蓝”入口继续使用 session/API Key。下游业务服务仍共用，因为内部 JWT 形状不变。通过独立域名或 Ingress 权重逐步把人类流量从蓝切绿；机器 API Key 固定走受控入口。

```text
users -> blue frontend/edge(session+api-key) ---\
                                                +--> shared downstream services
canary -> green frontend/edge(Casdoor+api-key) -/
```

## 2. 模块职责

- blue：现网基线与即时回滚目的地；
- green：Casdoor 配置、独立 audience/redirect、Casdoor-enabled edge 与 oidc frontend；
- traffic manager：按域名、cookie 或权重分流，且保证 OIDC callback 回到同一颜色；
- Casdoor reconcile：仍需方案 A 的数据准备或等价工具；
- 下游：不区分颜色，继续验证同一内部 JWT 公钥/密钥。

## 3. 核心流程

1. 完成 Casdoor 数据；
2. 部署绿 edge/frontend，不承接普通流量；
3. 在绿入口完成全角色、全服务矩阵；
4. 内部用户→1%→10%→50%→100% 分流；
5. 观察稳定后关闭蓝的人类入口；机器入口保留；
6. 蓝保留一段时间作为热回滚，再缩容。

## 4. 改动范围

- 主要在 Helm/Ingress/DNS/证书/前端 redirect URI、Casdoor application 和部署流水线；
- edge 业务代码改动较少，但配置必须隔离，不能让蓝绿共享错误的 issuer/audience；
- 需要明确内部 JWT 签名密钥兼容，否则共享下游无法同时接受两套 edge。

## 5. 扩展性与实施成本

- 扩展性和故障隔离最好；可精细百分比灰度；
- 成本高：双入口、双监控、双证书、OIDC callback 粘性、CORS/CSP/redirect 成倍增加；
- 对当前 dev 数据和单套本地 Compose 明显过重。

## 6. 主要风险

- OIDC authorize/callback 跨颜色导致 state/sessionStorage 不匹配；
- 两个 edge 的内部 JWT key/allowlist 漂移；
- 用户先在蓝产生 legacy session，再被分到绿导致混合身份；
- Casdoor 单点仍未被蓝绿解决，除非 Casdoor 本身也做 HA；
- 共享下游的 scope 门禁缺口不会因蓝绿而自动解决。

## 7. 回滚

把绿权重降为 0 即可，回滚成本最低；但若已经停止蓝 auth-service或撤销 session secret，就不再是热回滚。因此 destructive cleanup 必须晚于蓝保留期。

## 8. 适用结论

适合高流量生产和已有成熟 Ingress/DNS 灰度平台。本轮可只吸收“独立 oidc canary URL/前端构建”的局部做法，不建议完整复制整套栈。
