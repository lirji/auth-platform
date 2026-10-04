# 菜单与角色授权治理实施状态

## 目标
持续执行已确认的 [PLAN](PLAN.md) 和 [唯一任务表](IMPLEMENTATION_SLICES.md)，持续完成阶段 A–F；不把一个切片完成等同全部完成。

## 当前状态
- 用户已要求开始实施，计划 APPROVED。
- MG00 DONE：阶段 A [CONTRACTS](CONTRACTS.md) 已冻结并核对原快照／展示／权限边界。
- MG01 DONE：结构化声明／导航／固定提交导出和双工具一致性已验证（见 MG01_TEST_RESULT）。
- MG02 DONE：完整菜单差异／非法候选发布保护，198单测、14真PG、真实Owner HTTP和三宽度弹层通过（见 MG02_TEST_RESULT）。
- MG03 DONE：独立诊断影响／有界联合分页／全量去重统计，202单测、34真PG、18真实HTTP和三宽度组件验收通过（见 MG03_TEST_RESULT）。
- MG03提交e7c8cb8已正常合并／推送main，精确CI37103092332 SUCCESS。
- MG04 DONE：来源／原回执／历史，205单测／19真PG／13真实HTTP／4组组件交互与三宽度截图PASS（见 MG04_TEST_RESULT）；提交2548922已任务分支及main正常推送，精确CI37103806264 SUCCESS。
- MG05 DONE：固定预览／双摘要与独立影响／单向旧写门禁，206单测／32真PG／36 HTTP与CLI／5组页面／三宽度视觉PASS（见 MG05_TEST_RESULT）。提交973c94f已任务分支／main推送，精确CI37105196879 SUCCESS。
- MG06 DONE：只读来源／部署声明漂移及具体差异，208单测／19真PG／35前端／3工具／18真实HTTP／5页面组／三宽度视觉通过（见MG06_TEST_RESULT）；提交e9f9e3a已任务分支／main正常推送，精确CI37106012842 SUCCESS；MG07 DONE、MG08 DONE、MG09设计DONE、MG10 DONE、MG11 DONE、MG12 DONE、MG13 DONE（Git／CI SUCCESS）、MG14 DONE（GROUP与OA必要验收PASS，Git／CI见当前记录）、MG15 DONE（Git／精确CI SUCCESS）、MG16–MG19 TODO。
- MG07 DONE：281单测＋2真PG图，Commerce3单测／完整构建／实际22HTTP及固定SDK109b1ed源码和嵌套制品通过；生产方109b1ed、消费方8da6f4b已main正常推送（见MG07_TEST_RESULT）。Auth109b1ed精确CI37107379738 SUCCESS；Commerce8da6f4b精确CI37107505687／37107501809 SUCCESS。
- Auth原目录 feat/menu-role-governance-a（074bfed基线）；Commerce原目录 feat/menu-catalog-source（c9eb50c基线）。
- 生产部署未授权／未执行；原本机目录／业务Grant未写入。

- MG08 DONE：5导航规则／4目录工具／类型构建／25真实HTTP／15浏览器组／36路由三宽度公开DTO回归／14图实看PASS（见MG08_TEST_RESULT）。Commerce57804ef已任务分支／main正常推送，精确CI37109049960／37109045619 SUCCESS；Auth验收工具／进度2f644e7已main正常推送。

## 下一步
MG12产品／必要验证／Git DONE，见[MG12_TEST_RESULT](MG12_TEST_RESULT.md)：245单测、13真实PG、43支持HTTP（16项MG12）、8组实际页面、当前9图实看及六类表逐行不变通过。产品8b9addeb254eb057baadefea078216f424d096fe已正常合并／推送main，精确CI37132652622 completed SUCCESS。MG13产品／必要验证DONE，见[MG13_TEST_RESULT](MG13_TEST_RESULT.md)：249单测／25真PG／6真实图、28 HTTP／6页面组／9当前图，另43 HTTP／8组预览共享回归及9图通过；21路径指纹10a69fdc941f15d460b7e2b7687bc435c55cc43514546a6f6db805c0764210f0。所有owned Admin／Vite已退出，产品271941777ea7892022ee786dad529e12462713d2已正常合并／推送main，精确CI37135722188 completed SUCCESS。MG14 GROUP与OA必要验收DONE，MG15产品／必要验证／Git DONE，精确CI37137909570 completed SUCCESS；MG16–MG19仍TODO，全计划ACTIVE。用户对推荐迁移顺序回复“继续”，已说明按先撤旧／确认撤权／再授新的解释，不承诺访问不中断。

D-ENV仍为独立权限实例／数据库。MG12不增加新中间件；非扩权DIRECT首先支持，新增能力走独立授权／审批，组和OA来源交MG14。实际Grant迁移或生产部署未在本片执行。

## 验证记录
MG00：结构化声明、差异类别、非法变化、双哈希、分区诊断权限、发布幂等和旧写路径门禁、UNKNOWN运行状态及UI验收均有正式规则；文档链接／diff检查无阻断。产品验证从MG01开始，不引用历史CI为本轮证明。

## 阶段 C 当前证据

MG09设计PASS_WITH_LIMITATIONS（MG09_TEST_RESULT），正式架构／选型／MG09_CONTRACT已落盘。Casdoor固定提交4文件／7项源码核对PASS，MG09当时安装版与PG验收未完成且环境超时，历史证据保留；用户确认调整Docker，MG10环境恢复后已完成真实安装版Token／PG和双实例验证，隔离运行gate PASS。环境选择无需再确认：独立权限实例／DB，可信IdP可复用但各环境client／audience／密钥不同。MG10 DONE，仍默认关闭；后续MG11只在获授权隔离目标验证，不生产部署或开启。MG10产品8ad560d已任务分支／main正常推送；发现CI测试配置入口需兼容既有环境变量，18规则／15真PG按CI方式补证PASS，4371b486888118c0c3e78304002d18dbbcd1f87c补丁已main正常推送，精确CI37112631055 completed SUCCESS。

MG11固定输入／预览／原命令恢复工具DONE；新增workflow只执行协议测试及语法检查，不运行真实生产发布。客户端工作树指纹75e7ff41e192f73a27c2a6bbc9d2644402460c80062a0b632acd75a9df5f9a55、最终真实轮次mg10-http-b05285bb0133和卫生限制记录于MG11_TEST_RESULT及私密证据。MG12–MG19整体未完成；D-MIG按用户对推荐顺序回复继续的上下文推进，实际生产部署仍未授权。

MG14的D-SOURCE业务问题已异步提出：存量OA是否必须重新审批，或允许原审批负责人确认缩权；尚无答复，不执行依赖该规则的OA迁移。组来源保持GROUP及当前动态组资格，不能转成个人DIRECT。MG15仅依赖MG05／MG12，按[MG15_CONTRACT](MG15_CONTRACT.md)冻结ACTIVE／DEPRECATED独立元数据及停止新增使用矩阵；产品及必要验收DONE，V27／V28只在独立测试库应用；251单测／49真实PG／3真实图、36前端模型、最终20HTTP／6页面组／9图PASS，见[MG15_TEST_RESULT](MG15_TEST_RESULT.md)。31路径指纹9a388d4c872bfd4eb80927eb22e136360e8e6631f4e3f8bd22ab8bf167f9b4f2；产品36f614ba8751b169ab9d0117105e757694e37ef9已任务分支及main正常推送；精确[CI37137909570](https://github.com/lirji/auth-platform/actions/runs/37137909570) completed SUCCESS（精确head36f614ba8751b169ab9d0117105e757694e37ef9），原业务Grant写入0。


## 此前依赖与业务问题（历史记录，已被下方确认取代）

MG14的OA规则仍待答复：每次存量升级重新申请审批，或允许原审批负责人确认缩权迁移、扩权仍重新审批；不能将旧APPROVED视为新版批准。MG17的D-HR也已异步提出：先人工核对／负责人复核，或岗位映射立即切换，或有限交接期；尚无业务选择，不据“继续”或等待时间推断答案。

等待期间已完成只读源码核对：[MG16_DESIGN_NOTES](MG16_DESIGN_NOTES.md)（DRAFT）定位未来生效授权、在途申请／迁移及项目API未核验的引用缺口；[MG17_DESIGN_NOTES](MG17_DESIGN_NOTES.md)（DRAFT）记录OA事实权威、当前人员内容与历史前后内容缺失、成员代际和诊断／生命周期写权限边界。两个产品切片仍TODO，不把分析保存当实现或验收通过。

收到业务选择后从MG14正式契约和组／审批来源实现继续；MG16依赖MG14，MG17依赖MG14及D-HR，MG18依赖MG17，MG19依赖MG16／MG18，当前不能跳过依赖宣称全计划完成。MG15新页面只在隔离运行目标验收，原5273没有在本片部署更新；生产部署未授权。


## 业务选择确认后的恢复

用户对两个推荐方案回复“确认”，已告知并按OA重新审批、原截止切换，以及调岗人工核对／负责人复核、不自动按岗位授予落实。MG14产品／必要验收DONE，按[MG14_CONTRACT](MG14_CONTRACT.md)串行完成GROUP和OA；旧“待答”段落为此前历史，当前不再有这两项业务等待。D-HR责任人首版由获授权创建者显式选择有当前诊断资格的负责人，后续MG18契约具体化，不新增默认岗位映射。全计划ACTIVE，未生产部署。

MG14-A验收见[MG14A_TEST_RESULT](MG14A_TEST_RESULT.md)：251单测／31真实PG／10真实图、29HTTP／8页面组／9图实看PASS，22源指纹3347a10fa0858f9518e670d3fc3c1e88c72aa80380db8fcade4b236846c96601。原业务Grant写入0，自有Admin／Vite已退出。父MG14仍IN_PROGRESS，MG16–19依赖尚未放行。

MG14-A产品593653920a8ffae2eb78485ad7d70f76aba3fe37和格式限制文档a13a02b5f49954196b71485a7e64dfbd875df403已正常提交／ff合并／两分支推送；当前main和任务分支均a13a02b。精确CI37163694601（head a13a02b，包含已验证产品且之后仅文档）completed SUCCESS，28步骤成功。MG14-B接口FROZEN并进入实施，父MG14仍IN_PROGRESS。

MG14-B产品／必要验证DONE，见[MG14B_TEST_RESULT](MG14B_TEST_RESULT.md)：251单测／62真PG／13真实图、最新类型构建、最终mg14b-http-e85f4b288762的31HTTP／7页面组／12当前图实看PASS。26源指纹2e6a82f564f4b4d3ff74941cd9e3bd54c966570ac8a12369cd21ea8bd87512ab；无BLOCKING卫生项，四ADVISORY人工复核。原业务Grant写入0，自有21821／21822／21823已退出。真实OA引擎未联调，外部OA是签名HTTP契约夹具，实际Auth Inbox／consumer、PG和图真实执行。失败证据保留。

父MG14产品／必要验证DONE，MG14-B Git／精确CI待本次交付。下一MG16引用退出与退役，MG17–19仍TODO；全目标ACTIVE，不等待用户再次确认或输入继续。上方MG14 IN_PROGRESS／待答／运行中段落为各阶段历史。
