# 商城接入续做交付

状态：CATALOG本地接入与审核工具已交付；全模块扩展业务边界已批准；生产HOLD。用户已明确采用员工端范围边界及独立高风险权限，沿用现有业务审批。

## Git与验证

|仓库/逻辑单元|提交|验证|
|---|---|---|
|commerce完整CATALOG页面|41c22ae|393 Java项：388通过/5可选跳过，前端编译/Prettier/打包、本地真实浏览器|
|commerce进度/PKCE证据|4b12706、f7cb512|相对41c22ae仅文档；4b12706 CI36660768385 SUCCESS；最新见下|
|auth审核工具/浏览器与独立身份模式|aaf7c18|22项Python、编译/语法、34跨进程验收|
|auth完整密码+PKCE验收补充|fc49332|最终90089b33648d：34项通过，含9条浏览器分项|
|auth全模块契约/清单/接受准备|ece04d0、b0c1e00|218条入口清单、动态action核对、相对文档链接检查；业务范围/审批选择已记录至扩展契约|

任务分支auth `feat/commerce-migration-readiness`、commerce `feat/central-catalog-entry`，均在原目录创建，无新worktree。所有提交已正常fast-forward合并并推送origin/main，未强推。最终收尾仅文档，不改变已验证产品/工具树。

初轮auth ece04d0的CI36660687852、commerce 4b12706的CI36660768385均SUCCESS。补充PKCE之后最终检查：

- auth b0c1e00d6391300139e8508d00a24585c3c48232：[CI36661541739](https://github.com/lirji/auth-platform/actions/runs/36661541739) — SUCCESS。
- commerce f7cb512deb8dfd22f5e28a12e9de92a0e1f62b87：[CI36661542743](https://github.com/lirji/commerce-platform/actions/runs/36661542743) — SUCCESS。

本地证据见[TEST_RESULT](TEST_RESULT.md)及[MIGRATION_REVIEW](MIGRATION_REVIEW.md)。CLI prepare-review实际执行返回2/REVIEW_REQUIRED，0600输出保留previous_sha256，1 actor/1 grant/0差异/不可导入和切换。

## 状态与剩余

原63节点DAG不重写，P7-07/08继续BLOCKED。新增CE-00首轮清单完成、CE-01业务边界已批准，CE-02—08未实施。新模块不会从旧ADMIN/CATALOG隐式继承权限。真实OA绑定/Owner签字、永久授权期限、动态商家语义、生产目标/负载/SLO/RTO/RPO未确定；原8000 Casdoor升级仍HOLD。

已复验原商城8602健康，未切换或重启。OA6385a86及既有CODEX_PROGRESS/PROGRESS_STATE/DEPLOYMENT_RESULT/.local/tmp改动未触碰；不执行OA写入、生产部署、数据删除或旧授权收缩。

## 本地保留项

- auth `.local/governance/commerce-readiness`：快照、人工审核、dry-run、CI/hygiene与日志。含敏感元数据，不提交。
- P6演练c596881dc886失败503、9d785b72453b断言失败、c9a502b7a77c成功、90089b33648d完整PKCE成功：保留失败与成功证据/私密配置/隔离MySQL库。
- 为本轮P6复用P7资源helper创建f80e2038fe55、93fa4df3d37f、38f09bba42d4，共6个自有PG/IdP容器均已停止；网络/卷/数据保留。所有本轮JVM/Vite已停止。
- commerce `.local/central-catalog`保留构建、单测和hygiene证据；历史`.local/p0-baselines/commerce`基线worktree与原P7目录保留，不是本轮新工作树。

这些自有停止资源在证据归档、确认无后续任务依赖并取得清理授权后可清理；本轮没有删除授权，故未移除。共享实例保持运行。当前各任务分支提交已合入main，可留作追溯；未删除分支。

## CE-02/CE-03-I增量交付

- auth CE02-D/A：a503f24、5860200、1b1ee01已正常合并推送main；CI36663171698 SUCCESS。
- CE03-I：auth29052d05f37d7fc6001c1ef567e420a8b1bd05a4（feat/central-inventory-reference）及commerceb7715cec54d1cfcc02d5d9ab722bfba74fa8e09b（feat/central-inventory-access）已普通快进合并并推送main；CI36664884469、36664884967均SUCCESS。包含实现/迁移/测试/文档，未混入后续UI。
- 验证：商城397项392PASS/5skip、PG+graph执行引用两项IT、真实中央+商城隔离51PASS。证据详见CE03_INVENTORY.md。没有生产部署、真实Grant发布或旧授权收缩。
- 正继续CE03-U，在原两仓新建本任务UI分支；没有新worktree。专用MySQL43308重启供本轮测试，最终保留数据并停机；私密证据不纳入提交。

## CE03-U Git交付

本地验证后已正常合并推送：auth db96617c41618f54cc28785fff77cb439af91e07（feat/central-inventory-ui-contract），commerce 8cf2a584b067ddee34a883d48758b07c8c8cbf8d（feat/central-inventory-ui）。CI36666230427/36666232081均SUCCESS。D0 auth2557de1c70625f19fe18b60a11ecb022298939a0已合并推送main，CI36666736638 SUCCESS。

## CE03-D1交付

auth feat/commerce-directory-owner-contract与commerce feat/central-commerce-directory：实现/迁移/验收文档本地DONE，401项Java396PASS/5skip和83项真实隔离检查。验证后仅修正四个Java文件缩进（无语义变化）并同步文档，hygiene无阻断。新V51已执行不得改历史。D1 auth378313c/commerce37e2e06已正常合并推送main。商城CI36667458010因固定旧SDK缺executionScope编译失败；49274a8已单独修正scripts/auth-sdk-source.ref至auth2557de1并推送，CI36667650544 SUCCESS。D2未混入这些提交。auth CI36667456414 SUCCESS。

## CE03-D2本地交付门禁

当前auth feat/commerce-directory-ui-contract和commerce feat/central-directory-ui，目录页面/两提示/工具/契约/验证文档与当前验证版本相同，D2本地DONE。402项397PASS/5skip、最终99隔离检查与已查看截图详见CE03_DIRECTORY；最终只有前端ID修复，已重build/package/真实浏览器验证。按明确路径stage；私密.local、停止容器卷和失败证据保留。实际提交/CI另记，未进行生产部署。

D2实际交付：auth3a8f7e9（feat/commerce-directory-ui-contract）、commerce5587d53（feat/central-directory-ui）已普通快进合并并推送main；远程CI待查。auth当前继续feat/commerce-member-execution；commerce main暂时干净，没有新worktree。

CE04-P0本地DONE，251单测/4真实PG+graph IT/Boot4兼容/package/hygiene无阻断；auth feat/commerce-member-execution只包含协议/Owner门禁/测试/契约/证据，无商城后续P1实现。Git交付进行中。

CE04最新：P0 auth dd07223已推送，CI36668768692 SUCCESS（含D2基线）；D2 commerce CI36668436980 SUCCESS，auth旧run36668435670取消。P1本地DONE：406项401PASS/5skip、实际隔离114PASS（rehearsal-451c622849b4）、36项工具/223入口契约及hygiene无阻断，见CE04_MEMBER.md。下一CE04-P2 READY，后续会员和CE05—08未完成；原63节点DAG与生产HOLD不变。
