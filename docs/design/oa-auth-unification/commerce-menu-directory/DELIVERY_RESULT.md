# 电商真实菜单交付结果

产品提交b918d87969b0b415c134db5a45737a798d01bbdd，经测试后已正常合并/推送main；任务分支feat/commerce-menu-directory。最终交付文档单独提交，不改变部署代码。

- console镜像：auth-platform/governance-console:rev-b918d87969b0，sha256:bef8b8c2e0e41023851d9a7236e375f70cc05b18d35728b369525d06c3ab90fa。
- admin/projector镜像：auth-platform/governance-admin:rev-b918d87969b0，sha256:b0ead032291fce67d798529d7daac8e51b1d9ca1aba1649186ae553a3a853da0。
- CI37096950007 SUCCESS；只部署已授权local5273，三服务healthy；未部署生产，原其他容器保留。
- 发布：已认证原Owner发布commerce v2；保存不可变manifest/展示快照与事务审计。42真实菜单节点含6组35经营页1协作页；原2入口及store.manage保留，共44节点123能力。角色、Grant、委派、身份与旧快照未改。
- 访问：`http://localhost:5273/governance/menus`，选择local-commerce / commerce / local。左侧中文菜单树可搜索/滚动，右侧页面路由、直接与子菜单权限、固定角色关联。权限目录/角色表单共用数据库名称。
- 原角色store_reader仅含store.read；实际商家与门店使用store.directory.read，页面忠实显示无关联，不以近似命名扩权。
- 清理核查：原目录工作，无新worktree；.local/commerce-menu-directory私密证据/回退配置/原库备份/专属测试库和迁移退出容器需保留，未删除、强推或重置历史。Root CODEX_PROGRESS.md沿用既有忽略规则，不强制纳入Git。

技能Handoff：task-git-delivery / deployment-execution COMPLETED，gate PASS；请求ref与已部署ref完全一致，health/smoke PASS，首次迁移步骤漏执行的回退与最终重试均有真实证据，详情见TEST_RESULT。
