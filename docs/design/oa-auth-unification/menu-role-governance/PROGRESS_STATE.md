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
- MG06 DONE：只读来源／部署声明漂移及具体差异，208单测／19真PG／35前端／3工具／18真实HTTP／5页面组／三宽度视觉通过（见MG06_TEST_RESULT）；提交e9f9e3a已任务分支／main正常推送，精确CI37106012842 SUCCESS；MG07 DONE、MG08 DONE、MG09设计DONE、MG10 DONE、MG11 IN_PROGRESS、MG12–MG19 TODO。
- MG07 DONE：281单测＋2真PG图，Commerce3单测／完整构建／实际22HTTP及固定SDK109b1ed源码和嵌套制品通过；生产方109b1ed、消费方8da6f4b已main正常推送（见MG07_TEST_RESULT）。Auth109b1ed精确CI37107379738 SUCCESS；Commerce8da6f4b精确CI37107505687／37107501809 SUCCESS。
- Auth原目录 feat/menu-role-governance-a（074bfed基线）；Commerce原目录 feat/menu-catalog-source（c9eb50c基线）。
- 生产部署未授权／未执行；原本机目录／业务Grant未写入。

- MG08 DONE：5导航规则／4目录工具／类型构建／25真实HTTP／15浏览器组／36路由三宽度公开DTO回归／14图实看PASS（见MG08_TEST_RESULT）。Commerce57804ef已任务分支／main正常推送，精确CI37109049960／37109045619 SUCCESS；Auth验收工具／进度2f644e7已main正常推送。

## 下一步
MG09设计及MG10默认关闭的受限机器发布DONE，继续MG11 CI发布工具。MG10证据见[MG10_TEST_RESULT](MG10_TEST_RESULT.md)：242单测、48真PG、37真实HTTP／CLI及卫生PASS（工具限制）；运行能力在隔离目标已验证，不代表生产开启授权。MG08两仓Git已完成；Auth2f644e7不命中现有workflow路径过滤，精确查询[]，不声称CI通过。MG06精确CI已SUCCESS。D-ENV已获明确答复：测试／生产使用独立权限实例与数据库隔离；MG09据此细化身份／目标契约，不自动生产部署。后续新增决策按对应片处理，保留全计划目标。

## 验证记录
MG00：结构化声明、差异类别、非法变化、双哈希、分区诊断权限、发布幂等和旧写路径门禁、UNKNOWN运行状态及UI验收均有正式规则；文档链接／diff检查无阻断。产品验证从MG01开始，不引用历史CI为本轮证明。

## 阶段 C 当前证据

MG09设计PASS_WITH_LIMITATIONS（MG09_TEST_RESULT），正式架构／选型／MG09_CONTRACT已落盘。Casdoor固定提交4文件／7项源码核对PASS，MG09当时安装版与PG验收未完成且环境超时，历史证据保留；用户确认调整Docker，MG10环境恢复后已完成真实安装版Token／PG和双实例验证，隔离运行gate PASS。环境选择无需再确认：独立权限实例／DB，可信IdP可复用但各环境client／audience／密钥不同。MG10 DONE，仍默认关闭；后续MG11只在获授权隔离目标验证，不生产部署或开启。MG10 Git与精确CI下一收口。
