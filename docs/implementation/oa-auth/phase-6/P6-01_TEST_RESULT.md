# P6-01 验证与未决输入

总体：P6-01 PASS，限定用户选定commerce既有运营租户的隔离演练入口盘点和只读来源，不包含后续写冻结/切换。P6-02工具验证见独立报告。

| 检查 | 结果 | 边界 |
|---|---|---|
| 三仓 HEAD 与工作区核验 | PASS | auth/commerce 起始干净；OA 既有修改未触碰 |
| 源码入口、调用点及 SQL 盘点 | PASS（源码范围） | 见 P6-01_INVENTORY；含旧管理 API、间接写入、JDBC 定时回收、启动初始化、旧商品/价格/任务消费 |
| source-evidence.json 的55个文件路径及摘要 | PASS | 文件存在，当前字节与记录的 Git ref 一致 |
| 原 DAG 稳定 ID、依赖顺序、历史 DONE、生产授权 | PASS | 保留63节点；只更新 P6 授权与当前阻塞状态；生产仍 false |
| 目录权威与演练环境 | CONFIRMED | 用户选择OA、本地；P1目录来源记录亦支持OA |
| 首批来源单元及快照 | PASS | 用户指定commerce并授权选租户；P6_SELECTED_UNIT.json和私有快照摘要 |
| 目标身份/角色映射 | 后续P6-02 | 不把源选定当作映射完成 |
| 来源与目标隔离边界 | PASS（盘点） | 固定摘要来源，原商城workers/API/脚本只连原库；实际新目标账号/旧写冻结由P6-05验证 |
| dry-run、导入、增量、影子、切换、回退 | NOT_RUN | 未修改产品、数据库或服务；未用 P5 夹具替代真实存量 |

P6-01为盘点/选定单元文档；提取工具与测试归P6-02，不沿用P5通过结果声称迁移通过。执行 git diff --check 和 JSON/状态一致性核验。

SKILL_HANDOFF: slice=P6-01, status=COMPLETED, gate=PASS, produced=P6-01_INVENTORY.md/source-evidence.json/P6-01_TEST_RESULT.md, unresolved=P6-02目标身份及能力取舍, recommended_next=P6-02。
