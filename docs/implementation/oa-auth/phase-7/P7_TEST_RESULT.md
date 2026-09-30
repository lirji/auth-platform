# P7 本地验证结果

所选本地范围：PASS_WITH_LIMITATIONS。21项跨进程检查完成；auth受影响reactor150项单测通过，ProjectionProcessIT两项真实子JVM/PG/SpiceDB测试通过。P7-07实际生产Owner/操作授权及P7-08观察仍BLOCKED；不宣称生产上线或无条件RELEASE_CANDIDATE。最终源码指纹见P7_CODE_EVIDENCE.json，精确产品提交9f191e4的远程CI已通过，见[CI_RESULT](CI_RESULT.md)。

| 验收 | 实际证据 |
|---|---|
| 最小数据库权限、旧API关口及独立metrics端口 | P7_REHEARSAL_RESULT权限SQL负例、HTTP401/404，实际服务使用独立账号 |
| ALLOW/DENY/AUTHN/ERROR指标 | 3项新增单测及真实Actuator结果；标签无租户/用户 |
| 容量 | 下表，固定合成分布与全部样本；生产目标未定义 |
| P6/P7混部、kill/重启及共同撤权 | 两个真实JVM及不同构建制品摘要；同一V19模型 |
| 图/IdP/检查账号数据库故障 | 独立代理/自有PG账号故障；503且恢复后读取当前授权 |
| 旧投影晚到/未知结果 | ProjectionProcessIT真实SIGSTOP→撤权→SIGCONT、图提交后kill及SQL回执恢复 |
| 服务/客户端密钥/签名证书轮换 | 新建Casdoor客户端、专属JWKS，两实例退休旧凭据与旧Token |
| 隔离恢复 | 一致备份新库还原、完整实验撤权日志幂等重放、先UPDATING、空新图、新水位及允许/拒绝 |

## 本地容量样本

每档48请求；并发1/4/8；两实例、3条初始直接Grant，38允许/10越界拒绝；nearest-rank百分位包含全部错误请求。依赖故障代理在测量链路中，包含真实Casdoor在线探针/introspection及DB/图耗时。小样本不代表稳定容量或生产SLO。

| 并发 | 请求 | 实测RPS | P95 ms | P99 ms | 错误 |
|---:|---:|---:|---:|---:|---:|
| 1 | 48 | 14.111 | 73.639 | 982.814 | 0 |
| 4 | 48 | 48.709 | 100.986 | 106.632 | 0 |
| 8 | 48 | 55.704 | 186.274 | 191.195 | 2 |

最终样本错误分布：`{"INVALID_CREDENTIAL": 2}`。错误全部保留，不能以成功样本平均值掩盖。这里的PASS表示固定实验执行并取得可复核结果，不表示吞吐目标或错误率验收通过。

恢复阶段实测17.53秒（含备份/新库/新图/启动/验收）；重放1条已知备份后撤权，2道水位重新建立，HTTP readiness重试0次。生产RTO/RPO未知，不能将单条封闭事件证明外推为生产日志完整性。

## 失败历史与限制

首次使用共享PG时连接余量不足，未改共享配置；改用相同镜像独立有界PG。首轮完整恢复曾在新投影完成后的首个HTTP探针出现AUTHZ_STATE_NOT_READY；独立主库/图复核可正确允许，后续以有界readiness门禁保守等待，不把503改成ALLOW。最终运行原始结果保存实际重试次数。

较早容量试验在并发8出现9次错误；带独立故障代理复核仍观察到拒绝或依赖错误，未删除失败记录。所有中间运行保留在.local/governance/p7。当前生产容量门禁HOLD，不承诺无错误高并发。无统一formatter和独立静态分析，hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，详见P7_HYGIENE.json。

本次复用真实小型直接授权夹具；没有完整生产组扇出、外部成员/资源全集恢复、长时间流量观察或外部报警送达验收。实现者执行验证，不冒充独立人员/代理审查。未改公开协议、持久化schema、SDK及OA/商城产品源码；无可见UI变更，因此视觉验证N/A。

后续认证故障分类整改与共享IdP数据库限制见[P7_AUTH_FAILURE_FIX](P7_AUTH_FAILURE_FIX.md)；本文件原始实测作为历史证据保留。
