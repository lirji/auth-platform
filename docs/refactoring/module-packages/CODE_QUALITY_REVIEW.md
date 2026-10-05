# Auth 模块结构与代码规范审查

范围是 Auth 与 WMS 两仓的已批准重构，本文件记录 Auth；WMS 仍须完成 R12 真实验证与正常交付。采用 Claude project-refactoring/backend-implementation 与原仓库约定，不重做领域或基础设施选择。

| 模块 | main Java / 包数 | 最终责任 |
| --- | --- | --- |
| protocol | 32 / 1 | 已发布类型按业务契约类组织；公开全名及字段保持，避免消费方破坏 |
| core | 2 / 1 | 两个图协议适配器的实际协作，保持凝聚；有界响应/一致性/错误规则复用 |
| SDK | 10 / 2 | 9个稳定公开门面及内部HTTP字节收集/取消实现，不增透传包装 |
| governance | 128 / 42 | 目录、成员、邀请、授权审批、投影、执行、门户与迁移按真实责任组织 |
| admin | 53 / 19 | 管理HTTP、安全、审计、身份同步、workspace、组合根；保留包内配置协作 |
| server | 19 / 8 | 旧判权适配、安全、中央Access、上下文、观测及原Settings协作 |
| auth-console | 53 个源路径迁移 | 功能的页面/模型/组件及app shell；公共认证/API/组件沿用原责任 |
| portal | 20 个原源文件 | 已有catalog/components结构保留；运行catalog配置字节保持 |

A02–A04 精确映射及所有已知消费者已同步。治理运行时仍使用原 `mappers/governance/*.xml` 装载路径，只修改 namespace；新增结构检查同时验证类目录、Mapper接口及真实装载路径、自动配置和纯领域基础设施依赖。已发布 protocol 的161个公开类型与SDK9门面共170个类型以实际javap逐声明核对，并增加常驻ABI检查。原CLI、SpringBoot根扫描入口保持。

补充281处显式public/interface/annotation方法与构造器的中文原因说明，59处类型说明；Javac/DocTrees最终缺失均为0。Mapper说明基于真实表、参数、行锁、影响行数及分页语句；配置、凭据、线程上下文、订阅取消、错误和一致性要求按实际源码解释。Java可执行词法逐文件与A07前基线一致，SQL39、图模式6与运行配置2原字节保持；注释不改变规则或数据状态。

## 本轮实际行为修复

`useCommand` 原来依赖下一次React渲染才更新的busy/unknown状态。一个同步事件内双提交会发出2次在途请求；同步reset还会清空正在执行的冻结命令，未知结果重试再生成新幂等身份。真实React浏览器夹具在修改前两项均失败，修复后：同一事件仅1次在途交换；reset不清除在途/未知命令，重试沿用原commandId与payload。引用承担同步围栏，React状态继续负责显示；原401/403、超时/5xx及确定失败分类保持。此项 CHANGED 是明确缺陷修复。

可重复验证入口为 `node deploy/verify-use-command-race.mjs`，复用现有 `PLAYWRIGHT_MODULE` 或邻近commerce项目已安装的Playwright；只创建并结束自己的临时Vite和浏览器，夹具不访问业务后端。此回归在本机实际执行，不伪称远程CI已执行浏览器。原50控制台及29门户原生测试、类型/build/格式继续验证。

## 本地证据与交付门槛

全reactor332单测失败/错误/跳过0；50控制台、29门户、114部署脚本和7结构/公开接口检查测试通过。两个前端类型/构建，Java/XML/前端格式、Mapper/自动配置、目录和差异检查通过。SDK的精确65536字节/超1字节/后续交换回归在抽取前通过；已保留原超时/响应停滞、关联身份、范围、机器及错误测试。

A07真实差异的技能卫生CLI最终 `IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`、零finding。唯一限制 `FORMAT_TOOL_NOT_AVAILABLE` 是命令发现器无法识别独立Java格式工具，实际固定版本格式check已运行；没有修改技能规则。整仓格式的A01原CLI结果与同格式不可变Git基线的原引擎核对分别保留，授权格式整理不被伪装成新业务规则。

事务、唯一约束、锁顺序、投影marker/租约/READY、Owner、身份代际、撤权和TTL保持；异常与日志转换未改。没有新增表/迁移、MQ、缓存或运行依赖，没有改公开错误体系，也没有自动续发凭据。旧审计构造式DDL及容量策略属于原有持久化实现，本轮没有修改其建表或保留语义。

A08真实PostgreSQL/图/身份/投影、legacy/Boot4、两个精确候选CI及正常main交付仍须完成；本地检查不能代替这些验收，也不能代表本次重新部署。
