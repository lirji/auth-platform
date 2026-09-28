# P2-01 应用清单验收

结果 PASS。治理源码摘要 `16a956d0090a75b170f3a1716c339c81a9777ec68a478b0db20e94f5eb025935`；基线187cbde，当前任务分支feat/oa-auth-p2-rbac。

- 实际执行 `GOVERNANCE_TEST_CONFIG=<私密配置> ./mvnw -q -pl auth-platform-governance -am -Pgovernance-it verify`，退出0。
- 新增3项解析测试、5项真实PG测试通过：未知/重复/错误类型字段、跨应用命名空间、菜单环路、源URL、归一化摘要；预览无写、并发同版发布唯一、所有权冲突、版本/语义不可变、停用owner、审计失败回滚。
- 既有本模块unit回归通过。当前本次真实PG报告为Identity 20、Invitation 9、Directory 17、Catalog 5（共51）；其他历史profile报告不计入本次。
- 复用独立auth_gov_p1_test数据库，Flyway增量V6通过，V1–V5未修改；未改共享IdP/图。私密日志 `.local/governance/p2/p2-01-pg.log`。
- CLI注册/预览/发布复用同一服务；HTTP管理API属于P2-03，当前没有开放未鉴权路由。
- 仓库未配置统一formatter/独立静态分析器；格式沿用既有Java风格，最终阶段执行Code Hygiene gate。当前没有可见UI。

实施范围：ApplicationCatalog/CatalogManifest、目录Mapper XML、V6、受控CatalogCli、治理Runtime装配与局部403错误码。后续角色与Grant仍未实施，不声称整个P2完成。
