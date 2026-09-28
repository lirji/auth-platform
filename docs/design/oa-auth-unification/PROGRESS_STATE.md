# 企业 IAM 当前进度

## 当前状态

**P3_COMPLETE_PAUSED**。P0/P1/P2已交付；本轮用户授权的P3全部10节点DONE，两仓代码正常合并推送main且远程CI SUCCESS。按要求暂停在P4之前，未执行生产部署。

## 完成证据

- 同Grant范围、ScopePlan、门店/商品真实Owner SQL、列表/计数/统计/详情/搜索/私密分批导出与下载复核完成。
- 主库PolicyPartition/DirectoryFence、A/C二次快照、远端marker CAS、持久租约/批次/receipt及真实进程恢复完成。
- 组任职/期限/代际、owner能力紧急停用、可靠撤权回执及目录运维时区边界完成。
- 本地auth228单测/PG67/P3图24/P2图7/Boot4通过；商城383项零失败（5既有条件跳过），新增MySQL6项、跨仓HTTP48项及双节点性能基线通过。
- auth程序/测试提交e55368a的CI36441586619 SUCCESS；commerce最终main ca4f831的CI36441627523 SUCCESS。auth后续纯文档同步不变更已验证程序/构建树。

详细事实见[交付记录](../../implementation/oa-auth/phase-3/P3_DELIVERY_RESULT.md)、[CI_RESULT](../../implementation/oa-auth/phase-3/CI_RESULT.md)、[Handoff](../../implementation/oa-auth/phase-3/P3_HANDOFF.md)。首轮auth Linux测试时钟精度失败已修正并全CI复验，不隐去失败历史。

## 未完成与停止点

P3范围无剩余实施/验证/交付事项。P4及之后未授权，下一候选P4-01仅为恢复入口，不能自动执行。原63节点DAG及依赖保持，全部后续节点仍TODO。

OA用户既有修改未动；两仓私密.local证据、隔离PG/MySQL/图和既有P0 detached基线保留，未清理。共享Casdoor升级HOLD、生产容量与保留治理前置仍保留，不影响已冻结P3隔离试点完成，也不构成生产就绪承诺。
