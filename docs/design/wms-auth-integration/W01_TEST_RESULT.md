# W01 资源协议验证

日期：2026-10-04。Gate：PASS（仅 W01）。全任务仍等待 W03 必要业务输入。

## 结果

| 验收 | 方法 | 结果 |
|---|---|---|
| 仓库仅支持精确资源范围；拒绝门店/全企业/未知字段 | ScopeResourceBindingsTest、ScopeRulesTest | PASS |
| 同Grant范围、宽读/窄写、跨租户和空范围 | ScopeRulesTest | PASS |
| HTTP消费拒绝损坏范围，旧用户令牌下一请求重查、区分拒绝与503 | CentralAccessClientTest | PASS，真实HTTP服务为协议测试夹具，不是Casdoor登录验收 |
| 真实PG持久化、授权图与严格栅栏、仓级撤权/原上下文拒绝 | ReliableAuthorizationIT#realWarehouseGrantsRemainScopedAndRevocationRejectsTheOriginalContext | PASS |
| 原电商门店读/退款范围语义不变 | ReliableAuthorizationIT#realGraphPathsKeepReadAllAndRefundOneStoreSeparate | PASS |
| WMS实际目录被Auth生产解析器接受 | CatalogManifest.read，读取本次WMS生成的catalog.json | PASS，49能力/16菜单 |
| 格式/静态/差异卫生 | 仓库发现、code-hygiene gate、git diff --check | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；无统一格式化工具，不虚称执行 |

主 reactor verify：206 单元测试 + 2 真实 PG/图 IT，0失败/错误/跳过；另执行 SDK 的 13 个 HTTP 测试，均通过。最初测试新增常量漏静态导入导致编译失败，已修正并重新验证，失败日志保留。

命令：

```bash
./mvnw -q -pl auth-platform-protocol,auth-platform-governance,auth-platform-sdk -am test \
  -Dtest=ScopeResourceBindingsTest,ScopeRulesTest,CentralAccessClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false
GOVERNANCE_TEST_CONFIG=<本任务独立database.properties> \
GOVERNANCE_P3_GRAPH_CONFIG=<既有P3测试图graph.properties> \
./mvnw -q -pl auth-platform-governance -am verify -Pgovernance-projection-it \
  '-Dit.test=ReliableAuthorizationIT#realWarehouseGrantsRemainScopedAndRevocationRejectsTheOriginalContext+realGraphPathsKeepReadAllAndRefundOneStoreSeparate' \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

独立测试库为 auth_gov_p1_test_cfce2dfa3d39，使用新角色并仅追加本次随机隔离测试租户/应用/Grant。复用既有 P3 图的模型和接口，只写本片随机资源标识，没有重写 schema、修改原治理Grant、8602配置或运行容器。

私密证据保留于 .local/wms-auth-integration/w01-evidence/：原失败、成功日志、XML汇总、实际目录解析和校验工具。图与独立库暂留供复验；无清理授权。

未执行：WMS资源Owner SQL、实际WMS用户PKCE、控制台授权菜单/深链、作业写路径与Docker运行切换。它们属于W03–W07，不被本片PASS覆盖。
