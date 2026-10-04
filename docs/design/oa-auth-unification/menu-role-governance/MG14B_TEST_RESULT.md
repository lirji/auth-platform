# MG14-B：OA重新审批角色迁移验收

2026-10-03，Validation COMPLETED／PASS。按[MG14_CONTRACT](MG14_CONTRACT.md)完成OA专项路径；与MG14-A合并后父MG14产品及必要验证DONE。Git与精确CI单独记录，全计划ACTIVE。

本人从原APPROVED申请发起升级，只列兼容策略，固定原范围和排他截止；新版批准来自独立新申请的签名回调和真实Inbox处理。批准后保留原来源，显示待管理员切换。管理员显式选择新版批准，真实撤旧证明后才创建OA_REQUEST新来源。缺少来源或批准元数据时只读，开始推进时重新读取并复核。原批准、来源类型、范围、截止和其他独立来源不被伪造或覆盖。

| 验收 | 实际结果 |
|---|---|
| 后端 | 第四轮actual exit0／BUILD SUCCESS：251单测（protocol20／core27／governance150／admin54）；62真实PG（GROUP6／任务13／普通申请19／预览13／OA11）；13真实图（迁移12／普通申请投影1）。 |
| OA与持久化 | 真签名Inbox批准但无Grant、显式绑定新批准、旧APPROVED／伪造SQL批准拒绝、当前代际与策略、非扩权及精确范围、原／新申请取消、批准取消并发、真实6秒截止生命周期。V31只在本任务独立库执行，四个新增字段中文注释实际核对；不改已执行V29／V30／V31。 |
| 图与兼容 | OA两组真实图验证先撤旧后授新、正确分区回执、新OA_REQUEST原范围及截止、原取消不撤新独立来源、新取消只撤新源、其他DIRECT保留以及最后实际DENY；旧DIRECT／GROUP回归和旧命令摘要兼容通过。 |
| 前端 | 最新类型检查／生产构建PASS。申请升级、等待切换、新批准关联、来源缺失只读及刷新恢复、原键恢复沿用现有组件和真实接口。 |
| HTTP | 最终mg14b-http-e85f4b288762，31检查PASS，无传输重试。实际Auth Inbox／consumer、PostgreSQL及真实图；原业务Grant写入0。 |
| 浏览器 | 同轮7组PASS：固定范围／截止申请、丢失已提交202的同命令恢复、批准待切换无新Grant、显式绑定新版批准、跨读取元数据丢失不推进、实际迁移各阶段与回执、待切换新版撤回保留原授权。0页面／控制台错误。 |
| 视觉 | 申请表／批准待切换／迁移完成／新版撤回各1440／390／320，共12张当前截图逐张实看。内容可读且窄屏无body溢出。 |
| 审查与卫生 | 无BLOCKING findings，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。HF001/002已人工复核5秒短事务、分区→任务锁序、无事务内HTTP、Grant／Outbox／批准绑定／检查点／命令／审计原子回滚及批准取消同分区串行。HF003/004为已命名的CLI／页面／启动／PG／回调工具预算常量。既有formatter缺失、静态分析器N/A。 |

26产品／测试／工具路径指纹`2e6a82f564f4b4d3ff74941cd9e3bd54c966570ac8a12369cd21ea8bd87512ab`，最终运行后逐文件核对未变。真实运行Jar SHA256为`75e6f9c1b6e5a26be0cc3ef3ad4730d5cdb47ad70f052cde5cc1221560e95b02`。私密日志、source-fence、hygiene和evidence保存在忽略的.local/menu-role-governance，不提交凭据。

## 失败保留与验证范围

第二轮后端专项State导入歧义修正；第三轮11/12迁移图通过但既有GROUP图读取超时，整轮FAIL；第四轮全部成功。HTTP首轮策略夹具资源不兼容被实际403拒绝；次轮31HTTP通过但浏览器依赖加载失败，整体FAIL；修正既有Playwright模块路径后第三轮完整通过，无新增依赖。所有失败日志保留，不由部分成功覆盖整轮失败。

外部OA是签名HTTP契约夹具（SIGNED_HTTP_CONTRACT_FIXTURE_NOT_REAL_OA_ENGINE），不声称真实OA引擎端到端验收或生产审批流已更新；Auth实际启动协议、签名回调Inbox／消费、数据库与图均真实执行。此切片不改OA项目或部署生产。

自有Admin21821／Vite21822／夹具21823已退出并实际核对端口关闭；完整任务期间保留独立18094 IdP、测试数据库和证据。未重发原Commerce目录、未写原业务Grant、未更新原5273、未部署生产。下一串行MG16引用退出与退役，随后MG17–19。
