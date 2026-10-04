# MG18 验收结果（PASS）

2026-10-03。产品与必要验证DONE，Git／精确CI由[PROGRESS_STATE](PROGRESS_STATE.md)记录；MG19仍待完成。对应[契约](MG18_CONTRACT.md)／[实施证据](MG18_IMPLEMENTATION_EVIDENCE.md)，21路径指纹 `184067df8cd88aa55a13c6d9656e7aa529b9dd37a544ea3f953a13a3d20c0b74`，最终后逐路径核对一致。

## 验收映射

| 范围 | 实际执行与结果 |
|---|---|
| 固定选源、幂等、状态和不可变输入 | mg18-pg-graph-fourth.log：10 AccessReviewPostgresIT PASS；单页选1而当前2、原键恢复、改体／跨操作复用键冲突、原任务／来源／审计不可改删；调查／保留／取消不改Grant |
| 权限与责任 | 同轮实际普通成员、跨分区、无独立诊断资格／退出负责人拒绝；实际改派新当前合格负责人并重放，原成员不能决定；候选不含只有管理资格者 |
| 撤权原子性与证明 | 同轮真实审计触发器故障使Grant／投影／命令／条目全部回滚；已发撤权取消后保留，外部撤权不算本任务完成，GROUP全组确认必需；重启Runtime保留原快照、检查点 |
| 实际最终访问 | 同轮2 AccessReviewProjectionIT PASS，真实PG＋授权图：DIRECT撤权实际删除后确认且OA仍ALLOW，随后OA严格撤权实际DENY；取消后已发一源继续确认，另一源ACTIVE／ALLOW。没有SQL伪造ACTIVE或COMPLETED |
| 后端回归与数据库 | 最新256单测PASS，实际三表／全部字段注释检查PASS。此前同片未变人员逻辑10 PersonnelImpactPostgresIT回归保留，不冒充同次最新12项专项 |
| 前端 | mg18-ui-tests-third.log：50测试PASS；mg18-ui-fourth.log：类型与生产构建PASS；固定／当前来源独立守卫、非法状态／重复项／无证明完成拒绝 |
| HTTP | 最终mg18-http-20cc1b859d40/http-result.json：20实际HTTP检查PASS，401／403／409、固定选源创建／重试／改体、no-store、取消不改Grant；真实IdP PKCE、PG、Auth、图 |
| 浏览器写流程 | 同目录browser-result.json：5组PASS，0页面错误；实际选择4来源创建，KEEP真实提交后503结果丢失夹具原键恢复；GROUP必填确认阻止提交再记录调查；严格REVOKE202／无实际证明确认409；取消及自有Admin实际退出／重启，实际投影后确认原来源，其他3条ACTIVE |
| 视觉与交互 | 六状态×1440／390／320共18当前图片全部实际查看PASS，见visual-review.json。任务卡片／固定及当前来源层级清楚；表单反馈与主次按钮一致；长来源详情弹层内滚动、底部关闭；手机容器横向滚动而页面宽度不溢出；503清空旧任务且重试恢复，实际普通成员403隐藏人员和写入口 |
| 制品与基线 | 当前Admin Jar SHA256 b345095e95897d6650ada2341c8f81dc8922fb45f37038bc18d3ec1e44e6bdd7，maven.jar.forceCreation并核对嵌套Jar一致。原业务Grant写入0，隔离4来源仅1REVOKED；实际旧PID96934退出、新PID97058启动。自有21828／21829实际检查关闭 |

日志均有实际exit0。浏览器503和响应丢失是明确响应夹具；进程退出／重启、提交、数据库检查点和授权图证明均实际执行，不将夹具当依赖断连证据。

## 卫生门禁与人工复核

mg18-hygiene-third.json：0 BLOCKING／9 ADVISORY，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；diff --check PASS。

- HF001：100选源／20任务／500原因／65536快照上限均为明确命名常量，DB另约束快照jsonb文本大小。
- HF002–004：5秒数据库事务；无事务内RPC；主体→成员→分区→任务及Grant锁，版本／资格前后重验；命令／原严格撤权／检查点／审计同事务，真实故障回滚和原键重放已验证。
- HF005–008：工具CLI40秒、浏览器240秒、响应10MiB、建库30秒、SQL／HTTP10秒为有界演练预算；仅自有目标与进程，finally关闭，保留证据。
- HF009：复核ItemState与历史OA申请State分别表达调查／保留／等待撤权和审批生命周期；不能复用审批状态混淆含义，显式code稳定。

FORMAT SKIPPED／TOOL_NOT_AVAILABLE，沿用周边风格；STATIC_ANALYSIS NOT_APPLICABLE，无配置不宣称自动运行。

## 失败与适用限制

第一轮把目标相关2变更误当全源3，修正为分别断言2与Inbox3；第二轮Ant双中文按钮自动空格影响工具定位，且必填校验产生未处理Promise，产品仅处理已展示的errorFields并在最终真实页面必填验收；第三轮控制工具独占配置重用触发FileExistsError，改为每次唯一配置并保留诊断。前三轮失败、专库和图片全部保留，最终第四轮重新执行完整HTTP／页面链路，不采用失败图作PASS。

真实OA引擎尚未联调，目录／批准为HTTP契约夹具；source_sync仍UNKNOWN，生产退役运行核验未接入时仍UNPROVEN。没有岗位自动授予、消息发送、生产部署、容量／RTO／RPO结论；全部专库、证据、自有IdP和7个既有工作树保留。
