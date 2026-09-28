# 企业 IAM P3 Handoff

## 范围与完成状态

用户本轮授权「做P3」，停止点为P3全部完成后暂停。63节点DAG保留，P3-01/02/03/04a/04b/04c/04/05/06/07共10节点DONE；P4尚未授权。实现与本地验证已完成，最终远程交付观察见P3_DELIVERY_RESULT.md。

| 能力 | 产物/验收 |
|---|---|
| 同Grant固定数据范围 | ScopeRules、不可变范围快照、CONTRACTS_P3_SCOPE、P3-01/02_TEST_RESULT |
| 主库双版本栅栏与授权二次复查 | ReadFence、ReliableAuthorization、P3-03/05_TEST_RESULT |
| 远端marker CAS与租约批次 | SpiceDbProjectionGraph、ReliableProjection、P3-04a/b/c_TEST_RESULT |
| 组织组、任职期限、能力紧急停用、撤权回执 | V12、SafetyMapper、P3-06_TEST_RESULT |
| 商品/门店Owner SQL、游标、私密恢复导出 | commerce CentralScopeService/ScopeQuery、V47、P3-02_TEST_RESULT |
| 真实双节点、进程故障与本地延迟 | P3-07_TEST_RESULT、P3_FAULT_MATRIX、evidence/*.json |

## 运行与恢复

auth迁移已推进至V12，commerce至V47，禁止修改已执行历史。Scope服务/商城scope开关缺省关闭；需要双身份服务凭据、显式中央到本地身份桥和完整Owner映射。上线严格分区后不得调用旧P2 check降级；菜单不替代资源范围。范围Owner仅绑定store/product的ALL/STORES/RESOURCES，其他类型明确拒绝。

policy与directory独立投影，CLI一次有界批次，必须两者READY。UPDATING/BLOCKED不允许旧图放行，retry只恢复受审计执行，不能伪造marker/receipt。新Grant在双水位完全可见前可能保守DENY。目录时区只能由精确来源0600运维CLI设置；未配置、未知来源或隔离状态不会通过组授权。

导出SUBMITTED→RUNNING→COMPLETED，每批50、总量1000、每用户2/租户10未到期任务；进程失败从DB检查点恢复。每阶段重验绑定，策略变更后需重新提交，下载还检查当前资源版本。15分钟访问期限不等同物理删除；生产保留/清理策略尚未制定。详见商城RUNTIME_AND_CONTRACTS.md。

本次没有新建worktree；已有commerce P0 detached基线在auth/.local/p0-baselines/commerce保持。auth/.local/governance与commerce/.local/oa-auth-p3含0600私密配置和证据，应保留；target/dist/node_modules及脚本缓存可重建，但本轮未删除。隔离IdP18090、图18543/18544及专用PG/MySQL库保留以便复验。验收自有auth/admin/commerce HTTP进程已结束。OA已有用户改动未触碰。

## 后续停止点

暂停在P4之前，下一设计节点P4-01（AccessRequest不可变快照），本次不开始。共享Casdoor8000升级HOLD继续保留；隔离SSO通过不等于生产SSO或部署完成。未来P4恢复时先读PROGRESS_STATE、DAG及本Handoff，不重做P1/P2/P3。
