# MG02 完整菜单差异验证

状态：产品验证 PASS；全计划 ACTIVE，继续MG03。未生产部署，未修改原Commerce v2或业务Grant。

## 行为与边界
- Owner预览按稳定menu.code展示新增／删除／修改；名称、顺序、父级、路由、any_of前后事实和潜在关联能力完整返回。
- 父节点移动包括两版子树；输入／any_of顺序归一化不产生差异。旧content_hash不含展示字段，presentation_hash独立计算。
- 能力删除或语义变化、版本倒退、同版内容变化有具体violations，publishable=false。结构非法仍400，实际非法发布仍409；Owner边界不变。
- 差异可搜索／分类／展开；编辑作废预览，旧服务缺字段显示不可用并禁止发布；命令未知时冻结内容／关闭，原键原体重试。

## 验证
- 最新Auth protocol/core/governance/admin真实编译和198单测 PASS（20+27+97+54），新增8项纯差异业务测试。
- 14项真实隔离PG目录回归 PASS，验证只读预览不增加目录审计／角色／Grant、显示快照兼容、非法实际发布、并发唯一和审计原子回滚。当前原数据库未改动。
- Console类型检查／生产构建和21项既有行为测试 PASS。Python工具编译／Node语法／diff和修复后卫生门禁无阻断；统一formatter发现限制如实保留，无新增格式化器依赖。
- 真实Casdoor PKCE HUMAN Owner＋实际新Admin JAR／隔离PG：6个HTTP边界检查 PASS，包括完整差异、外部HUMAN403、匿名401、结构400、可审查语义冲突和实际发布409。
- 实际CatalogEditor组件测试壳（不是完整门户PKCE演练）通过真实Token和实际HTTP，6组交互检查 PASS；1440/390/320的前／后事实及非法原因9截图均检查布局。无横向溢出；移动端上下展示，固定底部操作可用。
- 测试只对随机mg02隔离应用发布，模拟已提交响应丢失后原键原体重试；不写原电商目录、任何Grant或角色。只启动本次Popen回环进程，结束已停止。

## 证据与失败闭环
- .local/menu-role-governance/mg02-unit.log、mg02-final-build.log、mg02-pg-final.log、mg02-console-final-*、mg02-hygiene.log保留。
- 最终HTTP／浏览器证据在 .local/menu-role-governance/mg02-edd2b9902b8d，布局9图查看在同等UI分支mg02-638e1ebc798a；早期证据保留。
- 首次HTTP发现增量Boot repackage复用了旧嵌套治理JAR；没有据构建退出码误判PASS。forceCreation重新打包，并在验收工具加入整个嵌套JAR一致性校验，最终通过。
- 首次卫生门禁在Git动作开始后才检查返回，发现前端状态字面量比较与测试CLI console输出阻断。已改为typed映射／聚合、CLI stdout与有名称的超时常量；窄构建／6组真实HTTP组件回归及mg02-hygiene-fixed均通过，常量启发式ADVISORY已归类，禁止再据未完成门禁声明可交付。
- 首轮截图没有完整露出手机事实／桌面错误原因；改为实际滚动到前后区域和完整错误框，最终9图已查看。
- MG01 Auth精确CI37100573760 SUCCESS；Commerce首次37100533319因Node JSON导入属性失败，47ebb65修复后CI37100968743待终态。MG02精确Git／CI结果后续追踪，不引用历史成功代替本次。

## 恢复
从MG03的独立分区诊断契约和真实PG聚合实现继续；MG02已验证功能不重复重构，不运行旧Owner电商发布脚本，不自动生产部署。
