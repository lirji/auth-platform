# IR-01 Test Result

结论 PASS（2026-10-02）：本片源代码、既有协议兼容和实际运行验收完成。分支 feat/integrated-resource-selector，基线4a9057a；验证后没有产品源码变化。源文件清单与SHA256见 IMPLEMENTATION_EVIDENCE.json。

| 验收 | 实际结果与证据 |
| --- | --- |
| Backend compile/package/unit | 专属 `.local/maven-repository` 离线 Maven；`-pl auth-platform-admin -am test package -Dmaven.jar.forceCreation=true` 返回0；32份Surefire XML共188测试，0失败/错误/跳过。 |
| PG 权限/版本/兼容 | PublishedCatalogPostgresIT 7、PortalPostgresIT 15、AccessPostgresIT 10、RequestPostgresIT 19，共51个真实PG测试通过；`.local/integrated-resource-selector/pg-test-2.log`。 |
| 新目录资格与一致性 | 当前HUMAN委派、Owner仍须委派、外分区/普通成员403、晚期撤权/代际失效、A/C真实提交的成员/ceiling/Owner发布变化拒绝；disable ABA改变view_hash；菜单0及读取零写。 |
| 旧写语义 | PG证明createRole仍可存mixed/disabled，mixed scoped Grant拒绝；disabled同资源Grant仍受理PENDING；Policy及effective分别拒绝disabled。新UI过滤不冒称旧写用例新增门禁。 |
| 真实有效授权 | ReliableAuthorizationIT全13用例此前通过；本片增加前置ALLOW断言后，当前emergencyCapabilityDisableIsOwnerOnlyAuditedIdempotentAndFencesEveryPartition单独执行返回0、XML 1/0/0。先真实投影ALLOW，再停用投影DENY，再恢复投影ALLOW；`.local/integrated-resource-selector/reliable-disabled-current.log`。仅共享18544现有schema、独有UUID关系，无writeSchema/清理。 |
| 前端类型/构建/有限选项 | `npm run build`含tsc与Vite通过；node tests 14/0。真实资源metadata、unknown kind/重复/越界/循环引用fail closed；whole-role单资源资格、复制显式纠正、父菜单浏览不改变选择。 |
| HTTP / Owner实际发布 | 专有21662、21665，PG `auth_gov_p1_test_141225689195`；真实IdP18090独有组织与三个HUMAN。Owner经真实HTTP X-Command-Id发布有界实际成员/目录菜单，0→3 menus；窄ceiling flags、无Token401、普通成员/外分区403、no-store、读取零写。 |
| 实际PKCE / unknown | 三身份真实密码+S256+state/code_verifier，无Token注入。真实Role POST先提交后丢响应；Owner同时发布v3，UI冻结原输入并原command_id/payload重试成功，无resource_type写字段。 |
| 两ScopeFields消费者 | GrantEditor和PolicyEditor都实际选择commerce_member，仅一个TENANT_ALL选项，无SPECIFIED_STORES默认/回退。真实SQL为3roles、1Grant、1scope、1policy，role_id同一新角色、普通受益人、Owner审批者、两处同一持久化camelCase范围JSON。 |
| 撤权 | 专有窄委派enabled=false后当前HTTP读取403。 |
| 视觉 | 实际13张1440/390/320截图全部view_image人工检查；目录、Role/unknown、Grant、Policy、窄ceiling、普通拒绝，控件/按钮可读；所有截图浏览器scrollWidth断言无页面横溢。 |
| Hygiene | `.local/integrated-resource-selector/hygiene-final.json`：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，0 blocking；仓库没有可用canonical formatter，魔法值/异常处理仅advisory。脚本Python编译、两个Node语法和git diff --check通过。 |

当前HTTP/PKCE/SQL/视觉证据：`.local/integrated-resource-selector/runtime-42a69299d5c0/{result.json,browser-result.json,visual-review.json,final-sql-*.json,artifacts-*.json}`。jar内protocol/core/governance依赖逐字节匹配工作树target；原D2目录/target未修改。

失败历史保留：最初prepared运行缺配置、错误自动登录按钮/AntD定位/文案，均是验收工具错误；runtime-6ef9a4a6b76e和runtime-9e01ffe31278保留失败，不算PASS。当前runtime-42a69299d5c0完整浏览器通过后，SQL期望错误使用了HTTP snake_case；修正为实际固定ScopeValues持久化camelCase，使用`--resume-verify`，校验既有成功browser结果与jar/frontend来源hash后重新读取实际SQL并完成撤权；没有重放成功UI写命令或覆盖旧失败证据。

边界：本片UI创建Grant只证明受理PENDING，未宣称该fixture已图投影READY；有效授权停用证明来自上述可靠图IT。正式Commerce已有67caps/0menus和21导航映射的Owner真实发布属于完整目标后续集成，不能把本片3菜单测试发布冒充完成。没有性能预算/压测、生产部署、main合并或推送声明。

## 同任务 CI 注册补齐（2026-10-02）

PASS：仅既有authz-ci.yml与本片验证/进度/交付文档修改。push(main)与pull_request增加deploy/governance-integrated-resources-*路径；既有Python语法步骤新增IR UI harness，既有Node语法循环新增IR两个mjs；Node测试明确运行context6+published-catalog8。现有governance-it persistence/*IT.java已包含新增7PG用例，未改数据库/profile或增加基础设施。

实际精确命令：`node --test auth-console/tests/governance-context.test.mjs auth-console/tests/governance-published-catalog.test.mjs` →14 PASS/0fail/0skip；完整CI Node syntax循环12脚本全部exit0；`python3 -m py_compile deploy/governance-integrated-resources-ui.py` →exit0。PyYAML6.0.3 BaseLoader结构比较证明只改变两个事件paths与两个原step中的指定run语句，jobs/permissions/services/concurrency/PG不变；原20产品/测试/harness SHA与IMPLEMENTATION_EVIDENCE全部相同。工作流SHA256：5e424b318df85fa89a68e957b9fac764b70131bb606ea794ff25f22165b69f38。git diff --check通过。

原实施14870ed已由主Agent正常推送并合并main，原精确CI37023342834开始运行；此处仅记录主Agent报告的在途状态，不声称终态通过。本次CI补齐本地提交后交主Agent顺序集成并运行其精确新CI，不自行push/merge；未运行Maven/build/DB或修改原D2。

## 远程CI失败与测试配置兼容修复（2026-10-02）

原14870ed精确CI37023342834于14:57:08Z COMPLETED/FAILURE：新PublishedCatalogPostgresIT.open仅读取GOVERNANCE_TEST_CONFIG，既有第一governance-it job仅设置GOVERNANCE_TEST_DB_URL/USER/PASSWORD，导致open INVALID_ARGUMENT；其7方法未获得验证。原失败保留在私密ci-pg-config-followup/remote-ci-37023342834-failed.log，历史本地运行PASS不替代这次CI。

最小修复只改新测试open：显式CONFIG仍按原0600读取；未设置CONFIG时沿用Directory/Portal现有三个DB env配置。原loopback和auth_gov_p1_test_库名regex未修改，并在GovernanceRuntime.open迁移前执行；不回退非法显式文件、不跳过方法、不修改产品/安全guard/profile/基础设施。测试新SHA256：d1ada14a51647d47fa7ed2356f943a31cfd26666c1305b0a75d5905fe17db816；原SHA保存在IMPLEMENTATION_EVIDENCE.ci_config_followup。其余19原IR指纹完全不变。

实际CI-style命令：CONFIG unset，使用本片专属PG94e2d99f5525三个env，`./mvnw -o -B -Dmaven.repo.local=<本树>/.local/maven-repository -pl auth-platform-governance -am -Pgovernance-it verify` → exit0、10真实PG suites109/0fail/0error/0skip，含PublishedCatalog7、Portal15、Access10、Request19（既有51路径完整）。CONFIG模式：仅设置本树0600 database.properties，三个DB env unset，同命令加`-Dit.test=PublishedCatalogPostgresIT -Dfailsafe.failIfNoSpecifiedTests=false` →exit0、新7/0fail/0error/0skip。私密日志/完整XML快照/summary位于`.local/integrated-resource-selector/ci-pg-config-followup`，未使用/修改root D2共享库或target。

修复结论 LOCAL_VALIDATION_PASS；03054的CI注册与本次test-only逻辑提交交主Agent顺序集成/运行新的精确远程CI。原远程CI失败仍记录为FAIL，不自行push/merge，不宣称新的远程通过。
