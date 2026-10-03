# 电商真实菜单交付进度

- 需求：5273显示电商实际中文菜单、层级、页面路由和菜单权限/角色关联。
- CM01 DONE：V20不可变展示快照，同Owner权限发布事务；兼容既有schema1权限JSON/hash。
- CM02 DONE：数据库名称/排序/中文搜索/默认页面选择/菜单角色关联，共用选择器、角色详情及业务入口名称。
- CM03 EXPORT_DONE / RUNTIME_VERIFYING：从不可变Commerce HEAD导航与已验收Owner目录导出44节点/123能力，保留本机旧项；不创建角色/Grant、不扩委派。精确CI、local部署/出版/浏览器待验收。
- 授权：用户已确认连续实现及正常Git main推送；延续本机5273更新授权，无生产部署。
- 原目录任务分支feat/commerce-menu-directory；Commerce目录已有其他任务改动，本任务仅读取其Git blob。
- 原业务库已私密备份；授权表与历史快照已保存用于逐行验收。
- 下一步：交付已验证产品提交、CI与immutable构建，完成真实Owner发布和本机页面只读验证，记录最终状态。
