# MG04 发布来源与历史验证

状态：PASS，MG04 DONE；全计划ACTIVE，继续MG05固定预览发布门禁。未生产部署，未发布原Commerce目录或写原业务Grant。

## 实现

- V21独立追加不可变catalog_release；全表／每列中文注释、版本快照FK、应用命令唯一、来源成对、摘要／版本／回执大小约束及不可更新删除触发器齐全。原v1清单、展示sidecar、旧迁移／哈希不修改，旧发布不回填。
- 原目录事务在同一应用锁下写快照／展示／指针／审计和元数据。新来源发布Candidate独立于Manifest；原入口新发布来源UNKNOWN。当前Owner认证后查原命令，同体返回原Preview／Release，不重算当前差异或倒退指针；改内容／来源／原因冲突。
- 受控维护CLI新增publish-source，仍依既有0600引导配置和当前Owner身份绑定；它是维护工具，不作为未来机器身份／CI发布授权。公开新增HTTP仅Owner历史列表／固定版本详情。MG05新增固定预览HTTP后由页面消费。
- 历史按固定版本降序100条分页，老快照来源／回执空；固定版本详情校验内容和显示摘要。NOT_FOUND明确404，跨Owner403，失败503不能显示为空历史。
- CatalogEditor内按需读取历史、来源、真实发布主体／时间／原因／决定／原命令、原预览和固定菜单；原预览标注以当时基础版本为准。

## 验证

- 205项编译／单测PASS（20+27+104+54），新增CatalogPublicationTest3验证已知／未知来源、严格JSON／数值与枚举／主体注入／字节和决定原因。
- 19项真实隔离PG PASS（Release5/Catalog6/Published8），验证原回执在后续版本后保持、同键改来源／内容拒绝、原接口UNKNOWN、旧历史120版本分页／详情／跨Owner、审计和元数据失败完整回滚、历史更新删除拒绝、三请求同命令并发仅一次发布，原角色／Grant不创建。
- Console类型检查／生产构建和28项行为测试PASS（历史新增3）；缺游标、倒序／重复／跨应用／不匹配固定详情拒绝。
- 实际新Admin JAR与真实Casdoor PKCE、隔离PG：13项HTTP检查PASS；源码CLI原键重试在发布v2之后不退指针，历史HTTP展示v1声明来源和v2未知来源。测试声明a/b摘要仅夹具，不宣称真实Commerce源码或部署。
- 实际CatalogEditor组件壳4组交互PASS（不是完整门户PKCE演练），列表／固定来源／旧实际菜单9截图覆盖1440/390/320。最终 `.local/menu-role-governance/mg04-add0809c5379`，列表padding调整后三图再实看，其余同UI状态9图此前在9a830819dcb5实看。长摘要／JSON可换行，无横向溢出，固定底部操作可用。
- 浏览器0管理写入；HTTP／CLI只写本次随机隔离应用的v1/v2，原Commerce和业务Grant0写入。自有回环Admin/Vite进程已结束。
- Python编译／Node语法／diff PASS，mg04-hygiene-final为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS、无BLOCKING；统一formatter／静态分析未配置的限制保留，预算常量已归类，新增SQL只在Mapper／迁移，事务无远程副作用。

## 失败和恢复

- 首次PG两处夹具错误：发布失败约束误拒绝已存在REGISTER行，及字符串source子串误匹配resource_type；改为仅定向PUBLISH／完整旧清单JSON字节等值后全部通过，没有放松产品约束。
- 首次HTTP把规范化排序菜单与原输入数组直接比较；按原归一化契约生成准确期望后通过。
- 不删除新历史回退程序，不将旧未知来源补造成固定提交。MG05启用GUARDED前升级调用方并退出旧写节点；生产目标未授权，本片未启用生产门禁。
- 当前MG03 e7c8cb8精确CI37103092332 SUCCESS；Commerce回执文档48a181b已main。MG04 Git／精确CI在进度继续记录。
