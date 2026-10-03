# MG07 本人业务导航验证

Auth生产方pass DONE／PASS；Commerce适配源码及实际跨仓HTTP PASS，固定SDK Git提交消费方pass待生产方提交后验证。父MG07 VERIFYING，全MG00–MG19目标仍ACTIVE；未生产部署，原Commerce目录／业务Grant0写入。

## 交付单元与实现

本片分为依赖有序的Auth生产方与Commerce固定SDK消费方两个pass，不重排稳定MG ID。Auth协议／用例／Server入口／SDK可独立构建验证后先正常Git交付；Commerce再把scripts/auth-sdk-source.ref固定到准确生产方提交，调用既有制品安装脚本并验证。父片只在两个pass都通过后DONE。

- 请求严格只有tenant／预期代际／关联ID。CallerService固定应用／环境，服务凭据与用户专属audience分别认证，独立navigation.callers是已注册scope调用方子集；缺省关闭，没有管理Token、/me/access或单能力执行引用回退。
- BusinessNavigation按当前菜单引用能力查严格范围存在性。整次查询使用前后新的主库栅栏，核对身份／成员、策略／目录／租户／Manifest及内容，逐能力保留最早到期，避免自然到期不改epoch的漏洞。7秒结果检查预算按调用边界执行，既有单能力／数据库／远端超时继续约束阻塞调用，不虚称7秒硬中断。
- 只输出本人相对路由、显示名称／顺序、固定目录双摘要及当次时点；祖先无直接权限没有route。状态使用显式稳定code，未知状态失败；无能力为NO_ACCESS，依赖错误整次503。
- SDK禁重定向／有界body／完整网络超时；验证schema、关联ID、主体租户／固定app/env、HUMAN与代际、双摘要／版本、时点、状态、菜单唯一／祖先无环／相对路由／显示元数据、能力命名空间。
- Commerce安全链只允许GET本人导航；每次中央查询后通过本地精确绑定找到OPERATOR，只授CENTRAL_NAVIGATION，不授任何业务authority、不签发执行引用。未知query／重复header拒绝。公开DTO沿用camelCase，内部Auth及SDK为snake_case。

## 验证

| 验收 | 证据 | 结果 |
|---|---|---|
| Auth编译与回归 | 强制打包281单测：protocol20／core27／SDK36／governance110／server34／admin54 | PASS |
| SDK严格HTTP | 新2项实际HTTP stub测试，正确双header、可用／无访问、401／403／503；错误目标、旧代际／版本、重复位置／菜单、祖先循环、外部route、坏时点、超大body均失败 | PASS（协议边界，不冒充真实SSO） |
| 导航规则与失效 | 3单测覆盖祖先无链接／排序／旧显示、已完成单能力检查后外层epoch改变及最早到期；到期fixture是逻辑单测，不声称实际等待Grant自然到期 | PASS |
| 真PG及授权图 | BusinessNavigationIT2；真实指定门店Grant仅显示允许菜单／协作入口，管理员没有业务菜单；12类表row_to_json逐行保持，包含执行引用；撤权／投影未就绪、旧主体版本、停用、新目录并发发布及图故障拒绝 | PASS |
| Commerce独立用例／安全链 | 3单测及完整reactor构建；精确本地OPERATOR绑定、故障不读本地旧权限、仅导航authority、上下文清理／401／400／只允许GET | PASS |
| 实际跨仓HTTP | 新专用PG／MySQL、现有Casdoor真实PKCE、Admin／Server嵌套治理JAR及Commerce嵌套SDK字节核对，22项 | PASS |
| 实际拒绝／撤权 | 正确商务audience＋本地绑定、无映射403、管理audience401、未登记caller403、伪造主体／环境400、外租户／旧代际403；撤权未就绪503、投影后NO_ACCESS、服务断连503没有旧结果 | PASS |
| 卫生 | Auth与Commerce实际卫生终态无BLOCKING；周边风格、Python编译、diff --check；无统一formatter／静态工具限制保留 | PASS_WITH_LIMITATIONS |

生产方非文档指纹 `b55d62efb86470c32962d8a6bdaa86b58a0623c975694cbb562165b6de4a672c`，基线e9f9e3a；mg07-auth-evidence.json逐文件记录。私密日志mg07-auth-reviewed-build.log、mg07-graph-reviewed.log、mg07-expiry-fence-unit.log、mg07-runtime-final.log；实际HTTP轮mg07-852bc0f01608。Commerce日志menu-role-governance-mg07-unit-reviewed／http-package／hygiene-reviewed。无前端可见UI改动，三宽度交互在MG08验收。

## 失败与恢复

- 第一轮真PG在新发布后读取新版本前未重新投影，按既有门禁AUTHZ_STATE_NOT_READY拒绝；仅补fixture的READY步骤，再跑2真PG／图PASS，未放宽产品。
- Commerce没有wrapper，reactor限定单测又触发既有依赖模块“必须有测试”规则；未修改POM。完整reactor先构建安装，再在app模块运行本片3测试。两种Maven本地仓库不同，用既有systemMaven安装方式对齐SDK，并核对嵌套制品。
- 实际HTTP第一轮受限MySQL账号在binlog开启时不能执行既有V48触发器迁移；第二轮改migration owner仅初始化本工具新建库，之后恢复受限账号。没有授SUPER给运行账号，没有改共享global配置或已执行迁移。
- 第二轮导航全链路通过，到fixture撤权因脚本误期待202、实际旧端点200中止；仅修脚本。第三轮22项全部PASS，所有旧库／日志保留。
- 演练只写新专用库。核对期间目录／角色／Grant／执行引用不变。自有进程全部退出；原部署／角色授权／共享组件和历史工作树保留，无清理授权不删除。
