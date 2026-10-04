# MG17 验收结果（PASS）

2026-10-03。MG17 产品与必要验证 DONE；Git／精确 CI 状态由 [PROGRESS_STATE](PROGRESS_STATE.md) 维护，MG18／MG19尚未完成。对应 [冻结契约](MG17_CONTRACT.md) 和 [实施证据](MG17_IMPLEMENTATION_EVIDENCE.md)。28源码／测试／工具路径指纹 `0c88e0c87993be07e6ced221fc5d4b7cc3a565c9e5f26a7e0439f381d210e268`；验收后逐路径 SHA256 再核对一致。

## 验收映射与真实证据

| 范围 | 结果与证据 |
|---|---|
| 原子证据、重放、旧版本、冲突、真实前后事实 | 最新 `mg17-pg-time-final.log`：10项 PersonnelImpactPostgresIT，包含实际审计触发器失败后的整事务回滚、不可变更新／删除拒绝、表与所有字段注释、手工暂停保护、首次v7前值缺失、108变更／101个人历史分页；全部PASS |
| 诊断失权与隔离 | 同一最新轮次2项 PersonnelImpactQualificationIT：真实PG撤委派／暂停发生在读取中，成功数据被拒绝，DENIED审计1／ALLOWED0；spy仅控制时机，报告读取执行真实Mapper与事务；不存在和跨租户同拒绝 |
| 时间与游标 | 实际未来有效期跨过开始边界，Grant版本／状态未变但依据变化；clock_timestamp语义真实PG验证；游标绑定目标／类型／依据、100／10000上限、409重新读取 |
| 最终访问与多来源 | `mg17-graph-fourth.log`：3项真实PG＋授权图专项；调岗使GROUP失去当前资格但DIRECT／OA仍有效，逐源严格撤销和实际COMPLETED证明；LEFT旧身份拒绝、新代际不继承个人旧源、重新加入当前组才可用；同主体两个实际租户成员在全局暂停后均拒绝 |
| 回归 | 最新完整后端256单测PASS；此前同片未变化Directory17／Portal16真实PG回归保留，不冒充最新12项同次运行。图专项早于最后游标常量／时间边界补丁，图逻辑未变化；最终HTTP使用当前打包制品 |
| 前端守卫与构建 | `mg17-ui-all-final.log`：46单测PASS；`mg17-ui-third.log`类型／生产构建PASS；GROUP真实generation=0守卫及非法NULL回归已验证 |
| 实际HTTP／IdP／导入／投影 | 最终 `mg17-http-ff60945b6ce2/http-result.json`：114实际HTTP检查PASS、no-store、独立普通成员403、108受信目录事件、重放不重复、102来源及100／2分页、冲突不改变当前ACTIVE、旧依据409；真实PKCE、Auth、PG、授权图均运行 |
| 浏览器与视觉 | 同目录 `browser-result.json`：5组PASS，0页面错误、0浏览器管理写入；100／8变更分页、完整来源与双向入口、实际403；503／409响应恢复为明确标注的响应夹具。1440／390／320共18当前视口图全部实际查看PASS，见私密visual-review.json |
| 制品与保护 | 当前运行Admin Jar SHA256 `5eb86bb0f88bf5221b648045413d3936c544af42698fb40a70fba7a0fd4a5479`；重包用maven.jar.forceCreation，内嵌protocol／governance Jar和当前target字节一致。原业务Grant写入0，隔离来源在浏览器前后逐行相同；自有21826／21827已停止，端口实际检查关闭 |

各日志均为终态实际exit0，未将运行中结果或不同轮次累加成一次全量测试。真实业务资源HTTP验证使用服务端固定资源事实夹具，不以页面自填事实证明ALLOW。

## 卫生门禁与人工复核

`mg17-hygiene-delivery.json`：0 BLOCKING、8 ADVISORY，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；diff --check PASS。

- HF001：每页100／摘要10000／游标256已明确命名常量，对应冻结边界。
- HF002–004：5秒数据库读取事务，无事务内远程调用；前后依据及身份重验；目录写入与证据同事务，真实故障回滚、重放和失权测试已覆盖。
- HF005：工具CLI40秒、浏览器240秒、响应2MiB为显式测试预算，不改产品期限。
- HF006–008：专库创建30秒、SQL／HTTP10秒均有界；只操作自有目标，最终进程关闭与证据保留。

仓库无canonical formatter，FORMAT为SKIPPED／TOOL_NOT_AVAILABLE，按周边风格检查；未配置static analysis，记录NOT_APPLICABLE，不声称自动分析已执行。

## 失败记录、恢复与限制

保留所有失败日志／隔离专库／截图：初次101条活来源触发真实100上限后改为实际先撤一源再建立历史；真实GROUP generation=0与前端原NULL假设不符已修正并加回归；图夹具补等待和新身份读取，没有放宽业务拒绝。模态动画期间Escape和截图失败，工具改为等待真实焦点与动画结束后最终第七轮重新验收。

一次同库只读重验发生在浏览器之后实际冲突已把目录栅栏变为UPDATING，产品正确不再返回旧完成证明；未重置数据或伪造READY。最终HTTP仍验证该冲突和旧游标拒绝。前六轮和只读重验完整留档，不引用被遮罩的旧图为视觉PASS。

OA目录来源是独立HTTP契约夹具 `HTTP_CONTRACT_FIXTURE_NOT_REAL_OA_ENGINE`，真实OA引擎未联调；没有新增生产同步状态探针，source_sync保持UNKNOWN。没有人员生命周期写页面、岗位自动授予、生产部署、容量或RTO/RPO承诺。V35已实际应用，不可再编辑；旧证据不补造。全部专库、私密证据、自有IdP与7个既有工作树保留，未清理。
