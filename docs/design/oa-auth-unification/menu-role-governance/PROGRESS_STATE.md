# 菜单与角色授权治理实施状态

## 目标
持续执行已确认的 [PLAN](PLAN.md) 和 [唯一任务表](IMPLEMENTATION_SLICES.md)，先交付阶段 A；不把一个切片完成等同全部完成。

## 当前状态
- 用户已要求开始实施，计划 APPROVED。
- MG00 DONE：阶段 A [CONTRACTS](CONTRACTS.md) 已冻结并核对原快照／展示／权限边界。
- MG01 DONE：结构化声明／导航／固定提交导出和双工具一致性已验证（见 MG01_TEST_RESULT）。
- MG02 DONE：完整菜单差异／非法候选发布保护，198单测、14真PG、真实Owner HTTP和三宽度弹层通过（见 MG02_TEST_RESULT）。
- MG03 DONE：独立诊断影响／有界联合分页／全量去重统计，202单测、34真PG、18真实HTTP和三宽度组件验收通过（见 MG03_TEST_RESULT）。
- MG03提交e7c8cb8已正常合并／推送main，精确CI37103092332 SUCCESS。
- MG04 DONE：来源／原回执／历史，205单测／19真PG／13真实HTTP／4组组件交互与三宽度截图PASS（见 MG04_TEST_RESULT）；提交2548922已任务分支及main正常推送，精确CI37103806264 SUCCESS。
- MG05 DONE：固定预览／双摘要与独立影响／单向旧写门禁，206单测／32真PG／36 HTTP与CLI／5组页面／三宽度视觉PASS（见 MG05_TEST_RESULT）。提交973c94f已任务分支／main推送，精确CI37105196879 SUCCESS。
- MG06 DONE：只读来源／部署声明漂移及具体差异，208单测／19真PG／35前端／3工具／18真实HTTP／5页面组／三宽度视觉通过（见MG06_TEST_RESULT）；提交e9f9e3a已任务分支／main正常推送，精确CI37106012842 SUCCESS；MG07 DONE、MG08 DONE、MG09设计DONE、MG10 DONE、MG11 DONE、MG12 DONE、MG13 DONE（Git／CI SUCCESS）、MG14 TODO（业务选择待答）、MG15 DONE（Git／CI收尾）、MG16–MG19 TODO。
- MG07 DONE：281单测＋2真PG图，Commerce3单测／完整构建／实际22HTTP及固定SDK109b1ed源码和嵌套制品通过；生产方109b1ed、消费方8da6f4b已main正常推送（见MG07_TEST_RESULT）。Auth109b1ed精确CI37107379738 SUCCESS；Commerce8da6f4b精确CI37107505687／37107501809 SUCCESS。
- Auth原目录 feat/menu-role-governance-a（074bfed基线）；Commerce原目录 feat/menu-catalog-source（c9eb50c基线）。
- 生产部署未授权／未执行；原本机目录／业务Grant未写入。

- MG08 DONE：5导航规则／4目录工具／类型构建／25真实HTTP／15浏览器组／36路由三宽度公开DTO回归／14图实看PASS（见MG08_TEST_RESULT）。Commerce57804ef已任务分支／main正常推送，精确CI37109049960／37109045619 SUCCESS；Auth验收工具／进度2f644e7已main正常推送。

## 下一步
MG12产品／必要验证／Git DONE，见[MG12_TEST_RESULT](MG12_TEST_RESULT.md)：245单测、13真实PG、43支持HTTP（16项MG12）、8组实际页面、当前9图实看及六类表逐行不变通过。产品8b9addeb254eb057baadefea078216f424d096fe已正常合并／推送main，精确CI37132652622 completed SUCCESS。MG13产品／必要验证DONE，见[MG13_TEST_RESULT](MG13_TEST_RESULT.md)：249单测／25真PG／6真实图、28 HTTP／6页面组／9当前图，另43 HTTP／8组预览共享回归及9图通过；21路径指纹10a69fdc941f15d460b7e2b7687bc435c55cc43514546a6f6db805c0764210f0。所有owned Admin／Vite已退出，产品271941777ea7892022ee786dad529e12462713d2已正常合并／推送main，精确CI37135722188 completed SUCCESS。MG14待业务规则，MG15实施中，MG16–MG19仍TODO，全计划ACTIVE。用户对推荐迁移顺序回复“继续”，已说明按先撤旧／确认撤权／再授新的解释，不承诺访问不中断。

D-ENV仍为独立权限实例／数据库。MG12不增加新中间件；非扩权DIRECT首先支持，新增能力走独立授权／审批，组和OA来源交MG14。实际Grant迁移或生产部署未在本片执行。

## 验证记录
MG00：结构化声明、差异类别、非法变化、双哈希、分区诊断权限、发布幂等和旧写路径门禁、UNKNOWN运行状态及UI验收均有正式规则；文档链接／diff检查无阻断。产品验证从MG01开始，不引用历史CI为本轮证明。

## 阶段 C 当前证据

MG09设计PASS_WITH_LIMITATIONS（MG09_TEST_RESULT），正式架构／选型／MG09_CONTRACT已落盘。Casdoor固定提交4文件／7项源码核对PASS，MG09当时安装版与PG验收未完成且环境超时，历史证据保留；用户确认调整Docker，MG10环境恢复后已完成真实安装版Token／PG和双实例验证，隔离运行gate PASS。环境选择无需再确认：独立权限实例／DB，可信IdP可复用但各环境client／audience／密钥不同。MG10 DONE，仍默认关闭；后续MG11只在获授权隔离目标验证，不生产部署或开启。MG10产品8ad560d已任务分支／main正常推送；发现CI测试配置入口需兼容既有环境变量，18规则／15真PG按CI方式补证PASS，4371b486888118c0c3e78304002d18dbbcd1f87c补丁已main正常推送，精确CI37112631055 completed SUCCESS。

MG11固定输入／预览／原命令恢复工具DONE；新增workflow只执行协议测试及语法检查，不运行真实生产发布。客户端工作树指纹75e7ff41e192f73a27c2a6bbc9d2644402460c80062a0b632acd75a9df5f9a55、最终真实轮次mg10-http-b05285bb0133和卫生限制记录于MG11_TEST_RESULT及私密证据。MG12–MG19整体未完成；D-MIG按用户对推荐顺序回复继续的上下文推进，实际生产部署仍未授权。

MG14的D-SOURCE业务问题已异步提出：存量OA是否必须重新审批，或允许原审批负责人确认缩权；尚无答复，不执行依赖该规则的OA迁移。组来源保持GROUP及当前动态组资格，不能转成个人DIRECT。MG15仅依赖MG05／MG12，按[MG15_CONTRACT](MG15_CONTRACT.md)冻结ACTIVE／DEPRECATED独立元数据及停止新增使用矩阵；产品及必要验收DONE，V27／V28只在独立测试库应用；251单测／49真实PG／3真实图、36前端模型、最终20HTTP／6页面组／9图PASS，见[MG15_TEST_RESULT](MG15_TEST_RESULT.md)。31路径指纹9a388d4c872bfd4eb80927eb22e136360e8e6631f4e3f8bd22ab8bf167f9b4f2；Git／CI收尾，原业务Grant写入0。
