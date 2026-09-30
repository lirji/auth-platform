# P7 认证错误分类修复

本轮继续P7-02/03/04的有界缺陷整改：发行方HTTP 200错误对象不再误报用户凭据无效；无法确定身份时保持拒绝，返回503/DEPENDENCY_UNAVAILABLE。生产容量及发布接受仍HOLD。

## 诊断证据

- 历史4c21b98cbbdc的两次INVALID_CREDENTIAL都在P6实例；P6与P7的认证源码完全相同，不能归因为旧版独有。
- 新隔离诊断b286c3d8481c在并发1/4/8各200次请求中分别观察0/0/11次错误；并发8为7次INVALID_CREDENTIAL、4次DEPENDENCY_UNAVAILABLE。
- 只记录布尔类型/字段一致性、不记录Token或用户事实的代理诊断观察到7份HTTP 200、status=error、无active字段且消息属于数据库/连接错误的对象，另有1份HTTP 401错误对象。Casdoor同期日志有9处“remaining connection slots”，只读共享PG快照为max_connections=100、superuser_reserved_connections=3、pg_stat_activity=94。
- 由这些复现证据确认“上游依赖错误被当成凭据错误”的路径。历史48请求样本未保存相同响应分类，故不声称逐条重建了历史两次响应。
- 权威/图数据库已隔离，但固定Casdoor18090的底层仍使用dev_infra共享PG，连接争用仍能影响基线。这是实际测量范围的限制；没有扩容共享PG、停止他人服务或修改既有Casdoor配置。

## 行为与保护

CasdoorAccessTokenVerifier在校验active值之前，识别非布尔active、error字段或status=error错误对象并返回依赖不可用。合法active=false仍为401；active=true时仍须通过签名、期限、用途、issuer/subject/audience等严格一致性校验。不增加重试、正向认证缓存或新依赖；上游错误文本不进入响应/日志。

六组真实签名+本机HTTP测试覆盖空对象、null/字符串active、数据库错误及带active=true的冲突错误对象；每次只访问一次发行方，恢复后可重新认证，随后撤销仍401。真实双JVM演练对同一注入错误对象验证修复实例503、P6基线401及恢复后的ALLOW。

演练新增有限响应类别计数（ACTIVE/INACTIVE/ERROR_ENVELOPE/MALFORMED/HTTP_ERROR）、逐请求实例与错误码，保留全部失败延迟。原21项混部/故障/轮换/恢复检查保持，新增上述错误分类检查。200次/档是已有16—200工具边界内的诊断复测，不改变生产目标未定的决定。

## 验证与交付

本地reactor验证：209项单测通过（protocol14/core27/governance86/server28/admin54），新增案例净增5项；Python语法及git diff检查通过。完整演练fd17d59c70ab的22项检查全部通过，源码SHA256与统计见[P7_AUTH_FIX_EVIDENCE](P7_AUTH_FIX_EVIDENCE.json)；产品提交e16a4ddfa009bdac025adfe6640f828cab9fdf8f已正常合并推送main，远程CI36655346503成功；见[CI_RESULT](CI_RESULT.md)和[P7_DELIVERY_RESULT](P7_DELIVERY_RESULT.md)。不复用上轮9f191e4的CI作为当前源码的通过证据。

已知边界：本修复纠正错误分类，不消除共享PG连接争用，不能把503解释成容量达标。P6基线有意保留旧行为供混部对照，不篡改旧制品。OA与商城源码/既有运行单元不变，生产P7-07/08继续BLOCKED。

官方实现参考：[Casdoor v4.11.0 introspection入口](https://github.com/casdoor/casdoor/blob/v4.11.0/controllers/token.go)。本地诊断与实测结果才是上述故障结论的依据。

### 修复后混部实测

每档200次、无重试，所有失败计入延迟。仍保留P6旧制品作对照，不能当成两台新版容量。

| 并发 | RPS | P95 ms | P99 ms | 错误 |
|---:|---:|---:|---:|---:|
| 1 | 18.647 | 70.181 | 139.615 | 0 |
| 4 | 45.620 | 120.721 | 179.860 | 0 |
| 8 | 41.648 | 325.682 | 384.638 | 11 |

并发8：新版18170有8次DEPENDENCY_UNAVAILABLE、0次INVALID_CREDENTIAL；旧版18171有2次DEPENDENCY_UNAVAILABLE和1次INVALID_CREDENTIAL。该档introspection计数为ACTIVE189、ERROR_ENVELOPE7、HTTP_ERROR3（另1次在前置步骤失败，未到introspection）。阶段聚合计数不是逐请求响应关联，不据此虚构每条错误的细节。

恢复17.606秒、2道新水位、单条备份后撤权重放、readiness重试0，允许/撤销/到期行为通过。该恢复实测仍非生产RTO/RPO。

实施/验证Handoff：backend-implementation、implementation-validation完成本轮有界修复与验证；实现者复核，无独立代理审查。生产容量HOLD不因错误分类修复解除。所有本轮自有进程/容器停止，备份、配置及诊断数据保留在私有.local；无删除操作。

Code Hygiene Gate（base=5882ae1）为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断项；仓库无统一formatter、独立静态分析未配置，周边格式及差异检查通过。未引入新工具或全仓格式化。
