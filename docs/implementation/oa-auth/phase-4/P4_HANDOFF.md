# P4 交接

P4-01至P4-07实现和G4真实验收完成；Git交付/远程CI以P4_DELIVERY_RESULT和CI_RESULT为准。原63节点未改，P5未授权，不自动继续。

后续P5复用CONTRACTS_P4_REQUEST：本人策略分页/提交/详情/列表/执行/取消/通知；OA指定办理人读取固定审批依据并调用原任务办理。审批APPROVED与执行ACTIVE不能混用，source回收只删除本Grant；other_active_grant_count仅提示其他来源存在。

auth V13—V16与OA V25/V26已实际执行，不改迁移历史。OA先依赖auth协议的已发布精确SHA，再正常交付；两个任务分支都在原项目目录，无新增worktree。OA四项用户既有文件保持不动；`.local`私密报告和tmp均不纳入提交。

复验遵循RUNTIME_SPEC。所有E2E应用进程由finally退出，隔离基础设施及私密配置/卷保留供复验，未经清理授权不删除。原目录治理/P3共享Casdoor升级等限制保持原结论。
