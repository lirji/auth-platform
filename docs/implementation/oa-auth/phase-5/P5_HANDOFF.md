# P5 Handoff

授权范围为P5-01..07；P6前停止，没有生产部署。Q-EXT已由用户选定真实商城门店/商家协作，不再作为待确认项。

- P501组织/应用工作台；P502目录、不可变角色版本、范围授予与真实投影状态；P503邀请、固定申请策略、申请/通知及OA审批依据；P504独立权限来源、显式诊断范围和单来源回收回执。详情见各P5-xx_TEST_RESULT。
- P505内部真实商品查询及受控元资料修订，保留Owner数据库范围/版本/Store活动不变量；P506受邀PARTNER读取S001/P001、独立限时导出申请、真实OA批准、投影实际生效、持久导出、到期/撤销旧文件拒绝，独立read来源保留；P507三应用真实交互PKCE/SSO、同源JAR、Cookie/CORS/错误/开关验收。
- 原63节点DAG保留，无节点重编号、无架构重选、无生产目录接管。P6-01是后续依赖就绪候选，需要用户下一阶段授权。
- auth仍为治理权威，OA是审批提供方，commerce为商品/门店数据Owner。既有read能力不隐含product.export；旧read指纹商品导出不能直接续跑。
- 生产共享Casdoor8000仍受旧版契约HOLD；本片使用隔离18090已验证版本。没有宣称旧生产Identity配置可直接上线。
- 状态对齐以PROGRESS_STATE、各片TEST_RESULT及P5_DELIVERY_RESULT/CI_RESULT为准。Git交付不会改OA用户既有CODEX_PROGRESS.md等未提交内容。

本次测试运行器在finally停止所持JVM/Vite。私有`.local`、独有客户端、数据库/卷保留供复查，不默认删除。未新建worktree。

恢复：先读根CODEX_PROGRESS.md和PROGRESS_STATE，再核对实际Git/Actions；不要重做已验收切片或再次询问Q-EXT。运行复现与回退见P5_RUNTIME。
