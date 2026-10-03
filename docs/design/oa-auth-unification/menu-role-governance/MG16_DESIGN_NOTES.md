# MG16 引用退出影响分析（DRAFT）

2026-10-03。MG15 已实现弃用和停止新增使用；MG14 的组／审批来源迁移未完成。本文件是对 MG16 依赖和源码事实的只读核对，尚未冻结退役接口或新增最终状态，MG16 仍为 TODO。

## 已核实的复用边界

- [CatalogImpactMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/CatalogImpactMapper.xml) 的 MG03 统计回答“菜单变化可能影响谁”。Grant 只取当前起止时间内的 ACTIVE／PENDING，并过滤人员代际和组资格；未来生效的来源、未完成申请、迁移检查点及项目 API 映射不在完整引用集合中。不能把该统计为零当成退役条件已满足。
- 同一 Mapper 查询全部相关角色版本，也包含策略页，但角色历史存在与当前可执行来源存在是不同事实。MG16 需分别呈现阻断引用和审计引用，不能通过删除角色或历史策略消除引用数量。
- [RequestMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/RequestMapper.xml) 中 SUBMITTED／IN_REVIEW 申请、启动 Outbox、审批 Inbox 和已经批准但待投影的 Grant 有不同状态；查询当前 enabled 策略不能覆盖这些在途效果。
- [RoleMigrationTaskMapper](../../../../auth-platform-governance/src/main/resources/mappers/governance/RoleMigrationTaskMapper.xml) 保留旧源、新角色、计划新 Grant 和逐项检查点。撤旧已提交而授新尚未提交的任务仍持有目标能力引用，不能因当前 Grant 数为零而认定没有在途使用。GROUP／OA 部分需待 MG14 正式契约补齐。
- [MG06_CONTRACT](MG06_CONTRACT.md) 的部署声明一致不证明实际运行，runtime_state 仍为 UNKNOWN。固定导出候选、手填证据或已发布菜单不证明所有运行接口均退出该能力。
- [MG15_CONTRACT](MG15_CONTRACT.md) 的弃用元数据独立于 disabled；当前只有 ACTIVE／DEPRECATED。现有清单仍必须保留旧能力定义及历史摘要，不能把能力从 Manifest 删除充当退役。

## 待契约冻结的引用矩阵

| 引用类别 | 应核对的事实 | 退出判定边界 |
| --- | --- | --- |
| 菜单和项目映射 | 当前发布菜单关联、受信固定项目声明与实际 API 能力映射 | 删除最后菜单仅证明导航引用退出；API 未核验必须显示未证明。 |
| 角色与来源 | 全部角色版本；当前及未来生效 ACTIVE／PENDING 来源、范围、期限和来源 Owner | 有效或可生效来源阻断；已撤销／到期的历史来源保留审计。人员暂不可用不能自动等同来源已撤销。 |
| 管理委派 | enabled 委派的真实能力集合与期限上限 | 仍可使用该能力的委派需退出；纯缩减继续调用原受控链路。 |
| 策略与申请 | 当前策略、SUBMITTED／IN_REVIEW、批准待执行、启动与回调处理 | 不能只停用策略而忽略已经提交的申请；冻结判定时需重验当前版本。 |
| 迁移与投影 | DIRECT 及后续 GROUP／OA 任务，计划新源，操作及真实回执、策略／目录栅栏 | 未完成效果不能当历史；SQL 状态或意图记录不代替真实回执。 |
| 历史兼容记录 | 旧 Manifest、发布回执、角色版本、申请批准快照及已终结来源 | 保留稳定编码和原字节，不删除、不重写摘要、不恢复历史来源。 |

该矩阵是分析输入，不是最终 HTTP 字段或执行许可。最终退役前需固定应用／能力和引用依据，区分无权限、读取失败、不完整及真实零引用；读取后、提交前均核对依据和当前 Owner。并发新引用与正常退役必须在同一持久化边界串行，不能只靠页面先查询一次。紧急 disabled 仍可按原受控入口独立执行。

## 后续顺序

MG14 完成后冻结完整 MG16 契约，补所有引用的有界查询与完整性说明，再实现退出检查和受控最终退役。真实 PG／图验收需包含未来来源、在途申请、迁移授新间隙、并发引用、Owner 失权和旧成功命令重放；项目运行映射未核验必须保持未证明。未运行本片测试，不标 PASS 或 DONE，不新增运行设施。
