# P3-02 ScopePlan与资源Owner范围适配

Status: DONE；Validation: PASS。

继P3-02_AUTH_API_RESULT协议检查点，门店与商品的真实Owner SQL、详情、列表/数量/统计/搜索、随机绑定游标、私密分批导出和下载均完成。商城SDK固定637385b（协议移除未发布目录时区DTO；ScopePlan语义与0168985相同）。完整行为与路由权威矩阵见commerce docs/implementation/oa-auth/phase-3/RUNTIME_AND_CONTRACTS.md；P3只接入当前门店/商品只读试点，未扩大到后续写流程。

- Scope服务与SDK：请求关联、全部上下文字段、类型/版本、短期限与256KiB上限，未绑定范围拒绝；详情仅接受受信Owner构造的真实归属和版本。同Grant范围不跨路径组合。
- 菜单在显式scope.enabled时走严格分区完整Grant候选；只表示访问提示，无法替代资源范围判断；非严格P2分区保留既有行为。
- 商城真实MySQL六项新增测试通过，覆盖游标绑定、批次失败整事务回滚、双执行者排他、命令幂等、任务申请人及配额隔离。完整mvn verify 383项：378通过、5既有条件跳过，零失败/错误。
- 跨进程真实Casdoor PKCE、双auth节点、资源Owner/MySQL共48项PASS，详见evidence/http-result.json。真实SIGKILL后进程恢复完成55行导出，旧任务/游标不能采用新策略，已完成导出也被撤权阻断；停auth不回退旧ACL。
- auth全仓228单测、PG67、P3图24、P2图7及Boot4兼容通过。失败过程及定向复测记录保留在商城报告，无虚构生产SLO。
- Code Hygiene：COMPLETE_WITH_LIMITATIONS，仓库无既有formatter/静态分析器；SQL、事务、固定预算与异常边界已人工复查，无剩余阻断Finding。

P3-02已完成；P3-07聚合故障/性能证据及整个阶段交付，不能用本报告替代远程CI结果。
